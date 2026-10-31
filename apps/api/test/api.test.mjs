import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from '../src/server.mjs';
import { OpenArgClient } from '../src/openarg.mjs';
import { JsonStore } from '../src/store.mjs';
import { RealtimeService } from '../src/realtime.mjs';

async function fixture(t, options = {}) {
  const folder = await mkdtemp(join(tmpdir(), 'nieto-test-')); const dataFile = join(folder, 'state.json');
  const server = createServer({ dataFile, institutionToken: 'institution-test', openarg: new OpenArgClient({ apiKey: '' }), realtime: new RealtimeService({ apiKey: '' }), ...options });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise((resolve) => server.close(resolve)); await rm(folder, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = async (path, { token, method = 'GET', body, headers = {} } = {}) => {
    const res = await fetch(`${base}${path.startsWith('/api') || path.startsWith('/downloads') ? path : `/api${path}`}`, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...headers }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: res.status, body: await res.json() };
  };
  const device = async (name = 'Persona') => (await request('/devices', { method: 'POST', body: { name, territoryId: 'AR-C' } })).body;
  return { request, device, dataFile };
}

test('device isolation, closed telemetry, completion idempotence, and no conversation persistence', async (t) => {
  const { request, device, dataFile } = await fixture(t); const a = await device(); const b = await device();
  const session = (await request('/assistant/sessions', { method: 'POST', token: a.token, body: { flowId: 'whatsapp-location' } })).body;
  assert.equal((await request(`/assistant/sessions/${session.id}/events`, { method: 'POST', token: b.token, body: { type: 'flow_completed' } })).status, 404);
  assert.equal((await request(`/assistant/sessions/${session.id}/events`, { method: 'POST', token: a.token, body: { type: 'step_shown', stepId: 'attach', screenshot: 'secret' } })).status, 400);
  for (let n = 0; n < 2; n++) assert.equal((await request(`/assistant/sessions/${session.id}/events`, { method: 'POST', token: a.token, body: { type: 'step_completed', stepId: 'attach' } })).status, 200);
  for (let n = 0; n < 2; n++) assert.equal((await request(`/assistant/sessions/${session.id}/events`, { method: 'POST', token: a.token, body: { type: 'flow_completed', durationSeconds: 30 } })).status, 200);
  assert.equal((await request(`/assistant/sessions/${session.id}/events`, { method: 'POST', token: a.token, body: { type: 'step_shown', stepId: 'open_chat' } })).status, 200);
  assert.equal((await request(`/assistant/sessions/${session.id}/events`, { method: 'POST', token: a.token, body: { type: 'autonomy_completed' } })).status, 200);
  assert.equal((await request(`/assistant/sessions/${session.id}/events`, { method: 'POST', token: a.token, body: { type: 'cancelled' } })).status, 200);
  const history = (await request('/assistant/history', { token: a.token })).body;
  assert.equal(history.sessions.length, 1); assert.equal(history.sessions[0].durationSeconds, 30); assert.equal(history.sessions[0].autonomyCompleted, true); assert.equal(history.sessions[0].state, 'completed');
  assert.deepEqual(history.sessions[0].completedSteps, ['attach']);
  assert.equal((await request('/assistant/history', { token: b.token })).body.sessions.length, 0);
  await request('/assistant/message', { method: 'POST', token: a.token, body: { text: 'texto privado nunca guardar' } });
  const disk = await readFile(dataFile, 'utf8');
  assert.equal(disk.includes('texto privado'), false); assert.equal(disk.includes(a.token), false);
});

test('family invitations are single-use, consent gates metrics/help and revocation removes access', async (t) => {
  const { request, device } = await fixture(t); const a = await device('Ana'); const b = await device();
  const family = (await request('/family/groups', { method: 'POST', body: { name: 'Familia' } })).body;
  const link = { invitationCode: family.invitationCode, permissions: { progress: false, help: true } };
  assert.equal((await request('/family/link', { method: 'POST', token: a.token, body: link })).status, 200);
  assert.equal((await request('/family/link', { method: 'POST', token: b.token, body: link })).status, 400);
  const alert = await request('/family/help', { method: 'POST', token: a.token, body: {} });
  const duplicate = await request('/family/help', { method: 'POST', token: a.token, body: {} });
  assert.equal(alert.body.alertId, duplicate.body.alertId);
  let summary = (await request('/family/summary', { token: family.caregiverToken })).body;
  assert.equal(summary.members[0].name, 'Ana'); assert.equal(summary.members[0].completedFlows, undefined); assert.equal(summary.alerts.length, 1);
  await request('/family/link', { method: 'DELETE', token: a.token });
  summary = (await request('/family/summary', { token: family.caregiverToken })).body;
  assert.deepEqual(summary.members, []); assert.deepEqual(summary.alerts, []);
  assert.equal((await request('/family/help', { method: 'POST', token: a.token, body: {} })).status, 403);
});

test('expired invite cannot link even when code was not used', async (t) => {
  let time = Date.now(); const { request, device } = await fixture(t, { now: () => time }); const a = await device();
  const family = (await request('/family/groups', { method: 'POST', body: { name: 'Familia' } })).body;
  time += 16 * 60 * 1000;
  assert.equal((await request('/family/link', { method: 'POST', token: a.token, body: { invitationCode: family.invitationCode, permissions: { progress: true, help: true } } })).status, 400);
});

test('family alerts identify the consenting requester among multiple members and hide revoked identities', async (t) => {
  const { request, device } = await fixture(t); const ana = await device('Ana'); const juan = await device('Juan');
  const family = (await request('/family/groups', { method: 'POST', body: { name: 'Familia' } })).body;
  const join = async (member, invitationCode) => request('/family/link', { method: 'POST', token: member.token,
    body: { invitationCode, permissions: { progress: false, help: true } } });
  assert.equal((await join(ana, family.invitationCode)).status, 200);
  const invite = (await request('/family/invitations', { method: 'POST', token: family.caregiverToken, body: {} })).body;
  assert.equal((await join(juan, invite.invitationCode)).status, 200);
  const anaHelp = (await request('/family/help', { method: 'POST', token: ana.token, body: {} })).body;
  const juanHelp = (await request('/family/help', { method: 'POST', token: juan.token, body: {} })).body;
  let summary = (await request('/family/summary', { token: family.caregiverToken })).body;
  const anaAlert = summary.alerts.find((a) => a.id === anaHelp.alertId);
  const juanAlert = summary.alerts.find((a) => a.id === juanHelp.alertId);
  assert.equal(anaAlert.memberId, ana.deviceId); assert.equal(anaAlert.requesterName, 'Ana');
  assert.equal(juanAlert.memberId, juan.deviceId); assert.equal(juanAlert.requesterName, 'Juan');
  await request('/family/link', { method: 'DELETE', token: ana.token });
  summary = (await request('/family/summary', { token: family.caregiverToken })).body;
  assert.equal(summary.alerts.length, 1); assert.equal(summary.alerts[0].requesterName, 'Juan');
  assert.equal(JSON.stringify(summary).includes(ana.deviceId), false);
  for (let n = 0; n < 3; n++) await device();
  const overview = (await request('/institution/overview', { token: 'institution-test' })).body;
  assert.equal(overview.suppressed, false);
  assert.equal(JSON.stringify(overview).includes('requesterName'), false);
  assert.equal(JSON.stringify(overview).includes('memberId'), false);
  assert.equal(JSON.stringify(overview).includes(juan.deviceId), false);
});

test('institution minimum cohort suppresses every NIETO breakdown and requires a private token', async (t) => {
  const { request, device } = await fixture(t);
  assert.equal((await request('/institution/overview')).status, 401);
  await device(); let overview = (await request('/institution/overview', { token: 'institution-test' })).body;
  assert.equal(overview.suppressed, true); assert.equal(overview.participants, null); assert.equal(overview.metrics, null); assert.deepEqual(overview.flows, []);
  for (let n = 0; n < 4; n++) await device();
  overview = (await request('/institution/overview', { token: 'institution-test' })).body;
  assert.equal(overview.suppressed, false); assert.equal(overview.participants, 5); assert.equal(overview.metrics.completedFlows, 0);
});

test('missing service credentials produce an explicit empty state, never sample statistics', async (t) => {
  const { request } = await fixture(t);
  const response = await request('/openarg/search?q=mayores', { token: 'institution-test' });
  assert.equal(response.status, 200); assert.deepEqual(response.body.datasets, []); assert.equal(response.body.status, 'credentials_required');
  assert.equal(response.body.fetchedAt, null); assert.ok(response.body.warnings.length);
  assert.equal((await request('/openarg/ask', { method: 'POST', token: 'institution-test', body: { question: 'población', territoryId: 'AR-C' } })).status, 503);
});

test('institution endpoints cannot use a public/default token when env is missing', async (t) => {
  const { request } = await fixture(t, { institutionToken: '' });
  assert.equal((await request('/institution/overview', { token: 'institution-test' })).status, 503);
});

test('CORS rejects foreign websites and request schema rejects arbitrary screen data', async (t) => {
  const { request } = await fixture(t);
  assert.equal((await request('/health', { headers: { Origin: 'https://foreign.example' } })).status, 403);
  assert.equal((await request('/devices', { method: 'POST', body: { name: 'Ana', territoryId: 'AR-C', screenTree: ['anything'] } })).status, 400);
});

test('all configured ISO provinces can register and unknown territories cannot', async (t) => {
  const { request } = await fixture(t);
  assert.equal((await request('/devices', { method: 'POST', body: { name: 'Ana', territoryId: 'AR-X' } })).status, 201);
  assert.equal((await request('/devices', { method: 'POST', body: { name: 'Ana', territoryId: 'AR-INVALID' } })).status, 400);
});

test('territory optional search is bounded and preserves explicit empty states without credentials', async (t) => {
  const { request } = await fixture(t);
  const response = await request('/openarg/territory?territoryId=AR-C&q=conectividad', { token: 'institution-test' });
  assert.equal(response.status, 200); assert.equal(response.body.query, 'conectividad'); assert.deepEqual(response.body.indicators, []);
  assert.equal((await request(`/openarg/territory?q=${'x'.repeat(301)}`, { token: 'institution-test' })).status, 400);
});

test('body size and IP request limits remain enforced with rotating bearer tokens', async (t) => {
  const { request } = await fixture(t, { maxBodyBytes: 150, rateLimit: 3 });
  assert.equal((await request('/devices', { method: 'POST', body: { name: 'a'.repeat(200) } })).status, 413);
  assert.equal((await request('/health', { token: 'one' })).status, 200);
  assert.equal((await request('/health', { token: 'two' })).status, 200);
  assert.equal((await request('/health', { token: 'three' })).status, 429);
});

test('JSON store serializes simultaneous changes without losing users and survives a new instance', async (t) => {
  const { dataFile } = await fixture(t); const store = new JsonStore(dataFile);
  await Promise.all(Array.from({ length: 20 }, (_, i) => store.update((s) => { s.users.push({ id: `parallel-${i}` }); })));
  const restarted = new JsonStore(dataFile); assert.equal((await restarted.read()).users.length, 20);
});
