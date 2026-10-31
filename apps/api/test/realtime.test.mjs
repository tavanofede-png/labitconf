import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { request as httpRequest } from 'node:http';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import WebSocket, { WebSocketServer } from 'ws';
import { RealtimeConversation, RealtimeService, REALTIME_MODEL, MAX_AUDIO_CHUNK } from '../src/realtime.mjs';
import { createServer } from '../src/server.mjs';
import { OpenArgClient } from '../src/openarg.mjs';
import { validateSnapshot, MAX_IMAGE_BYTES } from '../src/screen-context.mjs';

function eventsFrom(target, name = 'event') {
  const seen = []; const waiting = [];
  target.on(name, value => { seen.push(value); for (const waiter of [...waiting]) if (waiter.predicate(value)) { waiting.splice(waiting.indexOf(waiter), 1); clearTimeout(waiter.timer); waiter.resolve(value); } });
  return { seen, wait: predicate => {
    const match = seen.find(predicate); if (match) return Promise.resolve(match);
    return new Promise((resolve, reject) => { const entry = { predicate, resolve, timer: setTimeout(() => { waiting.splice(waiting.indexOf(entry), 1); reject(new Error('Timed out waiting for test event')); }, 2000) }; waiting.push(entry); });
  } };
}

async function fakeOpenAi(t, responder) {
  const messages = []; const requests = []; const sockets = new Set(); let number = 0;
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 }); await once(server, 'listening');
  const sendResponse = (socket, event, extra = {}) => {
    const id = `response-${++number}`;
    socket.send(JSON.stringify({ type: 'response.created', response: { id, metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.output_text.delta', response_id: id, delta: 'Respuesta de prueba.' }));
    socket.send(JSON.stringify({ type: 'response.output_text.done', response_id: id, text: 'Respuesta de prueba.' }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id, status: 'completed', output: [], ...extra } }));
  };
  server.on('connection', (socket, req) => {
    requests.push(req); sockets.add(socket); socket.once('close', () => sockets.delete(socket));
    socket.on('message', raw => {
      const event = JSON.parse(raw.toString()); messages.push(event);
      if (event.type === 'session.update') socket.send(JSON.stringify({ type: 'session.updated', session: event.session }));
      else if (event.type === 'response.create') { if (responder) responder(socket, event, { sendResponse, messages }); else sendResponse(socket, event); }
    });
    socket.send(JSON.stringify({ type: 'session.created', session: { id: 'fake-session' } }));
  });
  t.after(async () => { for (const socket of sockets) socket.terminate(); await new Promise(resolve => server.close(resolve)); });
  return { endpoint: `ws://127.0.0.1:${server.address().port}`, requests, messages, sockets };
}

async function apiFixture(t, realtime) {
  const folder = await mkdtemp(join(tmpdir(), 'nieto-realtime-'));
  const dataFile = join(folder, 'data.json');
  const server = createServer({ dataFile, realtime, institutionToken: 'institution-test', openarg: new OpenArgClient({ apiKey: '' }) });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeRealtime(); await new Promise(resolve => server.close(resolve)); await rm(folder, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = async (path, { token, body, method = body ? 'POST' : 'GET' } = {}) => {
    const response = await fetch(base + '/api' + path, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) });
    return { status: response.status, body: await response.json() };
  };
  const device = (await request('/devices', { body: { name: 'Dispositivo de prueba', territoryId: 'AR-C' } })).body;
  return { base, request, device, dataFile, server };
}

test('Realtime HTTP uses exact requested model and keeps queries and keys out of persistence', async t => {
  const upstream = await fakeOpenAi(t);
  const realtime = new RealtimeService({ apiKey: 'fake-key-not-a-real-secret', conversationOptions: { endpoint: upstream.endpoint } });
  const { request, device, dataFile } = await apiFixture(t, realtime);
  const result = await request('/assistant/message', { token: device.token, body: { text: 'Mensaje privado que no debe guardarse' } });
  assert.equal(result.status, 200); assert.equal(result.body.model, REALTIME_MODEL); assert.equal(result.body.instruction, 'Respuesta de prueba.');
  assert.equal(upstream.requests[0].headers.authorization, 'Bearer fake-key-not-a-real-secret');
  assert.equal(new URL(upstream.requests[0].url, 'http://localhost').searchParams.get('model'), REALTIME_MODEL);
  const config = upstream.messages.find(event => event.type === 'session.update').session;
  assert.deepEqual(config.output_modalities, ['text']); assert.equal(config.audio.input.turn_detection, null);
  const disk = await readFile(dataFile, 'utf8'); assert.equal(disk.includes('Mensaje privado'), false); assert.equal(disk.includes('fake-key'), false);
  assert.equal(realtime.active.size, 0);
});

test('missing OpenAI configuration returns explicit 503 instead of fabricated conversation', async t => {
  const realtime = new RealtimeService({ apiKey: '' }); const { request, device } = await apiFixture(t, realtime);
  const result = await request('/assistant/message', { token: device.token, body: { text: 'Hola' } });
  assert.equal(result.status, 503); assert.equal(result.body.error.code, 'openai_not_configured');
  const health = (await request('/health')).body;
  assert.equal(health.assistant.configured, false); assert.equal(health.assistant.model, REALTIME_MODEL); assert.equal(health.assistant.generalConversation, false);
});

test('negative and unrelated WhatsApp requests reach the model unchanged and are not redirected by keyword matching', async t => {
  const upstream = await fakeOpenAi(t);
  const realtime = new RealtimeService({ apiKey: 'test-key', conversationOptions: { endpoint: upstream.endpoint } });
  const { request, device } = await apiFixture(t, realtime);
  for (const text of ['No quiero compartir mi ubicación.', 'Quiero cambiar el tamaño de la letra de WhatsApp.']) {
    const result = await request('/assistant/message', { token: device.token, body: { text } });
    assert.equal(result.status, 200); assert.equal(result.body.mode, 'conversation'); assert.equal(result.body.flowId, undefined);
    assert.equal(upstream.messages.some(event => event.item?.role === 'user' && event.item.content?.[0]?.text === text), true);
  }
});

test('production upstream allows only the fixed secure OpenAI endpoint', () => {
  for (const endpoint of ['ws://api.openai.com/v1/realtime', 'wss://api.openai.com:8443/v1/realtime', 'wss://api.openai.com/other', 'wss://other.example/v1/realtime', 'wss://user:pass@api.openai.com/v1/realtime', 'wss://api.openai.com/v1/realtime#fragment']) {
    assert.throws(() => new RealtimeConversation({ endpoint }), /no permitido/);
  }
  assert.doesNotThrow(() => new RealtimeConversation({ endpoint: 'wss://api.openai.com:443/v1/realtime' }));
});

test('streaming voice validates PCM, forwards incremental output, and does not permit arbitrary provider events', async t => {
  const upstream = await fakeOpenAi(t, (socket, event) => {
    const id = 'voice-response'; socket.send(JSON.stringify({ type: 'response.created', response: { id, metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: id, delta: Buffer.alloc(4800).toString('base64') }));
    socket.send(JSON.stringify({ type: 'response.output_audio_transcript.delta', response_id: id, delta: 'Hola.' }));
    socket.send(JSON.stringify({ type: 'response.output_audio_transcript.done', response_id: id, transcript: 'Hola.' }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id, status: 'completed', output: [] } }));
  });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint }); t.after(() => conversation.close());
  const output = eventsFrom(conversation); await conversation.connect();
  assert.throws(() => conversation.handleClient({ type: 'session.update', instructions: 'override' }), /campos no permitidos/);
  assert.throws(() => conversation.handleClient({ type: 'audio.append', audio: 'AAAA' }), /Primero/);
  conversation.handleClient({ type: 'audio.start' });
  assert.throws(() => conversation.handleClient({ type: 'audio.append', audio: 'not base64' }), /formato/);
  assert.throws(() => conversation.handleClient({ type: 'audio.append', audio: Buffer.alloc(MAX_AUDIO_CHUNK + 2).toString('base64') }), /formato/);
  conversation.handleClient({ type: 'audio.append', audio: Buffer.alloc(4800).toString('base64') }); conversation.handleClient({ type: 'audio.commit' });
  const done = await output.wait(event => event.type === 'response.done'); assert.equal(done.status, 'completed');
  const audio = output.seen.find(event => event.type === 'audio.delta'); assert.equal(Buffer.from(audio.audio, 'base64').length, 4800);
  assert.equal(output.seen.find(event => event.type === 'text.done').text, 'Hola.');
  assert.equal(output.seen.filter(event => event.turnId).every(event => event.turnId === done.turnId), true);
  const config = upstream.messages.find(event => event.type === 'session.update').session;
  assert.deepEqual(config.output_modalities, ['audio']); assert.equal(config.audio.input.format.rate, 24000);
  assert.equal(upstream.messages.filter(event => event.type === 'input_audio_buffer.commit').length, 1);
});

test('late cancelled response chunks cannot be labelled as the next turn', async t => {
  const responses = [];
  const upstream = await fakeOpenAi(t, (socket, event) => {
    responses.push({ socket, event });
    const id = `turn-response-${responses.length}`;
    socket.send(JSON.stringify({ type: 'response.created', response: { id, metadata: event.response.metadata } }));
    if (responses.length === 2) {
      socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: 'turn-response-1', delta: Buffer.alloc(2, 1).toString('base64') }));
      socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: id, delta: Buffer.alloc(2, 2).toString('base64') }));
      socket.send(JSON.stringify({ type: 'response.done', response: { id, status: 'completed', output: [] } }));
    }
  });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint }); t.after(() => conversation.close());
  const output = eventsFrom(conversation); await conversation.connect(); conversation.handleClient({ type: 'text', text: 'Primero' });
  const first = output.seen.find(event => event.type === 'response.started').turnId;
  conversation.handleClient({ type: 'cancel' }); conversation.handleClient({ type: 'text', text: 'Segundo' });
  const done = await output.wait(event => event.type === 'response.done'); assert.notEqual(done.turnId, first);
  const audio = output.seen.filter(event => event.type === 'audio.delta'); assert.equal(audio.length, 1); assert.equal(audio[0].audio, Buffer.alloc(2, 2).toString('base64'));
});

test('legacy guide offers cannot redirect negative, cancelled or unrelated requests even when upstream attempts them', async t => {
  const rounds = new WeakMap(); const published = [];
  const upstream = await fakeOpenAi(t, (socket, event, helper) => {
    const round = (rounds.get(socket) ?? 0) + 1; rounds.set(socket, round);
    if (round === 1) {
      socket.send(JSON.stringify({ type: 'response.created', response: { id: 'tool-response', metadata: event.response.metadata } }));
      socket.send(JSON.stringify({ type: 'response.done', response: { id: 'tool-response', status: 'completed', output: [{ type: 'function_call', name: 'offer_guided_flow', call_id: 'call-1', arguments: JSON.stringify({ flowId: 'whatsapp-location' }) }] } }));
    } else helper.sendResponse(socket, event);
  });
  const realtime = new RealtimeService({ apiKey: 'test-key', conversationOptions: { endpoint: upstream.endpoint }, conversationFactory: options => {
    const conversation = new RealtimeConversation(options); conversation.on('event', event => published.push(event)); return conversation;
  } });
  const { request, device } = await apiFixture(t, realtime);
  for (const text of ['No quiero compartir mi ubicación.', 'Cancelá la tarea de ubicación.', 'Quiero agrandar la letra de WhatsApp.']) {
    const result = await request('/assistant/message', { token: device.token, body: { text } });
    assert.equal(result.status, 200); assert.equal(result.body.mode, 'conversation'); assert.equal(result.body.flowId, undefined);
  }
  const outputs = upstream.messages.filter(event => event.item?.type === 'function_call_output').map(event => JSON.parse(event.item.output));
  assert.equal(outputs.length, 3); assert.equal(outputs.every(output => output.error === 'unsupported_tool' && output.executed === false), true);
  assert.equal(published.some(event => event.type === 'guidance.offer'), false);
  assert.equal((await request('/assistant/history', { token: device.token })).body.sessions.length, 0);
  assert.equal(upstream.messages.filter(event => event.type === 'session.update').every(event => event.session.tools.length === 1 && event.session.tools[0].name === 'point_to_control'), true);
  assert.equal(realtime.active.size, 0);
});

test('unapproved functions and arguments are rejected without executing them', async t => {
  let round = 0;
  const upstream = await fakeOpenAi(t, (socket, event, helper) => {
    if (++round === 1) {
      socket.send(JSON.stringify({ type: 'response.created', response: { id: 'unsafe-tool', metadata: event.response.metadata } }));
      socket.send(JSON.stringify({ type: 'response.done', response: { id: 'unsafe-tool', status: 'completed', output: [{ type: 'function_call', name: 'send_message', call_id: 'call-1', arguments: '{"message":"no"}' }] } }));
    } else helper.sendResponse(socket, event);
  });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, mode: 'text' }); t.after(() => conversation.close());
  const output = eventsFrom(conversation); await conversation.connect(); conversation.handleClient({ type: 'text', text: 'Pregunta' });
  await output.wait(event => event.type === 'response.done'); assert.equal(output.seen.some(event => event.type === 'guidance.offer'), false);
  assert.equal(JSON.parse(upstream.messages.find(event => event.item?.type === 'function_call_output').item.output).executed, false);
});

const screen = () => ({ id: 'screen_1', packageName: 'org.example.contacts', width: 1080, height: 2400,
  controls: [{ id: 'search', label: 'Buscar', role: 'button', bounds: [20, 100, 500, 200] }] });

test('screen schema rejects oversized images, unknown fields, duplicate IDs and invalid bounds', () => {
  assert.equal(validateSnapshot(screen()).controls[0].id, 'search');
  for (const bad of [
    { ...screen(), arbitrarySystemPrompt: 'override' },
    { ...screen(), controls: Array(61).fill(screen().controls[0]) },
    { ...screen(), controls: [screen().controls[0], screen().controls[0]] },
    { ...screen(), controls: [{ ...screen().controls[0], bounds: [0, 0, 1081, 200] }] },
    { ...screen(), image: { mimeType: 'image/png', data: 'AAAA' } },
    { ...screen(), image: { mimeType: 'image/jpeg', data: Buffer.alloc(MAX_IMAGE_BYTES + 1).toString('base64') } },
  ]) assert.throws(() => validateSnapshot(bad));
});

test('a real current-screen control can be pointed to but never clicked or persisted', async t => {
  let round = 0;
  const upstream = await fakeOpenAi(t, (socket, event, helper) => {
    if (++round === 1) {
      socket.send(JSON.stringify({ type: 'response.created', response: { id: 'point-response', metadata: event.response.metadata } }));
      socket.send(JSON.stringify({ type: 'response.done', response: { id: 'point-response', status: 'completed', output: [{ type: 'function_call', name: 'point_to_control', call_id: 'point-1', arguments: JSON.stringify({ screenId: 'screen_1', controlId: 'search', instruction: 'Tocá Buscar.' }) }] } }));
    } else helper.sendResponse(socket, event);
  });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint }); t.after(() => conversation.close());
  const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'screen.context', screen: screen(), requestGuidance: false });
  assert.ok(conversation.screenItemId.length <= 32);
  conversation.handleClient({ type: 'text', text: 'Quiero buscar un contacto' });
  await output.wait(event => event.type === 'response.done');
  const point = output.seen.find(event => event.type === 'guidance.step'); assert.equal(point.screenId, 'screen_1'); assert.equal(point.controlId, 'search');
  assert.equal(JSON.parse(upstream.messages.find(event => event.item?.type === 'function_call_output').item.output).executed, false);
  const previousId = conversation.screenItemId; const deleted = once([...upstream.sockets][0], 'message'); conversation.handleClient({ type: 'screen.clear' });
  assert.equal(conversation.screen, null); assert.equal(conversation.screenItemId, null);
  await deleted;
  assert.equal(upstream.messages.some(event => event.type === 'conversation.item.delete' && event.item_id === previousId), true);
  assert.throws(() => conversation.handleClient({ type: 'screen.context', screen: screen() }), /Esperá/);
  conversation.close(); assert.equal(conversation.screen, null);
});

test('unknown or stale screen IDs cannot produce a guidance step', async t => {
  let round = 0;
  const upstream = await fakeOpenAi(t, (socket, event, helper) => {
    if (++round === 1) {
      socket.send(JSON.stringify({ type: 'response.created', response: { id: 'forged-point', metadata: event.response.metadata } }));
      socket.send(JSON.stringify({ type: 'response.done', response: { id: 'forged-point', status: 'completed', output: [{ type: 'function_call', name: 'point_to_control', call_id: 'point-forged', arguments: JSON.stringify({ screenId: 'old_screen', controlId: 'search', instruction: 'Tocá Buscar.' }) }] } }));
    } else helper.sendResponse(socket, event);
  });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint }); t.after(() => conversation.close());
  const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'screen.context', screen: screen(), requestGuidance: true });
  await output.wait(event => event.type === 'response.done');
  assert.equal(output.seen.some(event => event.type === 'guidance.step'), false);
  assert.equal(JSON.parse(upstream.messages.find(event => event.item?.type === 'function_call_output').item.output).error, 'stale_or_unknown_control');
});

test('WebSocket authenticates the device before opening upstream and closes its resource on disconnect', async t => {
  const upstream = await fakeOpenAi(t); const realtime = new RealtimeService({ apiKey: 'test-key', conversationOptions: { endpoint: upstream.endpoint } });
  const { base, device } = await apiFixture(t, realtime); const url = base.replace('http:', 'ws:') + '/api/assistant/realtime';
  const rejected = new WebSocket(url, { headers: { Authorization: 'Bearer wrong-token' } }); rejected.on('error', () => {});
  const rejection = await new Promise(resolve => rejected.once('unexpected-response', (_request, response) => { response.resume(); rejected.terminate(); resolve(response.statusCode); }));
  assert.equal(rejection, 401); assert.equal(upstream.requests.length, 0);
  const client = new WebSocket(url, { headers: { Authorization: `Bearer ${device.token}` } }); client.on('error', () => {});
  const output = eventsFrom(client, 'parsed'); client.on('message', raw => client.emit('parsed', JSON.parse(raw.toString())));
  await output.wait(event => event.type === 'ready'); assert.equal(upstream.requests.length, 1); assert.equal(realtime.active.size, 1);
  const released = once([...realtime.active.values()][0], 'closed');
  client.close(); await once(client, 'close'); await released;
  assert.equal(realtime.active.size, 0);
});

test('a malformed authenticated upgrade cannot leak a reserved voice slot', async t => {
  const upstream = await fakeOpenAi(t); const realtime = new RealtimeService({ apiKey: 'test-key', conversationOptions: { endpoint: upstream.endpoint } });
  const { base, device } = await apiFixture(t, realtime);
  const status = await new Promise((resolve, reject) => {
    const req = httpRequest(base + '/api/assistant/realtime', { headers: { Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Version': '13', 'Sec-WebSocket-Key': 'bad', Authorization: `Bearer ${device.token}` } }, response => { response.resume(); response.once('end', () => resolve(response.statusCode)); });
    req.once('error', reject); req.end();
  });
  assert.equal(status, 400); await new Promise(resolve => setImmediate(resolve)); assert.equal(realtime.active.size, 0); assert.equal(upstream.requests.length, 0);
});

test('HTTP cancellation during connection preparation cannot trigger a later model response', async t => {
  const sockets = [];
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 }); await once(server, 'listening');
  const sent = []; server.on('connection', socket => { sockets.push(socket); socket.on('message', message => sent.push(JSON.parse(message.toString()))); });
  t.after(async () => { for (const socket of sockets) socket.terminate(); await new Promise(resolve => server.close(resolve)); });
  const realtime = new RealtimeService({ apiKey: 'test-key', conversationOptions: { endpoint: `ws://127.0.0.1:${server.address().port}` } });
  const controller = new AbortController(); const request = realtime.reply({ deviceId: 'cancel-device', text: 'No enviar al cancelar', signal: controller.signal });
  controller.abort(); await assert.rejects(request); assert.equal(realtime.active.size, 0); assert.equal(sent.some(event => event.type === 'response.create'), false);
});

test('upstream provider errors are redacted and do not fall back to another model', async t => {
  const upstream = await fakeOpenAi(t, socket => socket.send(JSON.stringify({ type: 'error', error: { code: 'insufficient_quota', message: 'raw details private-key-example' } })));
  const realtime = new RealtimeService({ apiKey: 'test-key', conversationOptions: { endpoint: upstream.endpoint } });
  await assert.rejects(realtime.reply({ deviceId: 'quota-device', text: 'Hola' }), error => error.code === 'openai_quota' && !error.message.includes('private-key'));
  assert.equal(upstream.requests.length, 1); assert.equal(realtime.active.size, 0);
});

test('a recording is bounded at 45 seconds of PCM and invalid capture never asks for a response', async t => {
  const upstream = await fakeOpenAi(t);
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint }); t.after(() => conversation.close());
  await conversation.connect(); conversation.handleClient({ type: 'audio.start' });
  assert.throws(() => conversation.handleClient({ type: 'audio.commit' }), /corta/);
  assert.equal(upstream.messages.some(event => event.type === 'response.create'), false);
  conversation.handleClient({ type: 'audio.start' });
  const chunk = Buffer.alloc(12_000).toString('base64');
  for (let index = 0; index < 180; index++) conversation.handleClient({ type: 'audio.append', audio: chunk });
  assert.throws(() => conversation.handleClient({ type: 'audio.append', audio: Buffer.alloc(2).toString('base64') }), /45 segundos/);
  assert.equal(conversation.recording, false); assert.equal(conversation.inputBytes, 0);
  assert.equal(upstream.messages.some(event => event.type === 'response.create'), false);
});

test('voice connection limits and idle expiry release reserved resources', async t => {
  const upstream = await fakeOpenAi(t);
  const realtime = new RealtimeService({ apiKey: 'test-key', maxConnections: 1, conversationOptions: { endpoint: upstream.endpoint, idleTimeoutMs: 30 } });
  const first = realtime.reserve('device-one'); t.after(() => realtime.close());
  assert.throws(() => realtime.reserve('device-one'), error => error.code === 'realtime_already_active');
  assert.throws(() => realtime.reserve('device-two'), error => error.code === 'realtime_busy');
  const failure = once(first, 'failure'); await first.connect();
  const [error] = await failure; assert.equal(error.code, 'realtime_idle'); assert.equal(realtime.active.size, 0);
  const second = realtime.reserve('device-two'); second.close(); assert.equal(realtime.active.size, 0);
});

test('a response timeout closes its provider connection without retrying', async t => {
  const upstream = await fakeOpenAi(t, () => {});
  const realtime = new RealtimeService({ apiKey: 'test-key', conversationOptions: { endpoint: upstream.endpoint, responseTimeoutMs: 25 } });
  await assert.rejects(realtime.reply({ deviceId: 'timeout-device', text: 'Hola' }), error => error.code === 'openai_timeout');
  assert.equal(upstream.requests.length, 1); assert.equal(realtime.active.size, 0);
});
