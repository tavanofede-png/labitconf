import http from 'node:http';
import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { createAndroidArtifacts } from './android-artifact.mjs';
import { JsonStore } from './store.mjs';
import { OpenArgClient, OpenArgError } from './openarg.mjs';
import { normalizeSearch, normalizeAnswer } from './normalize.mjs';
import { getPublicTerritoryIndicators } from './public-indicators.mjs';
import { RealtimeService, RealtimeError } from './realtime.mjs';
import { attachRealtimeSocket } from './realtime-socket.mjs';
import { institutionOverview } from './institution.mjs';
import { SessionError, createSession, applySessionEvent, publicSession, operationalMetrics } from './sessions.mjs';
import { RealtimeAdmission, directOrigin } from './admission.mjs';

const API_ROOT = fileURLToPath(new URL('../', import.meta.url));
const TERRITORIES = {
  'AR-C': 'Ciudad Autónoma de Buenos Aires', 'AR-B': 'Provincia de Buenos Aires', 'AR-X': 'Córdoba',
  'AR-S': 'Santa Fe', 'AR-M': 'Mendoza', 'AR-T': 'Tucumán', 'AR-A': 'Salta', 'AR-E': 'Entre Ríos',
  'AR-H': 'Chaco', 'AR-U': 'Chubut', 'AR-K': 'Catamarca', 'AR-W': 'Corrientes', 'AR-P': 'Formosa',
  'AR-Y': 'Jujuy', 'AR-L': 'La Pampa', 'AR-F': 'La Rioja', 'AR-N': 'Misiones', 'AR-Q': 'Neuquén',
  'AR-R': 'Río Negro', 'AR-J': 'San Juan', 'AR-D': 'San Luis', 'AR-Z': 'Santa Cruz',
  'AR-G': 'Santiago del Estero', 'AR-V': 'Tierra del Fuego, Antártida e Islas del Atlántico Sur',
};
const hash = (text) => createHash('sha256').update(text).digest('hex');
const secret = () => randomBytes(32).toString('base64url');
const same = (left, right) => typeof left === 'string' && typeof right === 'string' && timingSafeEqual(Buffer.from(hash(left)), Buffer.from(hash(right)));
const publicGroup = (group) => ({ id: group.id, name: group.name });

class ApiError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
const required = (test, status, code, message) => { if (!test) throw new ApiError(status, code, message); };
function text(value, key, max = 100, optional = false) {
  if (optional && value === undefined) return undefined;
  required(typeof value === 'string' && value.trim().length > 0 && value.length <= max, 400, 'invalid_input', `${key} debe ser un texto de 1 a ${max} caracteres.`);
  return value.trim();
}
function keys(body, allowed) {
  required(body && typeof body === 'object' && !Array.isArray(body), 400, 'invalid_input', 'Se esperaba un objeto JSON.');
  required(Object.keys(body).every((key) => allowed.includes(key)), 400, 'unexpected_field', 'El pedido contiene campos no permitidos.');
}
function territory(value = 'AR-C') {
  required(Object.hasOwn(TERRITORIES, value), 400, 'unsupported_territory', 'Elegí una provincia argentina o Ciudad Autónoma de Buenos Aires con su código ISO 3166-2.');
  return value;
}
function sessionsFor(state, userId) { return state.sessions.filter((s) => s.userId === userId); }
function metricsFor(sessions) {
  return operationalMetrics(sessions);
}
function inviteFor(state, groupId, now) {
  const invitationCode = randomBytes(5).toString('hex').toUpperCase();
  const expiresAt = new Date(now + 15 * 60 * 1000).toISOString();
  state.invitations = state.invitations.filter((i) => !i.usedAt && Date.parse(i.expiresAt) > now);
  state.invitations.push({ id: randomUUID(), groupId, codeHash: hash(invitationCode), expiresAt });
  return { invitationCode, expiresAt };
}
function audit(state, action, actorId, now) {
  state.audit.push({ id: randomUUID(), action, actorId, at: new Date(now).toISOString() });
  if (state.audit.length > 5000) state.audit.splice(0, state.audit.length - 5000);
}

async function bodyOf(req, maxBytes) {
  if (!['POST', 'PATCH'].includes(req.method)) return {};
  required((req.headers['content-type'] ?? '').split(';')[0].trim() === 'application/json', 415, 'content_type', 'Usá Content-Type: application/json.');
  required(Number(req.headers['content-length'] ?? 0) <= maxBytes, 413, 'body_too_large', 'El pedido excede el tamaño permitido.');
  let size = 0; const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    required(size <= maxBytes, 413, 'body_too_large', 'El pedido excede el tamaño permitido.'); chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); }
  catch { throw new ApiError(400, 'invalid_json', 'JSON inválido.'); }
}

export function createApp({
  dataFile = process.env.DATA_FILE ? resolve(process.env.DATA_FILE) : resolve(API_ROOT, 'data/nieto.json'),
  store = new JsonStore(dataFile), openarg = new OpenArgClient({ timeoutMs: Number(process.env.OPENARG_TIMEOUT_MS) || 25_000 }),
  realtime = new RealtimeService(),
  androidArtifacts = createAndroidArtifacts(),
  institutionToken = process.env.INSTITUTION_TOKEN ?? '', minimumCohort = Number(process.env.MINIMUM_COHORT) || 5,
  corsOrigins = (process.env.CORS_ORIGINS ?? 'http://localhost:5173,http://127.0.0.1:5173').split(',').map((s) => s.trim()),
  now = Date.now, maxBodyBytes = 16_384, rateLimit = 180, rateWindowMs = 60_000,
  realtimeBudgetLimits = {},
} = {}) {
  minimumCohort = Math.max(5, Math.trunc(minimumCohort));
  const admission = new RealtimeAdmission(store, { now, limits: realtimeBudgetLimits });
  const rates = new Map(); const realtimeAttempts = new Map();
  const handler = async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store'); res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer'); res.setHeader('Vary', 'Origin');
    const respond = (status, data) => { res.writeHead(status); res.end(JSON.stringify(data)); };
    try {
      const origin = req.headers.origin;
      if (origin) {
        required(corsOrigins.includes(origin), 403, 'origin_not_allowed', 'Origen no permitido.');
        res.setHeader('Access-Control-Allow-Origin', origin);
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, DELETE, OPTIONS');
      }
      if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && ['/downloads/nieto-debug.apk', '/api/downloads/nieto-debug.apk'].includes(url.pathname)) {
        const apk = await androidArtifacts.open();
        if (res.destroyed || req.aborted) { apk?.stream.destroy(); return; }
        required(apk, 404, 'apk_not_built', 'No hay un APK con manifiesto y hash de compilación válidos. Compilá Android nuevamente.');
        res.setHeader('Content-Type', 'application/vnd.android.package-archive'); res.setHeader('Content-Length', apk.size);
        res.setHeader('Content-Disposition', 'attachment; filename="nieto-debug.apk"');
        res.setHeader('ETag', `"${apk.sha256}"`); res.setHeader('X-Android-Version', apk.versionName); res.setHeader('Cache-Control', 'no-cache');
        res.on('close', () => apk.stream.destroy()); apk.stream.on('error', () => res.destroy()).pipe(res); return;
      }
      required(url.pathname.startsWith('/api/'), 404, 'not_found', 'Ruta inexistente.');
      const route = url.pathname.slice(4).replace(/\/$/, '') || '/';
      const token = /^Bearer ([A-Za-z0-9_\-.]+)$/i.exec(req.headers.authorization ?? '')?.[1] ?? '';
      const current = now();
      // Do not trust X-Forwarded-For without a configured reverse proxy.
      if (rates.size > 5000) for (const [key, value] of rates) if (value.resetAt <= current) rates.delete(key);
      for (const rateKey of [`ip:${req.socket.remoteAddress}`, ...(token ? [`token:${hash(token)}`] : [])]) {
        let rate = rates.get(rateKey);
        if (!rate || rate.resetAt <= current) { rate = { count: 0, resetAt: current + rateWindowMs }; rates.set(rateKey, rate); }
        required(++rate.count <= rateLimit, 429, 'rate_limited', 'Demasiados pedidos. Esperá un minuto.');
      }
      const body = await bodyOf(req, maxBodyBytes);
      const method = req.method;

      if (method === 'GET' && route === '/health') {
        const status = openarg.status();
        const apk = await androidArtifacts.info();
        respond(200, { status: 'ok', version: '2.0.0', openarg: { configured: status.configured, connected: Boolean(status.transportConnected), endpoint: status.endpoint,
          transportError: status.transportError, queryError: status.queryError },
          privacy: { minimumCohort, screenStorage: false, conversationStorage: false }, institutionConfigured: Boolean(institutionToken),
          android: { apkAvailable: Boolean(apk), downloadUrl: apk ? '/downloads/nieto-debug.apk' : null, versionName: apk?.versionName ?? null, versionCode: apk?.versionCode ?? null, sha256: apk?.sha256 ?? null },
          assistant: { ...realtime.status(), ...admission.status(), maxConnectionsPerOrigin: realtime.maxConnectionsPerOrigin }, storage: 'local_json_single_process' }); return;
      }
      if (method === 'POST' && route === '/devices') {
        keys(body, ['name', 'territoryId', 'enrollmentCode']);
        const name = text(body.name, 'name', 60); const territoryId = territory(body.territoryId);
        const deviceId = randomUUID(); const newToken = secret();
        await store.update((state) => {
          const user = { id: deviceId, name, territoryId, tokenHash: hash(newToken), createdAt: new Date(current).toISOString() };
          admission.activate(state, user, body.enrollmentCode); state.users.push(user);
        });
        respond(201, { deviceId, token: newToken, realtimeApproved: true }); return;
      }
      if (method === 'POST' && route === '/family/groups') {
        keys(body, ['name']); const name = text(body.name, 'name', 60); const caregiverToken = secret();
        const result = await store.update((state) => {
          const group = { id: randomUUID(), name, tokenHash: hash(caregiverToken), createdAt: new Date(current).toISOString() };
          state.groups.push(group); audit(state, 'group_created', group.id, current);
          return { group: publicGroup(group), caregiverToken, ...inviteFor(state, group.id, current) };
        }); respond(201, result); return;
      }

      const institutional = route.startsWith('/institution/') || route.startsWith('/openarg/');
      if (institutional) {
        required(institutionToken, 503, 'institution_not_configured', 'Configurá INSTITUTION_TOKEN en el backend para habilitar el portal institucional.');
        required(same(token, institutionToken), 401, 'unauthorized', 'Token institucional inválido o ausente.');
      }

      if (method === 'POST' && route === '/institution/device-invitations') {
        keys(body, []); respond(201, await admission.invitation()); return;
      }
      if (method === 'GET' && route === '/institution/devices') {
        const state = await store.read();
        const usage = admission.usage(state);
        respond(200, { devices: state.users.map(user => ({ deviceId: user.id, realtimeApproved: user.realtimeApproved === true,
          approvedAt: user.realtimeApprovedAt ?? null, createdAt: user.createdAt, budget: usage.device(user.id) })),
          budget: { ...admission.status(), day: usage.day, globalReserved: usage.globalReserved } }); return;
      }
      const revokeDevice = /^\/institution\/devices\/([a-f0-9-]+)\/revoke$/.exec(route);
      if (method === 'POST' && revokeDevice) {
        keys(body, []);
        await store.update(state => {
          const user = state.users.find(item => item.id === revokeDevice[1]);
          required(user, 404, 'device_not_found', 'Teléfono inexistente.');
          user.realtimeApproved = false; user.realtimeRevokedAt = new Date(current).toISOString();
          audit(state, 'realtime_access_revoked', user.id, current);
        });
        realtime.active.get(revokeDevice[1])?.fail(new RealtimeError('device_activation_required', 'El equipo revocó el acceso a la asistencia de este teléfono.', 403));
        respond(200, { ok: true }); return;
      }

      if (method === 'GET' && route === '/openarg/status') {
        const status = await openarg.discover();
        respond(200, { configured: status.configured, connected: Boolean(status.transportConnected), endpoint: status.endpoint,
          tools: status.tools.map(({ name, description }) => ({ name, description })), error: !status.configured ? { code: 'credentials_required', message: 'Configurá OPENARG_API_KEY en el backend.' } : status.lastError,
          transport: status.transport, lastSuccessAt: status.lastSuccessAt, serverInfo: status.serverInfo,
          transportError: status.transportError, queryError: status.queryError,
          lastTransportSuccessAt: status.lastTransportSuccessAt, lastTransportCheckAt: status.lastTransportCheckAt }); return;
      }
      if (method === 'GET' && ['/openarg/search', '/openarg/territory'].includes(route)) {
        const territoryId = territory(url.searchParams.get('territoryId') ?? undefined);
        const isTerritory = route.endsWith('territory'); const suppliedQuery = url.searchParams.get('q');
        if (isTerritory && suppliedQuery !== null) required(suppliedQuery.length <= 300, 400, 'invalid_input', 'q admite hasta 300 caracteres.');
        const query = isTerritory ? (suppliedQuery?.trim() ? text(suppliedQuery, 'q', 300) : `personas mayores población acceso internet ${TERRITORIES[territoryId]}`)
          : text(suppliedQuery, 'q', 500);
        if (!openarg.status().configured) {
          const base = { query, datasets: [], sources: [], warnings: ['OpenArg pendiente: configurá OPENARG_API_KEY en el backend. No se muestran datos de reemplazo.'], cached: false, fetchedAt: null, status: 'credentials_required' };
          respond(200, isTerritory ? { ...base, territoryId, indicators: [] } : base); return;
        }
        const scope = `Territorio: ${TERRITORIES[territoryId]}. ${query}`;
        const result = normalizeSearch(await openarg.call('buscar_datasets', { texto: scope, limite: 8 }), query);
        if (isTerritory) {
          const context = await getPublicTerritoryIndicators(openarg, territoryId);
          const sources = [...result.sources, ...context.sources].filter((source, index, all) => all.findIndex((entry) => entry.url === source.url) === index);
          respond(200, { ...result, territoryId, indicators: context.indicators, sources,
            warnings: [...result.warnings, ...context.warnings], cached: result.cached && context.cached,
            evidence: context.evidence, gaps: context.gaps, opportunities: context.opportunities });
        }
        else respond(200, result); return;
      }
      if (method === 'POST' && route === '/openarg/ask') {
        keys(body, ['question', 'territoryId']); const question = text(body.question, 'question', 1600); const territoryId = territory(body.territoryId);
        const scopedQuestion = `Contexto territorial: ${TERRITORIES[territoryId]}. ${question} Indicá período de los datos, fuentes oficiales y límites de cobertura. No infieras indicadores de descripciones.`;
        respond(200, normalizeAnswer(await openarg.call('consultar_datos_publicos', { pregunta: scopedQuestion }))); return;
      }
      if (method === 'GET' && route === '/institution/overview') {
        const territoryId = territory(url.searchParams.get('territoryId') ?? undefined);
        const state = await store.read();
        respond(200, institutionOverview(state, territoryId, current, minimumCohort)); return;
      }

      const state = await store.read();
      const user = token ? state.users.find((u) => same(hash(token), u.tokenHash)) : undefined;
      const group = token ? state.groups.find((g) => same(hash(token), g.tokenHash)) : undefined;
      const needUser = () => required(user, 401, 'unauthorized', 'Token de dispositivo inválido o ausente.');
      const needGroup = () => required(group, 401, 'unauthorized', 'Token familiar inválido o ausente.');
      if (method === 'GET' && route === '/devices/me') {
        needUser(); respond(200, { deviceId: user.id, name: user.name, territoryId: user.territoryId, realtimeApproved: user.realtimeApproved === true, institutionMetricsConsent: user.institutionMetricsConsent === true }); return;
      }
      if (method === 'POST' && route === '/devices/me/activation') {
        needUser(); keys(body, ['enrollmentCode']);
        await store.update(state => admission.activate(state, state.users.find(item => item.id === user.id), body.enrollmentCode));
        respond(200, { ok: true, realtimeApproved: true }); return;
      }
      if (method === 'PATCH' && route === '/devices/me') {
        needUser(); keys(body, ['name', 'territoryId', 'institutionMetricsConsent']);
        const name = text(body.name, 'name', 60, true); const territoryId = body.territoryId === undefined ? undefined : territory(body.territoryId);
        if (body.institutionMetricsConsent !== undefined) required(typeof body.institutionMetricsConsent === 'boolean', 400, 'invalid_consent', 'Elegí explícitamente si autorizás métricas institucionales agregadas.');
        await store.update((s) => { const u = s.users.find((u) => u.id === user.id); if (name) u.name = name; if (territoryId) u.territoryId = territoryId;
          if (body.institutionMetricsConsent !== undefined) { u.institutionMetricsConsent = body.institutionMetricsConsent; u.institutionConsentUpdatedAt = new Date(current).toISOString(); } });
        respond(200, { ok: true }); return;
      }
      if (method === 'POST' && route === '/assistant/sessions') {
        needUser(); respond(201, await store.update(s => createSession(s, user.id, body, current))); return;
      }
      if (method === 'POST' && route === '/assistant/message') {
        needUser(); keys(body, ['sessionId', 'text']); const message = text(body.text, 'text', 1000);
        required(user.realtimeApproved === true, 403, 'device_activation_required', 'Conectá este teléfono con un código vigente del equipo para habilitar la asistencia.');
        if (body.sessionId) required(state.sessions.some((s) => s.id === body.sessionId && s.userId === user.id), 404, 'session_not_found', 'Sesión inexistente.');
        const controller = new AbortController();
        const onClose = () => { if (!res.writableEnded) controller.abort(); };
        res.once('close', onClose);
        try { respond(200, await realtime.reply({ deviceId: user.id, text: message, signal: controller.signal, origin: directOrigin(req.socket.remoteAddress),
          beforeConnect: () => admission.reserve(user.id, req.socket.remoteAddress, 'http') })); }
        finally { res.off('close', onClose); }
        return;
      }
      const eventMatch = /^\/assistant\/sessions\/([a-f0-9-]+)\/events$/.exec(route);
      if (method === 'POST' && eventMatch) {
        needUser(); respond(200, await store.update(s => applySessionEvent(s, user.id, eventMatch[1], body, current))); return;
      }
      if (method === 'GET' && route === '/assistant/history') {
        needUser(); respond(200, { sessions: sessionsFor(state, user.id).map(publicSession).reverse() }); return;
      }
      if (method === 'POST' && route === '/family/link') {
        needUser(); keys(body, ['invitationCode', 'permissions']); const code = text(body.invitationCode, 'invitationCode', 32).toUpperCase();
        keys(body.permissions, ['progress', 'help']);
        required(typeof body.permissions.progress === 'boolean' && typeof body.permissions.help === 'boolean', 400, 'invalid_permissions', 'Elegí permisos explícitos de progreso y ayuda.');
        const permissions = { progress: body.permissions.progress, help: body.permissions.help };
        const result = await store.update((s) => {
          required(!s.relationships.some((r) => r.userId === user.id), 409, 'already_linked', 'El dispositivo ya tiene un grupo. Revocá la vinculación anterior.');
          const invitation = s.invitations.find((i) => same(hash(code), i.codeHash) && !i.usedAt && Date.parse(i.expiresAt) > current);
          required(invitation, 400, 'invalid_invitation', 'El código no existe, ya se usó o venció.');
          invitation.usedAt = new Date(current).toISOString();
          s.relationships.push({ id: randomUUID(), userId: user.id, groupId: invitation.groupId, permissions, createdAt: new Date(current).toISOString() });
          s.consents.push({ id: randomUUID(), userId: user.id, groupId: invitation.groupId, permissions, acceptedAt: new Date(current).toISOString() });
          audit(s, 'family_link_accepted', user.id, current);
          return { group: publicGroup(s.groups.find((g) => g.id === invitation.groupId)), permissions };
        }); respond(200, result); return;
      }
      if (method === 'GET' && route === '/family/me') {
        needUser(); const relationship = state.relationships.find((r) => r.userId === user.id);
        respond(200, { group: relationship ? publicGroup(state.groups.find((g) => g.id === relationship.groupId)) : null, permissions: relationship?.permissions ?? { progress: false, help: false } }); return;
      }
      if (method === 'DELETE' && route === '/family/link') {
        needUser(); await store.update((s) => {
          s.relationships = s.relationships.filter((r) => r.userId !== user.id);
          for (const consent of s.consents) if (consent.userId === user.id && !consent.revokedAt) consent.revokedAt = new Date(current).toISOString();
          for (const alert of s.alerts) if (alert.userId === user.id) alert.revokedAt = new Date(current).toISOString();
          audit(s, 'family_link_revoked', user.id, current);
        }); respond(200, { ok: true }); return;
      }
      if (method === 'POST' && route === '/family/help') {
        needUser(); keys(body, []);
        const alertId = await store.update((s) => {
          const relation = s.relationships.find((r) => r.userId === user.id && r.permissions.help);
          required(relation, 403, 'help_not_consented', 'Vinculá un familiar y autorizá las solicitudes de ayuda.');
          const pending = s.alerts.find((a) => a.userId === user.id && a.groupId === relation.groupId && !a.resolvedAt && !a.revokedAt);
          if (pending) return pending.id;
          const alert = { id: randomUUID(), userId: user.id, groupId: relation.groupId, type: 'help_requested', message: 'Solicitó acompañamiento desde Nieto.', createdAt: new Date(current).toISOString() };
          s.alerts.push(alert); return alert.id;
        }); respond(200, { ok: true, alertId }); return;
      }
      if (method === 'GET' && route === '/family/summary') {
        needGroup(); const relations = state.relationships.filter((r) => r.groupId === group.id);
        const members = relations.map((r) => { const u = state.users.find((u) => u.id === r.userId); return { id: u.id, name: u.name, permissions: r.permissions, ...(r.permissions.progress ? metricsFor(sessionsFor(state, u.id)) : {}) }; });
        const helpIds = new Set(relations.filter((r) => r.permissions.help).map((r) => r.userId));
        const alerts = state.alerts.filter((a) => a.groupId === group.id && !a.revokedAt && helpIds.has(a.userId))
          .map(({ id, type, message, createdAt, resolvedAt, userId }) => ({ id, type, message, createdAt, resolvedAt,
            memberId: userId, requesterName: state.users.find((u) => u.id === userId)?.name }));
        respond(200, { group: publicGroup(group), members, alerts }); return;
      }
      const resolveMatch = /^\/family\/alerts\/([a-f0-9-]+)\/resolve$/.exec(route);
      if (method === 'POST' && resolveMatch) {
        needGroup(); keys(body, []);
        await store.update((s) => {
          const alert = s.alerts.find((a) => a.id === resolveMatch[1] && a.groupId === group.id && !a.revokedAt);
          required(alert, 404, 'alert_not_found', 'Alerta inexistente.'); alert.resolvedAt ??= new Date(current).toISOString();
        }); respond(200, { ok: true }); return;
      }
      if (method === 'POST' && route === '/family/invitations') {
        needGroup(); keys(body, []); respond(201, await store.update((s) => inviteFor(s, group.id, current))); return;
      }
      throw new ApiError(404, 'not_found', 'Ruta inexistente.');
    } catch (error) {
      if (res.headersSent) { res.end(); return; }
      const known = error instanceof ApiError || error instanceof OpenArgError || error instanceof RealtimeError || error instanceof SessionError;
      if (error.retryAfter !== null && error.retryAfter !== undefined) res.setHeader('Retry-After', String(error.retryAfter));
      respond(known ? error.status : 500, { error: { code: known ? error.code : 'internal_error', message: known ? error.message : 'No se pudo completar el pedido.' } });
    }
  };
  handler.store = store; handler.openarg = openarg; handler.realtime = realtime; handler.admission = admission;
  handler.authorizeRealtime = async req => {
    const origin = req.headers.origin;
    if (origin && !corsOrigins.includes(origin)) throw new RealtimeError('origin_not_allowed', 'Origen no permitido.', 403);
    const current = now(); const ip = req.socket.remoteAddress;
    if (realtimeAttempts.size > 1000) for (const [key, value] of realtimeAttempts) if (value.resetAt <= current) realtimeAttempts.delete(key);
    let attempt = realtimeAttempts.get(ip);
    if (!attempt || attempt.resetAt <= current) { attempt = { count: 0, resetAt: current + 60_000 }; realtimeAttempts.set(ip, attempt); }
    if (++attempt.count > 20) throw new RealtimeError('realtime_rate_limit', 'Demasiados intentos de conexión de voz.', 429);
    const token = /^Bearer ([A-Za-z0-9_\-.]+)$/i.exec(req.headers.authorization ?? '')?.[1] ?? '';
    if (!token) throw new RealtimeError('unauthorized', 'Falta el acceso de este teléfono.', 401);
    const state = await store.read();
    const user = state.users.find(user => same(hash(token), user.tokenHash));
    if (!user) throw new RealtimeError('unauthorized', 'El acceso de este teléfono no es válido.', 401);
    if (user.realtimeApproved !== true) throw new RealtimeError('device_activation_required', 'Conectá este teléfono con un código vigente del equipo para habilitar la asistencia.', 403);
    return user.id;
  };
  return handler;
}

export function createServer(options) { const app = createApp(options); return attachRealtimeSocket(http.createServer(app), app); }

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const port = Number(process.env.API_PORT ?? process.env.PORT) || 8787; const host = process.env.HOST ?? '0.0.0.0';
  const app = createApp(); await app.store.init(); const server = attachRealtimeSocket(http.createServer(app), app);
  server.listen(port, host, () => console.log(`NIETO API 2.0 en http://${host}:${port} — OpenArg ${app.openarg.status().configured ? 'configurado, pendiente de consulta' : 'pendiente de OPENARG_API_KEY'}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { server.closeRealtime(); server.close(() => process.exit(0)); });
}
