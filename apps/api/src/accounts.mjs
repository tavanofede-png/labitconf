import { createHash, createHmac, randomBytes, randomInt, randomUUID, timingSafeEqual } from 'node:crypto';

const digest = value => createHash('sha256').update(value).digest('hex');
const token = () => randomBytes(32).toString('base64url');
const roles = ['user', 'family', 'institution'];
const questionIds = { user: ['purpose', 'interaction', 'pace', 'priority'], family: ['relationship', 'support', 'frequency', 'guidance'], institution: ['objective', 'evidence', 'work'] };
export class AccountError extends Error { constructor(status, code, message) { super(message); this.status = status; this.code = code; } }
function check(condition, status, code, message) { if (!condition) throw new AccountError(status, code, message); }
const email = value => { check(typeof value === 'string' && value.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value), 400, 'invalid_email', 'Ingresá un correo válido.'); return value.trim().toLowerCase(); };
const cleanText = (value, max = 100) => typeof value === 'string' ? value.trim().slice(0, max) : '';
const usernamePattern = /^[a-z0-9_.]{3,30}$/;
const usernameBase = name => cleanText(name, 80).normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
  .replace(/[^a-z0-9]+/g, '.').replace(/^\.+|\.+$/g, '').slice(0, 24).replace(/\.+$/g, '') || 'usuario';
const availableUsername = (state, name, accountId) => !(state.accounts ?? []).some(a => a.id !== accountId && a.profile?.username?.toLowerCase() === name.toLowerCase());
function phoneNumber(value) {
  check(typeof value === 'string' && value.length <= 40 && /^\+?[\d ()-]+$/.test(value.trim()), 400, 'invalid_phone', 'Ingresá un teléfono válido. Es obligatorio.');
  const digits = value.replace(/\D/g, '');
  check(digits.length >= 8 && digits.length <= 15, 400, 'invalid_phone', 'El teléfono debe tener entre 8 y 15 dígitos.');
  return (value.trim().startsWith('+') ? '+' : '') + digits;
}
const equal = (a, b) => typeof a === 'string' && typeof b === 'string' && a.length === b.length && timingSafeEqual(Buffer.from(a), Buffer.from(b));
const init = s => { for (const key of ['accounts', 'authChallenges', 'authSessions', 'familyJoinRequests']) s[key] ??= []; };

/** Passwordless account identity, separate from anonymous installations and family consent. */
export function createAccounts({ store, now = Date.now, sendCode, authSecret = process.env.AUTH_SECRET,
  localDemo = process.env.TINO_LOCAL_DEMO === '1', judgesDemo = process.env.TINO_JUDGES_DEMO === '1', demoEmail = process.env.TINO_DEMO_EMAIL ?? 'test@labitconf.com', resendKey = process.env.RESEND_API_KEY,
  mailFrom = process.env.AUTH_EMAIL_FROM, institutionEmails = (process.env.INSTITUTION_EMAILS ?? '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean),
  onOperatorCode = ({ email, code }) => console.log(`[TINO PRUEBA LOCAL — NO CORREO] ${email}: ${code}`),
} = {}) {
  // In local operator mode codes expire on restart. Production delivery requires an explicit server secret.
  const demoAddress = judgesDemo ? email(demoEmail) : '';
  const demoAllowed = account => judgesDemo && account.demo === true && account.email === demoAddress && account.role === 'user';
  const pepper = authSecret || randomBytes(32).toString('hex');
  const codeHash = (id, code) => createHmac('sha256', pepper).update(`${id}:${code}`).digest('hex');
  async function principal(bearer) {
    if (!bearer) return null;
    const state = await store.read();
    const session = (state.authSessions ?? []).find(s => s.tokenHash === digest(bearer) && s.expiresAt > now());
    const account = session && (state.accounts ?? []).find(a => a.id === session.accountId);
    if (account?.demo && !demoAllowed(account)) return null;
    return account ? { ...account, institutionApproved: account.role === 'institution' && institutionEmails.includes(account.email), sessionId: session.id } : null;
  }
  function snapshot(account, bearer) {
    return { role: account.role, profile: account.profile ?? {}, onboardingRequired: !account.onboarded, demo: account.demo === true,
      ...(account.deviceId ? { deviceId: account.deviceId, deviceToken: bearer } : {}),
      ...(account.groupId ? { caregiverToken: bearer } : {}),
      ...(account.role === 'institution' && institutionEmails.includes(account.email) ? { institutionToken: bearer } : {}),
      ...(account.role === 'institution' && !institutionEmails.includes(account.email) ? { accessStatus: 'institution_approval_required' } : {}),
      ...(account.pendingGroupId ? { accessStatus: 'group_approval_required' } : {}) };
  }
  async function handle({ route, method, body, bearer, remoteAddress }) {
    if (method === 'POST' && route === '/auth/demo/login') {
      check(judgesDemo, 404, 'demo_unavailable', 'El acceso de demostración no está habilitado.');
      check(body && typeof body === 'object' && Object.keys(body).every(key => ['email', 'password'].includes(key)), 400, 'invalid_demo_input', 'Ingresá el correo y la contraseña de prueba.');
      const address = email(body.email);
      check(address === demoAddress && typeof body.password === 'string' && body.password.length <= 64 && equal(digest(body.password), digest('0000')), 401, 'invalid_demo_credentials', 'El correo o la contraseña de prueba no son correctos.');
      const bearerToken = token(), current = now();
      const result = await store.update(s => {
        init(s);
        let account = s.accounts.find(a => a.demo === true && a.email === demoAddress && a.role === 'user');
        if (!account) {
          let username = 'labitconf.demo', suffix = 1;
          while (!availableUsername(s, username)) username = `labitconf.demo${++suffix}`;
          account = { id: randomUUID(), email: demoAddress, role: 'user', demo: true, onboarded: true, createdAt: new Date(current).toISOString(),
            profile: { name: 'TINO Demo', email: demoAddress, username, phone: '+540000000000', territoryId: 'AR-C' },
            answers: { purpose: 0, interaction: 3, pace: 0, priority: 3 },
            preferences: { microphone: false, notifications: false, screen: false, support: false, aggregates: false }, deviceId: randomUUID() };
          s.accounts.push(account);
          s.users.push({ id: account.deviceId, accountId: account.id, name: account.profile.name, territoryId: 'AR-C', demo: true, tokenHash: digest(token()), createdAt: new Date(current).toISOString() });
        }
        s.authSessions = s.authSessions.filter(session => session.expiresAt > current);
        s.authSessions.push({ id: randomUUID(), accountId: account.id, tokenHash: digest(bearerToken), expiresAt: current + 7 * 86_400_000 });
        return snapshot(account, bearerToken);
      });
      return [200, { ...result, sessionToken: bearerToken }];
    }
    if (method === 'POST' && ['/accounts/username/suggest', '/accounts/username/check'].includes(route)) {
      const account = await principal(bearer);
      const state = await store.read();
      if (route.endsWith('/check')) {
        const username = cleanText(body.username, 31).toLowerCase();
        check(usernamePattern.test(username), 400, 'invalid_username', 'Usá entre 3 y 30 letras, números, puntos o guiones bajos.');
        return [200, { username, available: availableUsername(state, username, account?.id) }];
      }
      check(typeof body.name === 'string' && body.name.trim().length > 0 && body.name.length <= 100, 400, 'invalid_name', 'Ingresá tu nombre para generar un usuario.');
      let base = usernameBase(body.name); if (base.length < 3) base += '.tino';
      let username = base, suffix = 1;
      while (!availableUsername(state, username, account?.id)) { suffix++; username = `${base}${suffix}`; }
      return [200, { username, available: true }];
    }
    if (method === 'POST' && route === '/auth/code/request') {
      const address = email(body.email); check(roles.includes(body.role), 400, 'invalid_role', 'Elegí un tipo de cuenta válido.');
      check(['login', 'signup', 'family_join'].includes(body.purpose), 400, 'invalid_purpose', 'El motivo del acceso no es válido.');
      const adminEmail = body.purpose === 'family_join' ? email(body.adminEmail) : null;
      const operator = localDemo && ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(remoteAddress);
      check(sendCode || operator || (resendKey && mailFrom && authSecret?.length >= 32), 503, 'email_not_configured', 'Falta configurar el envío de códigos por correo. El equipo puede habilitar el modo de prueba local explícito.');
      const challengeId = randomUUID(), code = String(randomInt(0, 1_000_000)).padStart(6, '0'), current = now();
      await store.update(s => {
        init(s); s.authChallenges = s.authChallenges.filter(c => c.expiresAt > current && !c.used);
        check(!s.authChallenges.some(c => c.email === address && c.role === body.role && current - c.createdAt < 60_000), 429, 'code_cooldown', 'Esperá un minuto antes de solicitar otro código.');
        s.authChallenges = s.authChallenges.filter(c => !(c.email === address && c.role === body.role));
        s.authChallenges.push({ id: challengeId, email: address, role: body.role, adminEmail, purpose: body.purpose, hash: codeHash(challengeId, code), createdAt: current, expiresAt: current + 300_000, attempts: 0, used: false, delivered: false });
      });
      try {
        if (sendCode) await sendCode({ email: address, code });
        else if (operator) await onOperatorCode({ email: address, code });
        else {
          const response = await fetch('https://api.resend.com/emails', { method: 'POST', headers: { Authorization: `Bearer ${resendKey}`, 'Content-Type': 'application/json', 'Idempotency-Key': challengeId },
            body: JSON.stringify({ from: mailFrom, to: [address], subject: 'Tu código de acceso a TINO', text: `Tu código de acceso a TINO es ${code}. Vence en 5 minutos. No lo compartas. Si no lo pediste, ignorá este mensaje.` }), signal: AbortSignal.timeout(15_000) });
          check(response.ok, 502, 'email_delivery_failed', 'No pudimos enviar el código. Volvé a intentarlo en un minuto.');
        }
        await store.update(s => { const c = s.authChallenges.find(c => c.id === challengeId); if (c) c.delivered = true; });
      } catch (error) { await store.update(s => { s.authChallenges = s.authChallenges.filter(c => c.id !== challengeId); }); throw error instanceof AccountError ? error : new AccountError(502, 'email_delivery_failed', 'No pudimos entregar el código. Probá nuevamente.'); }
      return [200, { challengeId, expiresAt: new Date(current + 300_000).toISOString(), retryAfterSeconds: 60, delivery: operator ? 'operator' : 'email' }];
    }
    if (method === 'POST' && route === '/auth/code/verify') {
      check(typeof body.challengeId === 'string' && /^\d{6}$/.test(body.code), 400, 'invalid_code', 'Ingresá el código de seis dígitos.');
      const bearerToken = token(), current = now();
      const result = await store.update(s => {
        init(s); const c = s.authChallenges.find(c => c.id === body.challengeId);
        if (!c || !c.delivered || c.used || c.expiresAt <= current || c.attempts >= 5) return null;
        c.attempts++;
        if (!equal(c.hash, codeHash(c.id, body.code))) return null;
        c.used = true;
        let account = s.accounts.find(a => !a.demo && a.email === c.email && a.role === c.role);
        if (!account) { account = { id: randomUUID(), email: c.email, role: c.role, onboarded: false, createdAt: new Date(current).toISOString() }; s.accounts.push(account); }
        if (c.purpose === 'family_join') account.requestedAdminEmail = c.adminEmail;
        s.authSessions = s.authSessions.filter(session => session.expiresAt > current);
        s.authSessions.push({ id: randomUUID(), accountId: account.id, tokenHash: digest(bearerToken), expiresAt: current + 7 * 86_400_000 });
        return snapshot(account, bearerToken);
      });
      check(result, 400, 'invalid_code', 'El código no es válido, venció o superó los intentos permitidos.');
      return [200, { ...result, sessionToken: bearerToken }];
    }
    if (!route.startsWith('/accounts/')) return null;
    const account = await principal(bearer); check(account, 401, 'account_required', 'Verificá tu correo para continuar.');
    if (method === 'GET' && route === '/accounts/me') return [200, snapshot(account, bearer)];
    if (method === 'DELETE' && route === '/accounts/session') { await store.update(s => { s.authSessions = (s.authSessions ?? []).filter(session => session.id !== account.sessionId); }); return [200, { ok: true }]; }
    if (method === 'POST' && route === '/accounts/onboarding') {
      check(body.role === account.role, 403, 'role_mismatch', 'El tipo de cuenta no corresponde al acceso verificado.');
      const input = body.profile;
      check(input && typeof input === 'object' && !Array.isArray(input), 400, 'invalid_profile', 'Completá tu perfil.');
      check(email(input.email) === account.email, 403, 'email_mismatch', 'El correo debe coincidir con el que verificaste.');
      const profile = Object.fromEntries(['name', 'email', 'username', 'phone', 'birthDate', 'territoryId', 'organization', 'organizationType', 'position'].map(key => [key, cleanText(input[key], key === 'email' ? 254 : 100)]));
      profile.email = account.email;
      check(profile.name && /^AR-[A-Z]$/.test(profile.territoryId), 400, 'invalid_profile', 'Completá el nombre y el territorio.');
      profile.username = profile.username.toLowerCase();
      check(usernamePattern.test(profile.username), 400, 'invalid_username', 'El nombre de usuario es obligatorio y admite de 3 a 30 letras, números, puntos o guiones bajos.');
      profile.phone = phoneNumber(input.phone);
      check(account.role !== 'institution' || profile.organization, 400, 'organization_required', 'Ingresá el nombre de la institución.');
      check(questionIds[account.role].every(id => Number.isInteger(body.answers?.[id]) && body.answers[id] >= 0 && body.answers[id] <= 3), 400, 'invalid_answers', 'Respondé todas las preguntas con una opción A, B, C o D.');
      const answers = Object.fromEntries(questionIds[account.role].map(id => [id, body.answers[id]]));
      const preferences = Object.fromEntries(['microphone', 'notifications', 'screen', 'support', 'aggregates'].map(key => [key, body.preferences?.[key] === true]));
      const result = await store.update(s => {
        init(s); const a = s.accounts.find(a => a.id === account.id);
        check(availableUsername(s, profile.username, a.id), 409, 'username_unavailable', 'Ese nombre de usuario ya está en uso. Elegí otro.');
        a.profile = profile; a.preferences = preferences; a.answers = answers; a.onboarded = true;
        let invitation = {};
        if (a.role === 'user') {
          let device = s.users.find(u => u.id === a.deviceId);
          if (!device) { device = { id: randomUUID(), accountId: a.id, name: profile.name, territoryId: profile.territoryId, tokenHash: digest(token()), createdAt: new Date(now()).toISOString() }; s.users.push(device); a.deviceId = device.id; }
          device.name = profile.name; device.territoryId = profile.territoryId;
        }
        if (a.role === 'family' && !a.groupId && body.familyMode === 'join') {
          const admin = s.accounts.find(other => other.role === 'family' && other.email === a.requestedAdminEmail && other.groupId);
          // Identical response whether the group exists. Membership requires its administrator's approval.
          if (admin && !s.familyJoinRequests.some(r => r.accountId === a.id && r.groupId === admin.groupId && !r.resolvedAt)) {
            a.pendingGroupId = admin.groupId; s.familyJoinRequests.push({ id: randomUUID(), accountId: a.id, groupId: admin.groupId, createdAt: new Date(now()).toISOString() });
          }
        } else if (a.role === 'family' && !a.groupId) {
          const group = { id: randomUUID(), name: `Red de ${profile.name}`.slice(0, 60), tokenHash: digest(token()), adminAccountId: a.id, createdAt: new Date(now()).toISOString() };
          s.groups.push(group); a.groupId = group.id;
          const invitationCode = randomBytes(5).toString('hex').toUpperCase(); const expiresAt = new Date(now() + 900_000).toISOString();
          s.invitations.push({ id: randomUUID(), groupId: group.id, codeHash: digest(invitationCode), expiresAt }); invitation = { invitationCode, expiresAt };
          a.targetIdentifier = cleanText(body.targetIdentifier);
        }
        return { ...snapshot(a, bearer), ...invitation, accessStatus: a.role === 'family' && !a.groupId ? 'group_approval_required' : snapshot(a, bearer).accessStatus };
      });
      return [200, result];
    }
    const approve = /^\/accounts\/family\/requests\/([a-f0-9-]+)\/approve$/.exec(route);
    if (method === 'POST' && approve) {
      await store.update(s => {
        const group = s.groups.find(g => g.id === account.groupId && g.adminAccountId === account.id);
        check(group, 403, 'admin_required', 'Solo el administrador del grupo puede aprobar incorporaciones.');
        const request = (s.familyJoinRequests ?? []).find(r => r.id === approve[1] && r.groupId === group.id && !r.resolvedAt);
        check(request, 404, 'request_not_found', 'Solicitud inexistente.');
        const member = s.accounts.find(a => a.id === request.accountId); member.groupId = group.id; delete member.pendingGroupId; request.resolvedAt = new Date(now()).toISOString();
      }); return [200, { ok: true }];
    }
    return null;
  }
  return { handle, principal };
}
