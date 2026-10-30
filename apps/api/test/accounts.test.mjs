import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from '../src/server.mjs';
import { OpenArgClient } from '../src/openarg.mjs';

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
  const onboard = async (token, email, role = 'user', extra = {}) => request('/accounts/onboarding', { token, method: 'POST', body: { role, profile: { name: email.split('@')[0], email, territoryId: 'AR-C', organization: 'Institución de prueba' }, preferences: {},
    answers: role === 'user' ? { purpose: 0, interaction: 1, pace: 2, priority: 3 } : role === 'family' ? { relationship: 0, support: 1, frequency: 2, guidance: 3 } : { objective: 0, evidence: 1, work: 2 }, ...extra } });
  return { request, login, onboard, codes, file, advance: ms => { clock += ms; } };
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
