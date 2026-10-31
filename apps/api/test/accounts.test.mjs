import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from '../src/server.mjs';
import { OpenArgClient } from '../src/openarg.mjs';
import { createAccounts } from '../src/accounts.mjs';
import { JsonStore } from '../src/store.mjs';

async function fixture(t, overrides = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'tino-accounts-')); const file = join(dir, 'state.json');
  const codes = new Map(); let clock = Date.now();
  const server = createServer({ dataFile: file, institutionToken: '', openarg: new OpenArgClient({ apiKey: '' }), now: () => clock,
    accountOptions: { authSecret: 'test-secret-not-a-production-credential', sendCode: ({ email, code }) => codes.set(email, code), institutionEmails: ['institution@example.com'], ...overrides } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await rm(dir, { recursive: true, force: true }); });
  const request = async (path, { method = 'GET', body, token } = {}) => {
    const r = await fetch(`http://127.0.0.1:${server.address().port}/api${path}`, { method, headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    return { status: r.status, body: await r.json() };
  };
  const login = async (email, role = 'user', extra = {}) => {
    const challenge = await request('/auth/code/request', { method: 'POST', body: { email, role, purpose: 'signup', ...extra } }); assert.equal(challenge.status, 200);
    const result = await request('/auth/code/verify', { method: 'POST', body: { challengeId: challenge.body.challengeId, code: codes.get(email) } }); assert.equal(result.status, 200);
    return result.body.sessionToken;
  };
  const onboard = async (token, email, role = 'user', extra = {}) => request('/accounts/onboarding', { token, method: 'POST', body: { role, profile: { name: email.split('@')[0], email, username: email.split('@')[0], phone: '+54 11 1234 5678', territoryId: 'AR-C', organization: 'Institución de prueba' }, preferences: {},
    answers: role === 'user' ? { purpose: 0, interaction: 1, pace: 2, priority: 3 } : role === 'family' ? { relationship: 0, support: 1, frequency: 2, guidance: 3 } : { objective: 0, evidence: 1, work: 2 }, ...extra } });
  const realLogin = async address => {
    const auth = createAccounts({ store: new JsonStore(file), judgesDemo: false, localDemo: false, now: () => clock, sendCode: ({ email, code }) => codes.set(email, code) });
    const challenge = await auth.handle({ route: '/auth/code/request', method: 'POST', body: { email: address, role: 'user', purpose: 'signup' } });
    const result = await auth.handle({ route: '/auth/code/verify', method: 'POST', body: { challengeId: challenge[1].challengeId, code: codes.get(address) } });
    return result[1].sessionToken;
  };
  return { request, login, realLogin, onboard, codes, file, advance: ms => { clock += ms; } };
}

test('codes are single-use, bounded to five attempts, throttled, expired, and never returned or stored in clear text', async t => {
  const f = await fixture(t);
  const address = 'person@example.com';
  const challenge = await f.request('/auth/code/request', { method: 'POST', body: { email: address, role: 'user', purpose: 'signup' } });
  assert.equal(challenge.status, 200); assert.equal(challenge.body.code, undefined);
  assert.equal((await f.request('/auth/code/request', { method: 'POST', body: { email: address, role: 'user', purpose: 'signup' } })).status, 429);
  const code = f.codes.get(address), wrong = code === '000000' ? '111111' : '000000';
  for (let i = 0; i < 5; i++) assert.equal((await f.request('/auth/code/verify', { method: 'POST', body: { challengeId: challenge.body.challengeId, code: wrong } })).status, 400);
  assert.equal((await f.request('/auth/code/verify', { method: 'POST', body: { challengeId: challenge.body.challengeId, code } })).status, 400);
  f.advance(61_000);
  const next = await f.request('/auth/code/request', { method: 'POST', body: { email: address, role: 'user', purpose: 'signup' } });
  const verified = await f.request('/auth/code/verify', { method: 'POST', body: { challengeId: next.body.challengeId, code: f.codes.get(address) } });
  assert.equal(verified.status, 200);
  assert.equal((await f.request('/auth/code/verify', { method: 'POST', body: { challengeId: next.body.challengeId, code: f.codes.get(address) } })).status, 400);
  const disk = JSON.parse(await readFile(f.file, 'utf8'));
  assert.equal(disk.authChallenges.some(c => 'code' in c), false); assert.equal(JSON.stringify(disk).includes(verified.body.sessionToken), false);
  f.advance(61_000);
  const expired = await f.request('/auth/code/request', { method: 'POST', body: { email: address, role: 'user', purpose: 'login' } });
  f.advance(301_000);
  assert.equal((await f.request('/auth/code/verify', { method: 'POST', body: { challengeId: expired.body.challengeId, code: f.codes.get(address) } })).status, 400);
});

test('verified identity owns the profile, assistant data and revocable session, without granting institution access', async t => {
  const f = await fixture(t), token = await f.login('person@example.com');
  assert.equal((await f.onboard(token, 'someone@example.com')).status, 403);
  assert.equal((await f.onboard(token, 'person@example.com', 'institution')).status, 403);
  const profile = await f.onboard(token, 'person@example.com'); assert.equal(profile.status, 200); assert.equal(profile.body.deviceToken, token);
  const message = await f.request('/assistant/message', { token, method: 'POST', body: { text: 'quiero compartir mi ubicación' } }); assert.equal(message.status, 200); assert.equal(message.body.flowId, 'whatsapp-location');
  assert.notEqual((await f.request('/institution/analytics', { token })).status, 200);
  assert.equal((await f.request('/accounts/session', { method: 'DELETE', token })).status, 200);
  assert.equal((await f.request('/assistant/history', { token })).status, 401);
});

test('family account creation, invitation consent, help and group-join approval work across real API boundaries', async t => {
  const f = await fixture(t), admin = await f.login('admin@example.com', 'family');
  const group = await f.onboard(admin, 'admin@example.com', 'family', { familyMode: 'create', targetIdentifier: 'person@example.com' });
  assert.equal(group.status, 200); assert.ok(group.body.invitationCode);
  const person = await f.login('person@example.com'); await f.onboard(person, 'person@example.com');
  assert.equal((await f.request('/family/link', { token: person, method: 'POST', body: { invitationCode: group.body.invitationCode, permissions: { progress: false, help: true } } })).status, 200);
  assert.equal((await f.request('/family/help', { token: person, method: 'POST', body: {} })).status, 200);
  let summary = await f.request('/family/summary', { token: admin }); assert.equal(summary.status, 200); assert.equal(summary.body.alerts.length, 1); assert.equal(summary.body.members[0].completedFlows, undefined);
  const joiner = await f.login('joiner@example.com', 'family', { purpose: 'family_join', adminEmail: 'admin@example.com' });
  const joined = await f.onboard(joiner, 'joiner@example.com', 'family', { familyMode: 'join' }); assert.equal(joined.body.accessStatus, 'group_approval_required');
  assert.equal((await f.request('/family/summary', { token: joiner })).status, 401);
  summary = await f.request('/family/summary', { token: admin }); const requestId = summary.body.joinRequests[0].id;
  assert.equal((await f.request(`/accounts/family/requests/${requestId}/approve`, { token: person, method: 'POST', body: {} })).status, 403);
  assert.equal((await f.request(`/accounts/family/requests/${requestId}/approve`, { token: admin, method: 'POST', body: {} })).status, 200);
  assert.equal((await f.request('/family/summary', { token: joiner })).status, 200);
  assert.equal((await f.request('/family/link', { token: person, method: 'DELETE' })).status, 200);
  summary = await f.request('/family/summary', { token: admin }); assert.equal(summary.body.members.length, 0); assert.equal(summary.body.alerts.length, 0);
});

test('institution access requires a server-side email allowlist and preserves cohort suppression', async t => {
  const f = await fixture(t), denied = await f.login('not-approved@example.com', 'institution');
  await f.onboard(denied, 'not-approved@example.com', 'institution');
  assert.notEqual((await f.request('/institution/analytics', { token: denied })).status, 200);
  const allowed = await f.login('institution@example.com', 'institution'); await f.onboard(allowed, 'institution@example.com', 'institution');
  const data = await f.request('/institution/analytics', { token: allowed }); assert.equal(data.status, 200); assert.equal(data.body.suppressed, true); assert.equal(data.body.participants, null);
});

test('failed delivery never creates a usable code; unconfigured email never claims success', async t => {
  const f = await fixture(t, { sendCode: async () => { throw new Error('provider offline'); } });
  const result = await f.request('/auth/code/request', { method: 'POST', body: { email: 'person@example.com', role: 'user', purpose: 'signup' } });
  assert.equal(result.status, 502); assert.equal(JSON.parse(await readFile(f.file, 'utf8')).authChallenges.length, 0);
  const unavailable = await fixture(t, { sendCode: null, localDemo: false, resendKey: '', mailFrom: '' });
  assert.equal((await unavailable.request('/auth/code/request', { method: 'POST', body: { email: 'person@example.com', role: 'user', purpose: 'signup' } })).status, 503);
});


test('username suggestions avoid existing accounts across roles and ownership is case-insensitive', async t => {
  const f = await fixture(t), first = await f.login('first@example.com');
  const profile = { name: 'José Pérez', email: 'first@example.com', username: 'Jose.Perez', phone: '+54 (11) 1234-5678', territoryId: 'AR-C' };
  const created = await f.onboard(first, profile.email, 'user', { profile });
  assert.equal(created.status, 200); assert.equal(created.body.profile.username, 'jose.perez'); assert.equal(created.body.profile.phone, '+541112345678');
  const check = username => f.request('/accounts/username/check', { method: 'POST', body: { username } });
  assert.equal((await check('JOSE.PEREZ')).body.available, false);
  const own = await f.request('/accounts/username/check', { method: 'POST', token: first, body: { username: 'JOSE.PEREZ' } });
  assert.equal(own.body.available, true);
  const suggestion = await f.request('/accounts/username/suggest', { method: 'POST', body: { name: 'José Pérez' } });
  assert.equal(suggestion.body.username, 'jose.perez2'); assert.equal(suggestion.body.available, true);
  const second = await f.login('second@example.com', 'family');
  const duplicate = await f.onboard(second, 'second@example.com', 'family', { profile: { ...profile, email: 'second@example.com', username: 'JOSE.PEREZ' } });
  assert.equal(duplicate.status, 409); assert.equal(duplicate.body.error.code, 'username_unavailable');
  assert.equal((await f.onboard(first, profile.email, 'user', { profile })).status, 200);
});

test('phone and username are mandatory and invalid profile data is never persisted', async t => {
  const f = await fixture(t), token = await f.login('valid@example.com');
  const profile = { name: 'Test Person', email: 'valid@example.com', username: 'test.person', phone: '+54 11 1234 5678', territoryId: 'AR-C' };
  for (const phone of [undefined, '', '123', '+1234567890123456', 'call 12345678', 12345678]) {
    const result = await f.onboard(token, profile.email, 'user', { profile: { ...profile, phone } });
    assert.equal(result.status, 400); assert.equal(result.body.error.code, 'invalid_phone');
  }
  for (const username of [undefined, '', 'ab', 'a'.repeat(31), 'con espacios', 'nombre@usuario']) {
    const result = await f.onboard(token, profile.email, 'user', { profile: { ...profile, username } });
    assert.equal(result.status, 400); assert.equal(result.body.error.code, 'invalid_username');
  }
  assert.equal((await f.request('/accounts/me', { token })).body.onboardingRequired, true);
  assert.equal((await f.onboard(token, profile.email, 'user', { profile })).status, 200);
});

test('two simultaneous signups cannot save the same username', async t => {
  const f = await fixture(t), one = await f.login('one@example.com'), two = await f.login('two@example.com');
  const result = await Promise.all([[one, 'one@example.com'], [two, 'two@example.com']].map(([token, email]) =>
    f.onboard(token, email, 'user', { profile: { name: 'Same name', email, username: 'same.name', phone: '+541112345678', territoryId: 'AR-C' } })));
  assert.deepEqual(result.map(r => r.status).sort(), [200, 409]);
  const stored = JSON.parse(await readFile(f.file, 'utf8'));
  assert.equal(stored.accounts.filter(a => a.profile?.username === 'same.name').length, 1);
});



test('judges log in with test@labitconf.com / 0000 without email or onboarding', async t => {
  let deliveries = 0;
  const f = await fixture(t, { judgesDemo: true, demoEmail: 'test@labitconf.com', sendCode: () => { deliveries++; } });
  const login = (password = '0000', email = 'test@labitconf.com') => f.request('/auth/demo/login', { method: 'POST', body: { email, password } });
  assert.equal((await login('1111')).status, 401);
  assert.equal((await login('0000', 'other@example.com')).status, 401);
  const before = await readFile(f.file, 'utf8').catch(error => { if (error.code === 'ENOENT') return '{}'; throw error; });
  assert.equal(JSON.parse(before).accounts?.length ?? 0, 0);
  const logins = await Promise.all([login(), login('0000', 'TEST@LABITCONF.COM')]);
  for (const result of logins) {
    assert.equal(result.status, 200); assert.equal(result.body.demo, true); assert.equal(result.body.onboardingRequired, false); assert.ok(result.body.deviceToken);
    const me = await f.request('/accounts/me', { token: result.body.sessionToken });
    assert.equal(me.body.profile.username, 'labitconf.demo'); assert.equal(me.body.profile.email, 'test@labitconf.com');
    const message = await f.request('/assistant/message', { token: result.body.sessionToken, method: 'POST', body: { text: 'quiero compartir mi ubicación' } });
    assert.equal(message.status, 200); assert.equal(message.body.flowId, 'whatsapp-location');
    assert.notEqual((await f.request('/institution/analytics', { token: result.body.sessionToken })).status, 200);
  }
  assert.equal(deliveries, 0);
  const disk = JSON.parse(await readFile(f.file, 'utf8'));
  assert.equal(disk.accounts.filter(a => a.demo).length, 1); assert.equal(disk.users.filter(u => u.demo).length, 1);
  assert.equal(disk.authChallenges.length, 0); assert.equal(disk.accounts.some(a => 'password' in a), false);
  const disabled = createAccounts({ store: new JsonStore(f.file), judgesDemo: false });
  assert.equal(await disabled.principal(logins[0].body.sessionToken), null);
  assert.equal((await f.request('/accounts/session', { method: 'DELETE', token: logins[0].body.sessionToken })).status, 200);
  assert.equal((await f.request('/assistant/history', { token: logins[0].body.sessionToken })).status, 401);
  assert.equal((await f.request('/assistant/history', { token: logins[1].body.sessionToken })).status, 200);
});

test('demo password is opt-in and never authenticates the real account with the same email or grants another role', async t => {
  const off = await fixture(t, { judgesDemo: false });
  const body = { email: 'test@labitconf.com', password: '0000' };
  assert.equal((await off.request('/auth/demo/login', { method: 'POST', body })).status, 404);
  const f = await fixture(t, { judgesDemo: true, demoEmail: 'test@labitconf.com' });
  const real = await f.realLogin('test@labitconf.com'); await f.onboard(real, 'test@labitconf.com');
  for (const role of ['institution', 'family']) assert.equal((await f.request('/auth/demo/login', { method: 'POST', body: { ...body, role } })).status, 400);
  const demo = await f.request('/auth/demo/login', { method: 'POST', body });
  const realMe = await f.request('/accounts/me', { token: real });
  assert.equal(demo.status, 200); assert.notEqual(demo.body.deviceId, realMe.body.deviceId); assert.equal(realMe.body.demo, false);
  f.advance(61_000);
  const realAgain = await f.realLogin('test@labitconf.com');
  assert.equal((await f.request('/accounts/me', { token: realAgain })).body.deviceId, realMe.body.deviceId);
});


test('email verification in the demo advances without mail setup and accepts exactly 000000', async t => {
  let mailSent = 0;
  const f = await fixture(t, { judgesDemo: true, demoEmail: 'test@labitconf.com', sendCode: () => { mailSent++; }, institutionEmails: ['institution@example.com'] });
  const request = (email = 'newperson@example.com', role = 'user') => f.request('/auth/code/request', { method: 'POST', body: { email, role, purpose: 'signup' } });
  const challenges = await Promise.all([request(), request()]);
  for (const c of challenges) { assert.equal(c.status, 200); assert.equal(c.body.delivery, 'demo'); assert.equal(c.body.retryAfterSeconds, 0); }
  const verify = (challenge, code) => f.request('/auth/code/verify', { method: 'POST', body: { challengeId: challenge.body.challengeId, code } });
  assert.equal((await verify(challenges[0], '0000060')).status, 400);
  assert.equal((await verify(challenges[0], '111111')).status, 400);
  const sessions = await Promise.all(challenges.map(c => verify(c, '000000')));
  for (const result of sessions) { assert.equal(result.status, 200); assert.equal(result.body.demo, true); assert.equal(result.body.onboardingRequired, true); }
  assert.equal((await verify(challenges[0], '000000')).status, 400);
  assert.equal((await f.onboard(sessions[0].body.sessionToken, 'newperson@example.com')).status, 200);
  assert.equal((await f.request('/assistant/history', { token: sessions[0].body.sessionToken })).status, 200);
  const institutional = await request('institution@example.com', 'institution');
  const institutionalSession = await verify(institutional, '000000');
  assert.equal(institutionalSession.status, 200); assert.equal(institutionalSession.body.institutionToken, undefined);
  assert.notEqual((await f.request('/institution/analytics', { token: institutionalSession.body.sessionToken })).status, 200);
  assert.equal(mailSent, 0);
  const disabled = createAccounts({ store: new JsonStore(f.file), judgesDemo: false });
  assert.equal(await disabled.principal(sessions[0].body.sessionToken), null);
});


test('Ya tengo cuenta with the demo code opens an onboarded assistant account directly', async t => {
  const f = await fixture(t, { judgesDemo: true, demoEmail: 'test@labitconf.com', sendCode: null, localDemo: false, resendKey: '', mailFrom: '' });
  const address = 'demo-chat@example.com';
  const request = purpose => f.request('/auth/code/request', { method: 'POST', body: { email: address, role: 'user', purpose } });
  const verify = c => f.request('/auth/code/verify', { method: 'POST', body: { challengeId: c.body.challengeId, code: '000000' } });
  const unfinished = await verify(await request('signup')); assert.equal(unfinished.body.onboardingRequired, true);
  const login = await verify(await request('login'));
  assert.equal(login.status, 200); assert.equal(login.body.onboardingRequired, false); assert.ok(login.body.deviceToken); assert.ok(login.body.profile.username);
  const me = await f.request('/accounts/me', { token: login.body.sessionToken }); assert.equal(me.body.onboardingRequired, false);
  const message = await f.request('/assistant/message', { token: login.body.sessionToken, method: 'POST', body: { text: 'quiero compartir mi ubicación' } });
  assert.equal(message.status, 200); assert.equal(message.body.flowId, 'whatsapp-location');
});

test('verified accounts switch spaces only within their identity and retain independent role authorization', async t => {
  const f = await fixture(t);
  const source = await f.login('person@example.com'); await f.onboard(source, 'person@example.com');
  assert.equal((await f.request('/accounts/space', { method: 'POST', body: { role: 'institution' } })).status, 401);
  assert.equal((await f.request('/accounts/space', { token: source, method: 'POST', body: { role: 'admin' } })).status, 400);
  assert.equal((await f.request('/accounts/space', { token: source, method: 'POST', body: { role: 'institution', email: 'institution@example.com' } })).status, 400);
  const switched = await f.request('/accounts/space', { token: source, method: 'POST', body: { role: 'institution' } });
  assert.equal(switched.status, 200); assert.equal(switched.body.profile.email, 'person@example.com');
  assert.equal(switched.body.onboardingRequired, true); assert.equal(switched.body.institutionToken, undefined);
  assert.equal(switched.body.deviceToken, undefined); assert.equal(switched.body.caregiverToken, undefined);
  assert.equal((await f.request('/accounts/me', { token: source })).status, 401);
  assert.notEqual((await f.request('/institution/analytics', { token: switched.body.sessionToken })).status, 200);
  const back = await f.request('/accounts/space', { token: switched.body.sessionToken, method: 'POST', body: { role: 'user' } });
  assert.equal(back.status, 200); assert.equal(back.body.onboardingRequired, false); assert.equal(back.body.profile.username, 'person');
  assert.ok(back.body.deviceToken); assert.equal((await f.request('/assistant/history', { token: back.body.sessionToken })).status, 200);
});

test('demo navigation opens chat, family and institution without repeating onboarding or granting real data', async t => {
  const f = await fixture(t, { judgesDemo: true });
  const auth = await f.request('/auth/demo/login', { method: 'POST', body: { email: 'test@labitconf.com', password: '0000' } });
  let token = auth.body.sessionToken;
  let familyId;
  for (const role of ['family', 'institution', 'user', 'family']) {
    const result = await f.request('/accounts/space', { token, method: 'POST', body: { role } });
    assert.equal(result.status, 200); assert.equal(result.body.role, role); assert.equal(result.body.demo, true); assert.equal(result.body.onboardingRequired, false);
    assert.equal((await f.request('/accounts/me', { token })).status, 401);
    token = result.body.sessionToken;
    const endpoint = role === 'user' ? '/assistant/history' : `/demo/${role}/dashboard`;
    const data = await f.request(endpoint, { token }); assert.equal(data.status, 200);
    if (role === 'family') { if (familyId) assert.equal(data.body.group.id, familyId); familyId = data.body.group.id; }
    if (role === 'institution') { assert.equal(result.body.institutionToken, undefined); assert.notEqual((await f.request('/institution/analytics', { token })).status, 200); }
  }
  const disabled = createAccounts({ store: new JsonStore(f.file), judgesDemo: false });
  await assert.rejects(() => disabled.handle({ route: '/accounts/space', method: 'POST', body: { role: 'user' }, bearer: token }), e => e.status === 401);
});
