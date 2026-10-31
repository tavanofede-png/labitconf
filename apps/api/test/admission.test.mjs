import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter, once } from 'node:events';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import WebSocket from 'ws';
import { createServer } from '../src/server.mjs';
import { JsonStore } from '../src/store.mjs';
import { RealtimeService } from '../src/realtime.mjs';
import { RealtimeAdmission } from '../src/admission.mjs';
import { OpenArgClient } from '../src/openarg.mjs';

class LocalConversation extends EventEmitter {
  closed = false;
  async connect() { if (!this.closed) this.emit('event', { type: 'ready' }); }
  handleClient() {}
  close() { if (!this.closed) { this.closed = true; this.emit('closed'); } }
  fail(error) { this.emit('event', { type: 'error', code: error.code, message: error.message, fatal: true }); this.close(); }
}

async function fixture(t, options = {}) {
  const folder = await mkdtemp(join(tmpdir(), 'nieto-admission-')); const dataFile = join(folder, 'data.json');
  let providerOpens = 0;
  const realtime = new RealtimeService({ apiKey: 'synthetic-test-key', conversationFactory: () => { const conversation = new LocalConversation(); const connect = conversation.connect.bind(conversation); conversation.connect = () => { providerOpens++; return connect(); }; return conversation; } });
  const server = createServer({ dataFile, institutionToken: 'operator-test', realtime, openarg: new OpenArgClient({ apiKey: '' }), ...options });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeRealtime(); await new Promise(resolve => server.close(resolve)); await rm(folder, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = async (path, { body, token, method = body ? 'POST' : 'GET' } = {}) => {
    const response = await fetch(base + '/api' + path, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    return { status: response.status, body: await response.json() };
  };
  const code = async () => (await request('/institution/device-invitations', { token: 'operator-test', body: {} })).body.enrollmentCode;
  const device = async () => (await request('/devices', { body: { name: 'Private name', territoryId: 'AR-C', enrollmentCode: await code() } })).body;
  const socket = async device => {
    const client = new WebSocket(base.replace('http:', 'ws:') + '/api/assistant/realtime', { headers: { Authorization: `Bearer ${device.token}` } }); client.on('error', () => {});
    const event = await new Promise((resolve, reject) => { const timer = setTimeout(() => reject(new Error('No realtime status')), 2000); client.once('message', raw => { clearTimeout(timer); resolve(JSON.parse(raw)); }); });
    return { client, event };
  };
  return { request, code, device, socket, realtime, dataFile, store: server.listeners('request')[0].store, providerOpens: () => providerOpens };
}

test('nine public registrations cannot create identities or open paid upstreams without operator admission', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 9; i++) assert.equal((await f.request('/devices', { body: { name: 'Unapproved', territoryId: 'AR-C' } })).status, 403);
  assert.equal((await f.store.read()).users.length, 0); assert.equal(f.providerOpens(), 0);
  assert.equal((await f.request('/institution/device-invitations', { body: {} })).status, 401);
});

test('enrollment is one use, expires, and concurrent redemption creates exactly one approved device', async t => {
  let current = Date.now(); const f = await fixture(t, { now: () => current });
  const enrollmentCode = await f.code();
  const results = await Promise.all([1, 2].map(() => f.request('/devices', { body: { name: 'Test', territoryId: 'AR-C', enrollmentCode } })));
  assert.deepEqual(results.map(result => result.status).sort(), [201, 403]);
  const good = results.find(result => result.status === 201).body;
  assert.equal((await f.request('/devices/me', { token: good.token })).body.realtimeApproved, true);
  const expired = await f.code(); current += 15 * 60_000 + 1;
  assert.equal((await f.request('/devices', { body: { name: 'Expired', enrollmentCode: expired } })).status, 403);
  assert.equal((await f.store.read()).users.length, 1);
  assert.equal((await readFile(f.dataFile, 'utf8')).includes(enrollmentCode), false);
});

test('legacy tokens retain local account access but cannot use AI until single-use activation', async t => {
  const f = await fixture(t); const token = 'legacy-test-token';
  await f.store.update(state => state.users.push({ id: 'legacy-device', name: 'Legacy', territoryId: 'AR-C', tokenHash: createHash('sha256').update(token).digest('hex') }));
  assert.equal((await f.request('/devices/me', { token })).body.realtimeApproved, false);
  assert.equal((await f.request('/family/me', { token })).status, 200);
  const blocked = await f.request('/assistant/message', { token, body: { text: 'private query' } });
  assert.equal(blocked.status, 403); assert.equal(blocked.body.error.code, 'device_activation_required'); assert.equal(f.providerOpens(), 0);
  assert.equal((await f.request('/devices/me/activation', { token, body: { enrollmentCode: await f.code() } })).status, 200);
  assert.equal((await f.request('/devices/me', { token })).body.realtimeApproved, true);
  const { client, event } = await f.socket({ token }); assert.equal(event.type, 'ready'); client.close();
});

test('approved identities from one direct origin cannot occupy all slots across HTTP and WS; revocation closes active access', async t => {
  const f = await fixture(t); const devices = [];
  for (let i = 0; i < 9; i++) devices.push(await f.device());
  const first = await f.socket(devices[0]); const second = await f.socket(devices[1]);
  const third = await f.socket(devices[2]); assert.equal(third.event.code, 'realtime_origin_busy');
  assert.equal(f.realtime.active.size, 2); assert.equal(f.providerOpens(), 2);
  const ninth = await f.request('/assistant/message', { token: devices[8].token, body: { text: 'blocked' } });
  assert.equal(ninth.status, 429); assert.equal(ninth.body.error.code, 'realtime_origin_busy');
  const closing = once(first.client, 'close');
  assert.equal((await f.request(`/institution/devices/${devices[0].deviceId}/revoke`, { token: 'operator-test', body: {} })).status, 200);
  await closing; assert.equal(f.realtime.active.has(devices[0].deviceId), false);
  const forbidden = await f.request('/assistant/message', { token: devices[0].token, body: { text: 'denied' } });
  assert.equal(forbidden.status, 403); assert.equal(f.providerOpens(), 2);
  const list = (await f.request('/institution/devices', { token: 'operator-test' })).body;
  assert.equal(list.devices.length, 9); assert.equal(JSON.stringify(list).includes('Private name'), false); assert.equal(JSON.stringify(list).includes(devices[0].token), false);
  second.client.close(); third.client.close();
});

test('resource reservations persist across restart, share normalized origin limits and reset only at next UTC day', async t => {
  const folder = await mkdtemp(join(tmpdir(), 'nieto-budget-')); t.after(() => rm(folder, { recursive: true, force: true }));
  const file = join(folder, 'data.json'); let now = Date.UTC(2026, 9, 31, 12);
  const limits = { device: { turns: 20, seconds: 600, contexts: 60 }, origin: { turns: 20, seconds: 600, contexts: 60 }, global: { turns: 40, seconds: 1200, contexts: 120 } };
  const store = new JsonStore(file); await store.update(state => state.users.push({ id: 'one', realtimeApproved: true }, { id: 'two', realtimeApproved: true }));
  const admission = new RealtimeAdmission(store, { now: () => now, limits });
  await admission.reserve('one', '::ffff:127.0.0.1', 'ws');
  const restarted = new RealtimeAdmission(new JsonStore(file), { now: () => now, limits });
  await assert.rejects(restarted.reserve('one', 'elsewhere', 'http'), error => error.code === 'realtime_daily_budget');
  await assert.rejects(restarted.reserve('two', '127.0.0.1', 'http'), error => error.code === 'realtime_daily_budget');
  assert.equal((await store.read()).realtimeUsage.global.turns, 20);
  now += 24 * 60 * 60_000; await restarted.reserve('one', '127.0.0.1', 'http');
  const saved = await store.read(); assert.equal(saved.realtimeUsage.global.turns, 1);
  assert.equal(JSON.stringify(saved.realtimeUsage).includes('127.0.0.1'), false);
});

test('global daily reservation cap remains enforced across origins and process restart without partially charging rejected reservations', async t => {
  const folder = await mkdtemp(join(tmpdir(), 'nieto-global-budget-')); t.after(() => rm(folder, { recursive: true, force: true }));
  const file = join(folder, 'data.json'); const store = new JsonStore(file);
  await store.update(state => state.users.push({ id: 'one', realtimeApproved: true }, { id: 'two', realtimeApproved: true }));
  const options = { limits: { global: { turns: 1, seconds: 100, contexts: 1 } } };
  await new RealtimeAdmission(store, options).reserve('one', 'first-origin', 'http');
  const restarted = new RealtimeAdmission(new JsonStore(file), options);
  await assert.rejects(restarted.reserve('two', 'second-origin', 'http'), error => error.code === 'realtime_daily_budget');
  const state = await store.read(); assert.equal(state.realtimeUsage.devices.two, undefined); assert.equal(state.realtimeUsage.global.turns, 1);
  assert.equal(restarted.usage(state).device('one').reserved.seconds, 65);
});

test('revocation during durable reservation closes the allocated slot before any provider connection can open', async t => {
  const f = await fixture(t); const device = await f.device();
  const app = f.store; const admission = new RealtimeAdmission(app);
  let reached; const waiting = new Promise(resolve => { reached = resolve; });
  let release; const gate = new Promise(resolve => { release = resolve; });
  const result = f.realtime.reply({ deviceId: device.deviceId, text: 'not sent', origin: '127.0.0.1', beforeConnect: async () => { reached(); await gate; await admission.reserve(device.deviceId, '127.0.0.1', 'http'); } });
  result.catch(() => {}); await waiting;
  assert.equal((await f.request(`/institution/devices/${device.deviceId}/revoke`, { token: 'operator-test', body: {} })).status, 200);
  release(); await assert.rejects(result); assert.equal(f.providerOpens(), 0); assert.equal(f.realtime.active.size, 0);
});

test('a malformed saved budget fails closed instead of resetting or permitting unbounded use', async t => {
  const folder = await mkdtemp(join(tmpdir(), 'nieto-invalid-budget-')); t.after(() => rm(folder, { recursive: true, force: true }));
  const store = new JsonStore(join(folder, 'data.json'));
  await store.update(state => { state.users.push({ id: 'one', realtimeApproved: true }); state.realtimeUsage = { day: new Date().toISOString().slice(0, 10), devices: {}, origins: {}, global: { turns: null, seconds: 0, contexts: 0 } }; });
  await assert.rejects(new RealtimeAdmission(store).reserve('one', 'origin', 'http'), error => error.code === 'realtime_budget_unavailable');
  assert.equal((await store.read()).realtimeUsage.global.turns, null);
});
