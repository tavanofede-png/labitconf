import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from '../src/server.mjs';
import { JsonStore } from '../src/store.mjs';
import { OpenArgClient } from '../src/openarg.mjs';

const NOW = Date.parse('2026-10-30T12:00:00Z');
const TOKEN = 'institution-isolated-test';
async function fixture(t) {
  const folder = await mkdtemp(join(tmpdir(), 'tino-institution-'));
  const store = new JsonStore(join(folder, 'test.json'));
  const server = createServer({ store, institutionToken: TOKEN, now: () => NOW, rateLimit: 2000, openarg: new OpenArgClient({ apiKey: '' }) });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await rm(folder, { recursive: true, force: true }); });
  const request = async (path, { token = TOKEN, method = 'GET', body } = {}) => {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/api${path}`, { method,
      headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    return { status: response.status, data: await response.json() };
  };
  const devices = [];
  for (let i = 0; i < 5; i++) devices.push((await request('/devices', { method: 'POST', body: { name: `Private person ${i}`, territoryId: 'AR-C' } })).data);
  return { request, store, devices };
}
async function session(request, device, autonomy = true) {
  const result = await request('/assistant/sessions', { token: device.token, method: 'POST', body: { flowId: 'whatsapp-location' } });
  for (const event of [{ type: 'flow_started' }, { type: 'step_completed', stepId: 'attach' }, { type: 'flow_completed', durationSeconds: 120 }, ...(autonomy ? [{ type: 'autonomy_completed' }] : [])]) {
    assert.equal((await request(`/assistant/sessions/${result.data.id}/events`, { token: device.token, method: 'POST', body: event })).status, 200);
  }
}

test('new institution routes require credentials, validate dates and periods, and use active rather than registered cohorts', async t => {
  const { request, devices } = await fixture(t);
  for (const path of ['/institution/analytics', '/institution/territories', '/institution/programs']) assert.equal((await request(path, { token: 'bad' })).status, 401);
  assert.equal((await request('/institution/analytics?days=1')).status, 400);
  assert.equal((await request('/institution/analytics?territoryId=unknown')).status, 400);
  let result = (await request('/institution/analytics')).data;
  assert.equal(result.suppressed, true); assert.equal(result.participants, null); assert.equal(result.metrics, null);
  for (let i = 0; i < devices.length; i++) await session(request, devices[i], i < 3);
  result = (await request('/institution/analytics')).data;
  assert.equal(result.suppressed, false); assert.equal(result.participants, 5);
  assert.equal(result.metrics.completedFlows, 5); assert.equal(result.metrics.autonomyRate, 60);
  assert.equal(result.metrics.accompaniedMinutes, 10); assert.equal(result.metrics.completionRate, 100);
  assert.equal(result.usage, null); assert.equal(result.safety, null);
  assert.equal(result.trend.length, 1); assert.equal(result.flows[0].steps[0].completed, 5);
  const serialized = JSON.stringify(result);
  assert.equal(serialized.includes('Private person'), false);
  for (const device of devices) { assert.equal(serialized.includes(device.deviceId), false); assert.equal(serialized.includes(device.token), false); }
});

test('sparse distribution and out-of-period sessions remain suppressed; territory counts never leak identities', async t => {
  const { request, devices, store } = await fixture(t);
  for (const device of devices) await session(request, device);
  await store.update(s => { s.sessions[0].createdAt = '2026-10-01T10:00:00Z'; });
  let result = (await request('/institution/analytics?days=30')).data;
  assert.equal(result.suppressed, false); assert.deepEqual(result.trend, []);
  result = (await request('/institution/analytics?days=7')).data;
  assert.equal(result.suppressed, true); assert.equal(result.metrics, null);
  const map = (await request('/institution/territories?days=30')).data;
  assert.equal(map.territories.length, 24);
  assert.equal(map.territories.find(row => row.territoryId === 'AR-C').participants, 5);
  assert.ok(map.territories.filter(row => row.territoryId !== 'AR-C').every(row => row.participants === null));
});

test('usage is consent-gated, idempotent and non-overlapping; unaccompanied time is independent of autonomy checks', async t => {
  const { request, devices } = await fixture(t);
  const interval = { id: 'interval-001', category: 'communication', startedAt: '2026-10-30T10:00:00Z', endedAt: '2026-10-30T10:10:00Z', accompaniedSeconds: 120 };
  const send = (device, body) => request('/usage/batch', { token: device.token, method: 'POST', body });
  assert.equal((await send(devices[0], { consent: false, intervals: [interval] })).status, 400);
  assert.equal((await send(devices[0], { consent: true, intervals: [{ ...interval, url: 'https://private.example' }] })).status, 400);
  for (const device of devices) {
    assert.equal((await send(device, { consent: true, intervals: [interval] })).status, 200);
    assert.equal((await send(device, { consent: true, intervals: [interval] })).status, 200);
  }
  assert.equal((await send(devices[0], { consent: true, intervals: [{ ...interval, id: 'interval-002' }] })).status, 409);
  assert.equal((await send(devices[0], { consent: true, intervals: [{ ...interval, category: 'learning' }] })).status, 409);
  const result = (await request('/institution/analytics')).data;
  assert.equal(result.usage.screenMinutes, 50); assert.equal(result.usage.accompaniedMinutes, 10); assert.equal(result.usage.unaccompaniedMinutes, 40);
  assert.equal(result.metrics, null); assert.equal(result.usage.categories[0].minutes, 50);
  assert.equal((await send(devices[0], { consent: true, intervals: [{ ...interval, id: 'interval-003', accompaniedSeconds: 700 }] })).status, 400);
});

test('safety events are scoped to a device and contain only closed categories', async t => {
  const { request, devices } = await fixture(t);
  const event = { id: 'safety-event-1', kind: 'suspicious_link', category: 'browser' };
  for (const device of devices) {
    assert.equal((await request('/safety/events', { token: device.token, method: 'POST', body: event })).status, 200);
    assert.equal((await request('/safety/events', { token: device.token, method: 'POST', body: event })).status, 200);
  }
  assert.equal((await request('/safety/events', { token: devices[0].token, method: 'POST', body: { ...event, url: 'private' } })).status, 400);
  assert.equal((await request('/safety/events/safety-event-1', { token: TOKEN, method: 'PATCH', body: { resolved: true } })).status, 401);
  assert.equal((await request('/safety/events/safety-event-1', { token: devices[0].token, method: 'PATCH', body: { resolved: true } })).status, 200);
  const result = (await request('/institution/analytics')).data;
  assert.equal(result.safety.total, 5); assert.equal(result.safety.resolved, 1); assert.equal(result.safety.pending, 4);
  assert.equal(JSON.stringify(result).includes('safety-event-1'), false);
});

test('programs persist, validate dates, compare equal windows and suppress small baselines', async t => {
  const { request, store, devices } = await fixture(t);
  const body = { name: 'Taller accesible', territoryId: 'AR-C', startDate: '2026-10-25', endDate: '2026-10-29', goal: 70 };
  assert.equal((await request('/institution/programs', { method: 'POST', body: { ...body, startDate: '2026-02-30' } })).status, 400);
  assert.equal((await request('/institution/programs', { method: 'POST', body: { ...body, goal: 101 } })).status, 400);
  const created = await request('/institution/programs', { method: 'POST', body });
  assert.equal(created.status, 201);
  await request(`/institution/programs/${created.data.program.id}`, { method: 'PATCH', body: { status: 'active' } });
  let programs = (await request('/institution/programs')).data.programs;
  assert.equal(programs[0].status, 'active'); assert.equal(programs[0].evaluation.status, 'insufficient_cohort');
  for (const device of devices) await session(request, device);
  await store.update(s => {
    const original = s.sessions.map(session => ({ ...session }));
    s.sessions.forEach(session => { session.createdAt = '2026-10-27T12:00:00Z'; });
    s.sessions.push(...original.map((session, index) => ({ ...session, id: `baseline-${index}`, createdAt: '2026-10-22T12:00:00Z', autonomyCompleted: index < 2 })));
  });
  programs = (await request('/institution/programs')).data.programs;
  const evaluation = programs[0].evaluation;
  assert.equal(evaluation.status, 'available'); assert.equal(evaluation.before.metrics.autonomyRate, 40);
  assert.equal(evaluation.after.metrics.autonomyRate, 100); assert.equal(evaluation.changePoints, 60);
  assert.equal(Date.parse(evaluation.before.period.to) - Date.parse(evaluation.before.period.from), Date.parse(evaluation.after.period.to) - Date.parse(evaluation.after.period.from));
  assert.equal((await request('/institution/programs?territoryId=AR-X')).data.programs.length, 0);
});
