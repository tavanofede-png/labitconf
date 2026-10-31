import test from 'node:test';
import assert from 'node:assert/strict';
import { once, EventEmitter } from 'node:events';
import WebSocket from 'ws';
import defaultCloudServer, { createCloudServer, vercelClientAddress } from '../src/vercel.mjs';
import { BlobStore } from '../src/blob-store.mjs';
import { OpenArgClient } from '../src/openarg.mjs';
import { RealtimeService, REALTIME_MODEL } from '../src/realtime.mjs';
import { RealtimeAdmission } from '../src/admission.mjs';
import { createServer } from '../src/server.mjs';

// The exported default is a lazy, non-listening HTTP server, matching Vercel's
// native HTTP/WebSocket entrypoint contract. No storage or provider request runs.
defaultCloudServer.closeRealtime();
assert.equal(defaultCloudServer.listeners('request')[0].realtime.options.maxSessionMs, 270_000);

test('only Vercel runtime trusts its single, valid ingress IP and local requests cannot spoof origins', () => {
  const request = { headers: { 'x-vercel-forwarded-for': '203.0.113.4', 'x-forwarded-for': '198.51.100.7' }, socket: { remoteAddress: '127.0.0.1' } };
  assert.equal(vercelClientAddress(request, ''), '127.0.0.1');
  assert.equal(vercelClientAddress(request, '1'), '203.0.113.4');
  request.headers['x-vercel-forwarded-for'] = '203.0.113.4, 198.51.100.7';
  assert.equal(vercelClientAddress(request, '1'), '127.0.0.1');
  delete request.headers['x-vercel-forwarded-for'];
  assert.equal(vercelClientAddress(request, '1'), '127.0.0.1');
});

function durableClient() {
  let content = null, version = 0;
  class BlobPreconditionFailedError extends Error {}
  return {
    BlobPreconditionFailedError,
    async get(_pathname, options) {
      assert.equal(options.access, 'private'); assert.equal(options.useCache, false);
      if (content === null) return null;
      const bytes = Buffer.from(content); const etag = `"${version}"`;
      return { statusCode: 200, blob: { etag, size: bytes.length }, stream: new ReadableStream({ start(controller) { controller.enqueue(bytes); controller.close(); } }) };
    },
    async put(_pathname, next, options) {
      assert.equal(options.access, 'private');
      if (!options.allowOverwrite && content !== null) throw Error('Exists');
      if (options.ifMatch && options.ifMatch !== `"${version}"`) throw new BlobPreconditionFailedError();
      content = next; version++;
    },
    content: () => content,
  };
}

test('cloud Function rewrite alias requires authentication and is unavailable on local servers', async t => {
  const cloud = createCloudServer({ store: new BlobStore({ client: durableClient() }), realtime: new RealtimeService({ apiKey: '' }) });
  const local = createServer({ realtime: new RealtimeService({ apiKey: '' }) });
  await Promise.all([cloud, local].map(server => new Promise(resolve => server.listen(0, '127.0.0.1', resolve))));
  t.after(async () => { cloud.closeRealtime(); local.closeRealtime(); await Promise.all([cloud, local].map(server => new Promise(resolve => server.close(resolve)))); });
  const status = (server, path = '/api/index') => new Promise((resolve, reject) => {
    const client = new WebSocket(`ws://127.0.0.1:${server.address().port}${path}`, { handshakeTimeout: 2000 });
    client.on('error', () => {});
    client.once('unexpected-response', (_request, response) => { response.resume(); client.terminate(); resolve(response.statusCode); });
    client.once('open', () => { client.terminate(); reject(Error('Alias opened without authentication')); });
  });
  assert.equal(await status(cloud), 401);
  assert.equal(await status(local), 404);
  assert.equal(await status(cloud, '/api/index?path=assistant%2Frealtime'), 401);
  assert.equal(await status(cloud, '/api/index?path=assistant%2Frealtime&token=not-accepted'), 404);
  assert.equal(await status(cloud, '/api/index?path=other'), 404);
  assert.equal(await status(local, '/api/assistant/realtime?path=assistant%2Frealtime'), 404);
});

class TestConversation extends EventEmitter {
  closed = false;
  async connect() { this.emit('event', { type: 'ready', model: REALTIME_MODEL }); return this; }
  handleClient() {}
  close() { if (!this.closed) { this.closed = true; this.emit('closed'); } }
}

test('cloud entrypoint serves HTTP and authenticated WS using state committed by another function instance', async t => {
  const client = durableClient(); const stores = [0, 1].map(() => new BlobStore({ client, sleep: async () => {} }));
  let providerConnects = 0;
  const servers = stores.map(store => createCloudServer({ store, institutionToken: 'operator-test',
    openarg: new OpenArgClient({ apiKey: '' }),
    realtime: new RealtimeService({ apiKey: 'not-a-real-key', conversationFactory: () => {
      const conversation = new TestConversation();
      conversation.connect = async () => { providerConnects++; conversation.emit('event', { type: 'ready', model: REALTIME_MODEL }); return conversation; };
      return conversation;
    } }),
  }));
  await Promise.all(servers.map(server => new Promise(resolve => server.listen(0, '127.0.0.1', resolve))));
  t.after(async () => { for (const server of servers) server.closeRealtime(); await Promise.all(servers.map(server => new Promise(resolve => server.close(resolve)))); });
  const bases = servers.map(server => `http://127.0.0.1:${server.address().port}`);
  const post = async (instance, path, body, token) => {
    const response = await fetch(bases[instance] + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  };
  const invitation = await post(0, '/api/institution/device-invitations', {}, 'operator-test');
  assert.equal(invitation.status, 201);
  const device = await post(1, '/api/devices', { name: 'Tablet', territoryId: 'AR-C', enrollmentCode: invitation.body.enrollmentCode });
  assert.equal(device.status, 201);
  assert.equal((await stores[0].read()).users[0].id, device.body.deviceId);
  const socket = new WebSocket(bases[0].replace('http:', 'ws:') + '/api/index?path=assistant%2Frealtime', { headers: { Authorization: `Bearer ${device.body.token}` } });
  t.after(() => socket.terminate());
  const ready = once(socket, 'message');
  await once(socket, 'open');
  const [raw] = await ready; assert.deepEqual(JSON.parse(raw.toString()), { type: 'ready', model: REALTIME_MODEL });
  assert.equal(providerConnects, 1);
  assert.equal((await stores[1].read()).realtimeUsage.global.turns, 20);
  assert.equal((await stores[1].read()).realtimeLeases.length, 1);
  assert.equal(client.content().includes(device.body.token), false);
  assert.equal(client.content().includes(invitation.body.enrollmentCode), false);
  socket.close(); await once(socket, 'close');
  for (let attempt = 0; attempt < 20 && (await stores[0].read()).realtimeLeases.length; attempt++)
    await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal((await stores[0].read()).realtimeLeases.length, 0);
});

test('cloud concurrency leases enforce shared device, origin and global caps, then release or expire safely', async () => {
  const client = durableClient(); const a = new BlobStore({ client, sleep: async () => {} }), b = new BlobStore({ client, sleep: async () => {} });
  await a.update(state => { for (let index = 0; index < 12; index++) state.users.push({ id: `device-${index}`, realtimeApproved: true }); });
  let clock = Date.UTC(2026, 9, 31, 12);
  const first = new RealtimeAdmission(a, { now: () => clock }), second = new RealtimeAdmission(b, { now: () => clock });
  const one = await first.reserve('device-0', 'origin-a', 'ws');
  await assert.rejects(second.reserve('device-0', 'another-origin', 'http'), error => error.code === 'realtime_already_active');
  const two = await second.reserve('device-1', 'origin-a', 'ws');
  await assert.rejects(first.reserve('device-2', 'origin-a', 'http'), error => error.code === 'realtime_origin_busy');
  const rest = await Promise.all(Array.from({ length: 6 }, (_, index) => (index % 2 ? first : second).reserve(`device-${index + 2}`, `origin-${index}`, 'ws')));
  await assert.rejects(first.reserve('device-8', 'origin-extra', 'http'), error => error.code === 'realtime_busy');
  assert.equal((await a.read()).realtimeUsage.global.turns, 160);
  await second.release(one.leaseId);
  const next = await first.reserve('device-8', 'origin-extra', 'http');
  assert.equal((await b.read()).realtimeLeases.length, 8);
  clock += 75_001;
  await assert.rejects(second.validateLease(next.leaseId), error => error.code === 'device_activation_required');
  await first.reserve('device-9', 'origin-9', 'http');
  assert.equal((await a.read()).realtimeLeases.length, 8);
  await second.release(two.leaseId);
  await a.update(state => { state.users.find(user => user.id === 'device-2').realtimeApproved = false; });
  await assert.rejects(second.validateLease(rest[0].leaseId), error => error.code === 'device_activation_required');
  clock += 300_001;
  const renewed = await second.reserve('device-0', 'origin-a', 'ws');
  assert.ok(renewed.leaseId); assert.equal((await a.read()).realtimeLeases.length, 1);
  assert.equal(JSON.stringify((await a.read()).realtimeLeases).includes('origin-a'), false);
});

test('operator-authorized continuous mode removes daily cuts while preserving history, admission and shared concurrency', async () => {
  const client = durableClient(); const store = new BlobStore({ client, sleep: async () => {} });
  const previousUsage = { day: '2026-10-30', devices: {}, origins: {}, global: { turns: 9999, seconds: 99999, contexts: 9999 } };
  await store.update(state => {
    state.users.push({ id: 'approved', realtimeApproved: true }, { id: 'second', realtimeApproved: true }, { id: 'third', realtimeApproved: true }, { id: 'unapproved' });
    state.realtimeUsage = previousUsage;
  });
  const admission = new RealtimeAdmission(store, { continuous: true });
  await assert.rejects(admission.reserve('unapproved', 'same-origin', 'ws'), error => error.code === 'device_activation_required');
  const active = await admission.reserve('approved', 'same-origin', 'ws');
  await assert.rejects(admission.reserve('approved', 'another-origin', 'ws'), error => error.code === 'realtime_already_active');
  const second = await admission.reserve('second', 'same-origin', 'ws');
  await assert.rejects(admission.reserve('third', 'same-origin', 'ws'), error => error.code === 'realtime_origin_busy');
  await admission.release(active.leaseId); await admission.release(second.leaseId);
  for (let index = 0; index < 64; index++) { const lease = await admission.reserve('approved', 'same-origin', 'ws'); await admission.release(lease.leaseId); }
  assert.deepEqual((await store.read()).realtimeUsage, previousUsage);
  assert.equal(admission.status().dailyLimitsEnforced, false); assert.equal(admission.status().limits, null);
  assert.equal(admission.usage(await store.read()).device('approved').remaining, null);
});

test('HTTP cloud reply holds a durable lease until provider finishes and releases it before completing the response', async t => {
  const client = durableClient(); const store = new BlobStore({ client, sleep: async () => {} });
  let heldDuringProvider = false;
  const realtime = new RealtimeService({ apiKey: 'not-a-real-key', conversationFactory: () => {
    const conversation = new TestConversation();
    conversation.handleClient = () => {
      heldDuringProvider = JSON.parse(client.content()).realtimeLeases.length === 1;
      conversation.emit('result', { status: 'completed', text: 'Abrí Gmail para empezar.' });
    };
    return conversation;
  } });
  const server = createCloudServer({ store, realtime, institutionToken: 'operator-test', openarg: new OpenArgClient({ apiKey: '' }),
    allowLocalAssistant: true, accountOptions: { judgesDemo: true, localDemo: true } });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeRealtime(); await new Promise(resolve => server.close(resolve)); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = async (path, body, token) => {
    const response = await fetch(base + '/api' + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body) });
    return { status: response.status, body: await response.json() };
  };
  const demoLogin = await post('/auth/demo/login', { email: 'test@labitconf.com', password: '0000' });
  assert.equal(demoLogin.status, 404); assert.equal(demoLogin.body.error.code, 'demo_unavailable');
  const localCode = await post('/auth/code/request', { email: 'person@example.com', role: 'user', purpose: 'login' });
  assert.equal(localCode.status, 503); assert.equal(localCode.body.error.code, 'email_not_configured');
  const invitation = await post('/institution/device-invitations', {}, 'operator-test');
  const device = await post('/devices', { name: 'Tablet', enrollmentCode: invitation.body.enrollmentCode });
  const reply = await post('/assistant/message', { text: 'Quiero crear un grupo de WhatsApp' }, device.body.token);
  assert.equal(reply.status, 200); assert.equal(heldDuringProvider, true);
  assert.equal(reply.body.instruction, 'Abrí Gmail para empezar.');
  assert.equal(reply.body.model, REALTIME_MODEL);
  assert.equal((await store.read()).realtimeLeases.length, 0);
});
