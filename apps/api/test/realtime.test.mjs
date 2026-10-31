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
  const messages = []; const requests = []; const connections = []; const sockets = new Set(); let number = 0;
  const server = new WebSocketServer({ host: '127.0.0.1', port: 0 }); await once(server, 'listening');
  const sendResponse = (socket, event, extra = {}) => {
    const id = `response-${++number}`;
    socket.send(JSON.stringify({ type: 'response.created', response: { id, metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.output_text.delta', response_id: id, delta: 'Respuesta de prueba.' }));
    socket.send(JSON.stringify({ type: 'response.output_text.done', response_id: id, text: 'Respuesta de prueba.' }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id, status: 'completed', output: [], ...extra } }));
  };
  server.on('connection', (socket, req) => {
    const connection = { messages: [] }; connections.push(connection);
    requests.push(req); sockets.add(socket); socket.once('close', () => sockets.delete(socket));
    socket.on('message', raw => {
      const event = JSON.parse(raw.toString()); messages.push(event); connection.messages.push(event);
      if (event.type === 'session.update') socket.send(JSON.stringify({ type: 'session.updated', session: event.session }));
      else if (event.type === 'response.create') { if (responder) responder(socket, event, { sendResponse, messages }); else sendResponse(socket, event); }
    });
    socket.send(JSON.stringify({ type: 'session.created', session: { id: 'fake-session' } }));
  });
  t.after(async () => { for (const socket of sockets) socket.terminate(); await new Promise(resolve => server.close(resolve)); });
  return { endpoint: `ws://127.0.0.1:${server.address().port}`, requests, messages, connections, sockets };
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
  const enrollment = (await request('/institution/device-invitations', { token: 'institution-test', body: {} })).body;
  const device = (await request('/devices', { body: { name: 'Dispositivo de prueba', territoryId: 'AR-C', enrollmentCode: enrollment.enrollmentCode } })).body;
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
  assert.equal(config.max_output_tokens, 800);
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

test('HTTP voice replies carry real PCM in a playable WAV and retain the exact Realtime model', async t => {
  const pcm = Buffer.alloc(4800); for (let i = 0; i < pcm.length; i += 2) pcm.writeInt16LE(Math.round(Math.sin(i / 12) * 8000), i);
  const upstream = await fakeOpenAi(t, (socket, event) => {
    const id = 'http-voice';
    // A normal spoken reply also spends tokens on reasoning and its transcript.
    // Reproduce the provider's cut-off when the text-only budget is reused.
    const complete = event.response.max_output_tokens >= 1200;
    socket.send(JSON.stringify({ type: 'response.created', response: { id, metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: id, item_id: 'voice-item', delta: pcm.toString('base64') }));
    socket.send(JSON.stringify({ type: 'response.output_audio_transcript.done', response_id: id, transcript: 'Acá estoy. ¿En qué te ayudo?' }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id, status: complete ? 'completed' : 'incomplete', output: [],
      ...(!complete ? { status_details: { type: 'incomplete', reason: 'max_output_tokens' } } : {}) } }));
  });
  const service = new RealtimeService({ apiKey: 'test-key', conversationOptions: { endpoint: upstream.endpoint } });
  t.after(() => service.close());
  const reply = await service.reply({ deviceId: 'jury-voice', text: 'Hola TINO', outputMode: 'audio' });
  assert.equal(reply.model, REALTIME_MODEL); assert.equal(reply.instruction, 'Acá estoy. ¿En qué te ayudo?');
  assert.equal(reply.audio.mimeType, 'audio/wav'); const wav = Buffer.from(reply.audio.base64, 'base64');
  assert.equal(wav.toString('ascii', 0, 4), 'RIFF'); assert.equal(wav.readUInt32LE(24), 24000);
  assert.equal(wav.readUInt16LE(22), 1); assert.equal(wav.readUInt16LE(34), 16); assert.deepEqual(wav.subarray(44), pcm);
  assert.deepEqual(upstream.messages.find(event => event.type === 'session.update').session.output_modalities, ['audio']);
  assert.equal(service.active.size, 0);
});

test('an HTTP voice turn cannot claim speech when the provider only returned text', async t => {
  const upstream = await fakeOpenAi(t);
  const service = new RealtimeService({ apiKey: 'test-key', conversationOptions: { endpoint: upstream.endpoint } });
  t.after(() => service.close());
  await assert.rejects(service.reply({ deviceId: 'jury-voice-empty', text: 'Hola TINO', outputMode: 'audio' }), error => error.code === 'voice_output_unavailable');
  assert.equal(service.active.size, 0);
});

test('a partially generated voice reply is rejected when the provider did not complete it', async t => {
  const upstream = await fakeOpenAi(t, (socket, event) => {
    const id = 'cut-off-voice';
    socket.send(JSON.stringify({ type: 'response.created', response: { id, metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: id, item_id: 'partial-item', delta: Buffer.alloc(4800).toString('base64') }));
    socket.send(JSON.stringify({ type: 'response.output_audio_transcript.done', response_id: id, transcript: 'Abrí Gmail y después...' }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id, status: 'incomplete', status_details: { reason: 'max_output_tokens' }, output: [] } }));
  });
  const service = new RealtimeService({ apiKey: 'test-key', conversationOptions: { endpoint: upstream.endpoint } });
  t.after(() => service.close());
  await assert.rejects(service.reply({ deviceId: 'jury-voice-truncated', text: 'Quiero mandar un correo.', outputMode: 'audio' }), error => error.code === 'openai_response_incomplete');
  assert.equal(service.active.size, 0);
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

test('a passive practice screen never replaces greeting audio with a task or generates its own turn', async t => {
  const upstream = await fakeOpenAi(t);
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint }); t.after(() => conversation.close());
  const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'screen.context', requestGuidance: false, screen: {
    id: 'settings-greeting', packageName: 'org.nieto.assistant.tino', width: 1080, height: 1920,
    controls: [{ id: 'practice', label: 'Probar WhatsApp', role: 'button', bounds: [20, 20, 500, 120] }],
  } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(upstream.messages.some(event => event.type === 'response.create'), false);
  const fixture = await readFile(new URL('../../../apps/android/app/src/androidTest/assets/wake-positive.pcm', import.meta.url));
  conversation.handleClient({ type: 'audio.start' });
  for (let index = 0; index < fixture.length; index += MAX_AUDIO_CHUNK) conversation.handleClient({ type: 'audio.append', audio: fixture.subarray(index, index + MAX_AUDIO_CHUNK).toString('base64') });
  conversation.handleClient({ type: 'audio.commit' });
  await output.wait(event => event.type === 'response.done');
  const forwarded = upstream.messages.filter(event => event.type === 'input_audio_buffer.append').map(event => Buffer.from(event.audio, 'base64'));
  assert.deepEqual(Buffer.concat(forwarded), fixture);
  assert.equal(upstream.messages.filter(event => event.type === 'response.create').length, 1);
  const userItems = upstream.messages.filter(event => event.type === 'conversation.item.create' && event.item.role === 'user');
  assert.equal(userItems.length, 1);
  assert.equal(conversation.taskContext, null);
  assert.equal(output.seen.some(event => event.type === 'guidance.step'), false);
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
  assert.equal(upstream.messages.filter(event => event.type === 'session.update').every(event =>
    event.session.tools.length === 2 && event.session.tools.some(tool => tool.name === 'point_to_control') && event.session.tools.some(tool => tool.name === 'set_task_context')), true);
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
  const { base, device, dataFile } = await apiFixture(t, realtime);
  const status = await new Promise((resolve, reject) => {
    const req = httpRequest(base + '/api/assistant/realtime', { headers: { Connection: 'Upgrade', Upgrade: 'websocket', 'Sec-WebSocket-Version': '13', 'Sec-WebSocket-Key': 'bad', Authorization: `Bearer ${device.token}` } }, response => { response.resume(); response.once('end', () => resolve(response.statusCode)); });
    req.once('error', reject); req.end();
  });
  assert.equal(status, 400); await new Promise(resolve => setImmediate(resolve)); assert.equal(realtime.active.size, 0); assert.equal(upstream.requests.length, 0);
  assert.equal(JSON.parse(await readFile(dataFile, 'utf8')).realtimeUsage, undefined);
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
  for (let index = 0; index < 180; index++) { conversation.handleClient({ type: 'audio.append', audio: chunk }); if(index%10===0) await new Promise(resolve=>setTimeout(resolve,1)); }
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
  const closed = once(first, 'closed'); await first.connect();
  const [details] = await closed; assert.equal(details.reason, 'idle'); assert.equal(details.renewable, false); assert.equal(realtime.active.size, 0);
  const second = realtime.reserve('device-two'); second.close(); assert.equal(realtime.active.size, 0);
});

test('a response timeout closes its provider connection without retrying', async t => {
  const upstream = await fakeOpenAi(t, () => {});
  const realtime = new RealtimeService({ apiKey: 'test-key', conversationOptions: { endpoint: upstream.endpoint, responseTimeoutMs: 25 } });
  await assert.rejects(realtime.reply({ deviceId: 'timeout-device', text: 'Hola' }), error => error.code === 'openai_timeout');
  assert.equal(upstream.requests.length, 1); assert.equal(realtime.active.size, 0);
});


test('curated group and email journeys remain available without opening an AI connection',async t=>{
 const upstream=await fakeOpenAi(t);const realtime=new RealtimeService({apiKey:'test-key',conversationOptions:{endpoint:upstream.endpoint}});
 const {request,device}=await apiFixture(t,realtime);
 const send=text=>request('/assistant/message',{token:device.token,body:{text,conversationId:'guided-demo'}});
 assert.equal((await send('Quiero crear un grupo de WhatsApp')).body.flowId,'whatsapp-group');
 assert.equal((await send('Quiero enviar un correo')).body.clearEmailDraft,true);
 await send('persona@example.com');await send('Asunto sintético');
 const result=await send('Mensaje sintético para probar el borrador.');
 assert.equal(result.body.flowId,'email-send');assert.equal(result.body.emailDraft.to,'persona@example.com');
 assert.equal(upstream.requests.length,0);assert.equal(realtime.active.size,0);
});

test('visual assistance advances from launcher to WhatsApp using a fresh control from each screen',async t=>{
 let round=0;
 const upstream=await fakeOpenAi(t,(socket,event,helper)=>{
  ++round;
  if(round%2===0){helper.sendResponse(socket,event);return;}
  const first=round===1;
  const args={screenId:first?'launcher_screen':'whatsapp_screen',controlId:first?'whatsapp_icon':'new_group',instruction:first?'Tocá WhatsApp.':'Tocá Nuevo grupo.'};
  const responseId=`visual-${round}`;
  socket.send(JSON.stringify({type:'response.created',response:{id:responseId,metadata:event.response.metadata}}));
  socket.send(JSON.stringify({type:'response.done',response:{id:responseId,status:'completed',output:[{type:'function_call',name:'point_to_control',call_id:`point-${round}`,arguments:JSON.stringify(args)}]}}));
 });
 const conversation=new RealtimeConversation({apiKey:'test-key',endpoint:upstream.endpoint,mode:'text'});t.after(()=>conversation.close());
 const output=eventsFrom(conversation);await conversation.connect();
 conversation.handleClient({type:'screen.context',screen:{id:'launcher_screen',packageName:'org.synthetic.launcher',width:360,height:640,controls:[{id:'whatsapp_icon',label:'WhatsApp',role:'button',bounds:[20,100,120,200]}]}});
 conversation.handleClient({type:'text',text:'Ayudame a crear un grupo de WhatsApp.'});
 const firstDone=await output.wait(e=>e.type==='response.done');const oldItem=conversation.screenItemId;
 // Respect the production snapshot rate limit rather than disabling it in this integration test.
 await new Promise(resolve=>setTimeout(resolve,800));
 conversation.handleClient({type:'screen.context',requestGuidance:true,screen:{id:'whatsapp_screen',packageName:'com.whatsapp',width:360,height:640,controls:[{id:'new_group',label:'Nuevo grupo',role:'button',bounds:[180,50,350,105]}]}});
 await output.wait(e=>e.type==='response.done'&&e.turnId!==firstDone.turnId);
 assert.deepEqual(output.seen.filter(e=>e.type==='guidance.step').map(e=>[e.screenId,e.controlId]),[['launcher_screen','whatsapp_icon'],['whatsapp_screen','new_group']]);
 assert.ok(upstream.messages.some(e=>e.type==='conversation.item.delete'&&e.item_id===oldItem));
 const acknowledgements=upstream.messages.filter(e=>e.item?.type==='function_call_output').map(e=>JSON.parse(e.item.output));
 assert.equal(acknowledgements.length,2);assert.ok(acknowledgements.every(e=>e.pointed===true&&e.executed===false));
});

test('mail visual protocol preserves explicit review before pointing to Send and never executes it',async t=>{
 let round=0;
 const upstream=await fakeOpenAi(t,(socket,event,helper)=>{
  ++round;
  if(round%2===0){helper.sendResponse(socket,event);return;}
  const compose=round===1;
  const args={screenId:compose?'mail_inbox':'mail_draft',controlId:compose?'compose':'send',instruction:compose?'Tocá Redactar.':'Como confirmaste que revisaste todo, podés tocar Enviar cuando quieras.'};
  const id=`mail-${round}`;
  socket.send(JSON.stringify({type:'response.created',response:{id,metadata:event.response.metadata}}));
  socket.send(JSON.stringify({type:'response.done',response:{id,status:'completed',output:[{type:'function_call',name:'point_to_control',call_id:id,arguments:JSON.stringify(args)}]}}));
 });
 const conversation=new RealtimeConversation({apiKey:'test-key',endpoint:upstream.endpoint,mode:'text'});t.after(()=>conversation.close());
 const output=eventsFrom(conversation);await conversation.connect();
 const instructions=upstream.messages.find(e=>e.type==='session.update').session.instructions;
 assert.match(instructions,/Antes de señalar una acción sensible.*confirmación explícita/);
 assert.match(instructions,/campos editables llegan ocultos/);
 assert.match(instructions,/borrador.*sin duplicar pasos/);
 conversation.handleClient({type:'screen.context',screen:{id:'mail_inbox',packageName:'com.google.android.gm',width:360,height:640,controls:[{id:'compose',label:'Redactar',role:'button',bounds:[180,500,350,560]}]}});
 conversation.handleClient({type:'text',text:'Ayudame a mandar un mail paso por paso.'});
 const first=await output.wait(e=>e.type==='response.done');
 assert.deepEqual(output.seen.filter(e=>e.type==='guidance.step').map(e=>e.controlId),['compose']);
 await new Promise(resolve=>setTimeout(resolve,800));
 conversation.handleClient({type:'screen.context',screen:{id:'mail_draft',packageName:'com.google.android.gm',width:360,height:640,controls:[{id:'recipient',label:'Campo de texto',role:'input',bounds:[20,100,340,150]},{id:'subject',label:'Campo de texto',role:'input',bounds:[20,180,340,230]},{id:'body',label:'Campo de texto',role:'input',bounds:[20,250,340,450]},{id:'send',label:'Enviar',role:'button',bounds:[280,20,340,70]}]}});
 // A screen alone does not create a turn or certify any editable value.
 assert.deepEqual(output.seen.filter(e=>e.type==='guidance.step').map(e=>e.controlId),['compose']);
 const confirmation='Ya revisé destinatario, asunto y mensaje. No lleva adjuntos. Quiero enviarlo.';
 conversation.handleClient({type:'text',text:confirmation});
 await output.wait(e=>e.type==='response.done'&&e.turnId!==first.turnId);
 assert.deepEqual(output.seen.filter(e=>e.type==='guidance.step').map(e=>[e.screenId,e.controlId]),[['mail_inbox','compose'],['mail_draft','send']]);
 assert.ok(upstream.messages.some(e=>e.item?.role==='user'&&e.item.content?.[0]?.text===confirmation));
 const acknowledgements=upstream.messages.filter(e=>e.item?.type==='function_call_output').map(e=>JSON.parse(e.item.output));
 assert.ok(acknowledgements.every(e=>e.executed===false));
});

async function eventually(predicate) {
  const deadline = Date.now() + 2000;
  while (!predicate()) { if (Date.now() > deadline) throw new Error('Expected provider event'); await new Promise(resolve => setTimeout(resolve, 5)); }
}

test('written output stays on the same conversation with text modality and no audio events', async t => {
  const upstream = await fakeOpenAi(t); const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint }); t.after(() => conversation.close());
  const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'output.mode', mode: 'text' });
  conversation.handleClient({ type: 'screen.context', screen: screen() });
  conversation.handleClient({ type: 'text', text: 'Respondeme por escrito.' });
  await output.wait(event => event.type === 'response.done');
  assert.deepEqual(upstream.messages.find(event => event.type === 'response.create').response.output_modalities, ['text']);
  assert.equal(output.seen.find(event => event.type === 'response.started').outputMode, 'text');
  assert.equal(output.seen.some(event => event.type === 'audio.delta'), false); assert.equal(upstream.requests.length, 1);
  assert.throws(() => conversation.handleClient({ type: 'audio.start' }), /respuesta por voz/);
});

test('interruption after generation truncates the heard item and deletes later unplayed items; foreign checkpoints cannot change context', async t => {
  const upstream = await fakeOpenAi(t, (socket, event) => {
    socket.send(JSON.stringify({ type: 'response.created', response: { id: 'two-items', metadata: event.response.metadata } }));
    for (const id of ['heard-item', 'unheard-item']) socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: 'two-items', item_id: id, content_index: 0, delta: Buffer.alloc(48_000).toString('base64') }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id: 'two-items', status: 'completed', output: [] } }));
  });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint }); t.after(() => conversation.close());
  const output = eventsFrom(conversation); await conversation.connect(); conversation.handleClient({ type: 'text', text: 'Siguiente.' });
  const done = await output.wait(event => event.type === 'response.done'); assert.equal(conversation.activeTurn, null);
  assert.throws(() => conversation.handleClient({ type: 'cancel', playback: { turnId: done.turnId, itemId: 'not-owned', contentIndex: 0, audioEndMs: 10 } }), /supera/);
  assert.throws(() => conversation.handleClient({ type: 'cancel', playback: { turnId: done.turnId, itemId: 'heard-item', contentIndex: 0, audioEndMs: 1001 } }), /supera/);
  conversation.handleClient({ type: 'cancel', playback: { turnId: done.turnId, itemId: 'heard-item', contentIndex: 0, audioEndMs: 500 } });
  await eventually(() => upstream.messages.some(event => event.type === 'conversation.item.delete' && event.item_id === 'unheard-item'));
  assert.deepEqual(upstream.messages.find(event => event.type === 'conversation.item.truncate'), { type: 'conversation.item.truncate', item_id: 'heard-item', content_index: 0, audio_end_ms: 500 });
  assert.equal(upstream.messages.some(event => event.type === 'conversation.item.delete' && event.item_id === 'heard-item'), false);
});

test('audio arriving after cancellation is removed from provider context instead of leaking into the next turn', async t => {
  const upstream = await fakeOpenAi(t, (socket, event) => socket.send(JSON.stringify({ type: 'response.created', response: { id: 'late-audio', metadata: event.response.metadata } })));
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint }); t.after(() => conversation.close());
  const output = eventsFrom(conversation); await conversation.connect(); conversation.handleClient({ type: 'text', text: 'Interrumpir.' });
  await eventually(() => conversation.responses.has('late-audio')); conversation.handleClient({ type: 'cancel' });
  const socket = [...upstream.sockets][0];
  socket.send(JSON.stringify({ type: 'response.output_item.added', response_id: 'late-audio', item: { type: 'message', id: 'late-item' } }));
  socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: 'late-audio', item_id: 'late-item', content_index: 0, delta: Buffer.alloc(4800).toString('base64') }));
  await eventually(() => upstream.messages.some(event => event.type === 'conversation.item.delete' && event.item_id === 'late-item'));
  assert.equal(output.seen.some(event => event.type === 'audio.delta'), false);
});

test('fully drained playback remains in task context when the next turn starts', async t => {
  let number = 0;
  const upstream = await fakeOpenAi(t, (socket, event) => {
    const id = `played-response-${++number}`;
    socket.send(JSON.stringify({ type: 'response.created', response: { id, metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: id, item_id: `played-item-${number}`, content_index: 0, delta: Buffer.alloc(4800).toString('base64') }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id, status: 'completed', output: [] } }));
  });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint }); t.after(() => conversation.close());
  const output = eventsFrom(conversation); await conversation.connect(); conversation.handleClient({ type: 'text', text: 'Primero.' });
  const done = await output.wait(event => event.type === 'response.done');
  conversation.handleClient({ type: 'playback.complete', turnId: done.turnId });
  conversation.handleClient({ type: 'text', text: 'Continuar.' });
  await eventually(() => number === 2);
  assert.equal(upstream.messages.some(event => event.type === 'conversation.item.delete' && event.item_id === 'played-item-1'), false);
  assert.equal(upstream.messages.some(event => event.type === 'conversation.item.truncate'), false);
});

test('quiet screen changes after generation retain pending playback and never delete or truncate its audio', async t => {
  const upstream = await fakeOpenAi(t, (socket, event) => {
    socket.send(JSON.stringify({ type: 'response.created', response: { id: 'screen-change-response', metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: 'screen-change-response', item_id: 'screen-change-audio', content_index: 0, delta: Buffer.alloc(4800).toString('base64') }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id: 'screen-change-response', status: 'completed', output: [] } }));
  });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint }); t.after(() => conversation.close());
  const output = eventsFrom(conversation); await conversation.connect(); conversation.handleClient({ type: 'text', text: 'Actualizar.' });
  const done = await output.wait(event => event.type === 'response.done');
  conversation.handleClient({ type: 'screen.clear' });
  conversation.handleClient({ type: 'screen.context', screen: screen(), requestGuidance: false });
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(conversation.playbackTurn, done.turnId); assert.equal(conversation.turnState.get(done.turnId).cancelled, false);
  assert.equal(upstream.messages.some(event => event.type === 'response.cancel' || event.type === 'conversation.item.truncate' || event.type === 'conversation.item.delete' && event.item_id === 'screen-change-audio'), false);
  assert.equal(output.seen.some(event => event.type === 'cancelled'), false);
  conversation.handleClient({ type: 'playback.complete', turnId: done.turnId }); assert.equal(conversation.playbackTurn, null);
});

test('a requested general answer continues generating voice across passive snapshots and screen clearing', async t => {
  let finish;
  const upstream = await fakeOpenAi(t, (socket, event) => {
    const id = 'free-conversation'; socket.send(JSON.stringify({ type: 'response.created', response: { id, metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: id, item_id: 'free-audio', content_index: 0, delta: Buffer.alloc(4800).toString('base64') }));
    finish = () => {
      socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: id, item_id: 'free-audio', content_index: 0, delta: Buffer.alloc(4800).toString('base64') }));
      socket.send(JSON.stringify({ type: 'response.output_audio_transcript.done', response_id: id, transcript: 'Sí, te escucho.' }));
      socket.send(JSON.stringify({ type: 'response.done', response: { id, status: 'completed', output: [] } }));
    };
  });
  let clock = Date.now(); const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, now: () => clock });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'screen.context', screen: screen(), requestGuidance: false });
  conversation.handleClient({ type: 'text', text: '¿Estás ahí?' }); await output.wait(event => event.type === 'audio.delta');
  const turn = conversation.activeTurn; clock += 751;
  conversation.handleClient({ type: 'screen.context', screen: { ...screen(), id: 'incoming_notification', packageName: 'org.example.notifications' }, requestGuidance: false });
  conversation.handleClient({ type: 'screen.clear' });
  assert.equal(conversation.activeTurn, turn); assert.equal(conversation.playbackTurn, turn); assert.equal(conversation.turnState.get(turn).cancelled, false);
  finish(); const done = await output.wait(event => event.type === 'response.done');
  assert.equal(done.status, 'completed'); assert.equal(done.turnId, turn); assert.equal(output.seen.filter(event => event.type === 'audio.delta').length, 2);
  assert.equal(output.seen.find(event => event.type === 'text.done').text, 'Sí, te escucho.');
  assert.equal(upstream.messages.some(event => event.type === 'response.cancel' || event.type === 'conversation.item.truncate' || event.type === 'conversation.item.delete' && event.item_id === 'free-audio'), false);
  assert.equal(upstream.messages.filter(event => event.type === 'response.create').length, 1); assert.equal(output.seen.some(event => event.type === 'cancelled'), false);
});

test('a quietly replaced snapshot invalidates an old pointer without cancelling its active response', async t => {
  let finishFirst; let requests = 0;
  const upstream = await fakeOpenAi(t, (socket, event, helper) => {
    if (++requests > 1) { helper.sendResponse(socket, event); return; }
    socket.send(JSON.stringify({ type: 'response.created', response: { id: 'old-pointer-response', metadata: event.response.metadata } }));
    finishFirst = () => socket.send(JSON.stringify({ type: 'response.done', response: { id: 'old-pointer-response', status: 'completed', output: [
      { type: 'function_call', name: 'point_to_control', call_id: 'old-pointer', arguments: JSON.stringify({ screenId: 'screen_1', controlId: 'search', instruction: 'Tocá Buscar.' }) },
    ] } }));
  });
  let clock = Date.now(); const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, mode: 'text', now: () => clock });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'screen.context', screen: screen(), requestGuidance: false }); conversation.handleClient({ type: 'text', text: 'Ayudame a encontrar la opción.' });
  await eventually(() => Boolean(finishFirst)); const turn = conversation.activeTurn; clock += 751;
  conversation.handleClient({ type: 'screen.context', screen: { ...screen(), id: 'fresh_screen' }, requestGuidance: false });
  assert.equal(conversation.activeTurn, turn); finishFirst(); await output.wait(event => event.type === 'response.done');
  assert.equal(output.seen.some(event => event.type === 'guidance.step' || event.type === 'cancelled'), false);
  const toolResult = upstream.messages.find(event => event.item?.type === 'function_call_output'); assert.equal(JSON.parse(toolResult.item.output).error, 'stale_or_unknown_control');
  assert.equal(upstream.messages.some(event => event.type === 'response.cancel'), false); assert.equal(conversation.turnState.get(turn).cancelled, false);
});

test('an explicit guidance request can replace a response while quiet observations cannot', async t => {
  let requests = 0;
  const upstream = await fakeOpenAi(t, (socket, event, helper) => {
    if (++requests === 1) socket.send(JSON.stringify({ type: 'response.created', response: { id: 'replaced-response', metadata: event.response.metadata } }));
    else helper.sendResponse(socket, event);
  });
  let clock = Date.now(); const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, mode: 'text', now: () => clock });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'text', text: 'Ayudame con esta tarea.' }); await eventually(() => requests === 1); const original = conversation.activeTurn;
  conversation.handleClient({ type: 'screen.context', screen: screen(), requestGuidance: false }); assert.equal(conversation.activeTurn, original);
  clock += 751; conversation.handleClient({ type: 'screen.context', screen: { ...screen(), id: 'explicit_step' }, requestGuidance: true });
  assert.notEqual(conversation.activeTurn, original); assert.equal(conversation.turnState.get(original).cancelled, true);
  const done = await output.wait(event => event.type === 'response.done'); assert.equal(done.status, 'completed');
  assert.ok(upstream.messages.some(event => event.type === 'response.cancel')); assert.equal(output.seen.filter(event => event.type === 'cancelled').length, 1);
});

test('operator continuous assistance accepts more than twenty turns and sixty snapshots with bounded turn memory', async t => {
  const upstream = await fakeOpenAi(t); let clock = Date.now();
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, mode: 'text', continuous: true, now: () => clock });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  assert.equal(output.seen.find(event => event.type === 'ready').session.renewable, true);
  for (let index = 0; index < 40; index++) {
    conversation.handleClient({ type: 'text', text: `Consulta general ${index}` });
    const current = conversation.activeTurn;
    await output.wait(event => event.type === 'response.done' && event.turnId === current);
  }
  assert.ok(conversation.turnState.size <= 8); assert.ok(conversation.responses.size <= 8);
  const responsesBeforeScreens = upstream.messages.filter(event => event.type === 'response.create').length;
  for (let index = 0; index < 80; index++) {
    clock += 751; conversation.handleClient({ type: 'screen.context', screen: { ...screen(), id: `screen_${index}` }, requestGuidance: false });
  }
  assert.equal(conversation.screen.id, 'screen_79'); assert.equal(conversation.snapshotCount, 80);
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(upstream.messages.filter(event => event.type === 'response.create').length, responsesBeforeScreens);
  assert.equal(output.seen.some(event => event.type === 'error'), false);
});

test('continuous transport announces renewal and closes normally without clearing the client task summary', async t => {
  const upstream = await fakeOpenAi(t);
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, mode: 'text', continuous: true,
    maxSessionMs: 150, renewAfterMs: 75, idleTimeoutMs: 1000 });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); const closed = once(conversation, 'closed');
  await conversation.connect();
  conversation.handleClient({ type: 'resume.context', context: { goal: 'Crear una cuenta de correo', targetApp: 'Gmail', progress: 'La persona está en el inicio del teléfono.', expectsReply: true } });
  const clientSummary = output.seen.find(event => event.type === 'task.context').context;
  assert.equal(clientSummary.expectsReply, false);
  await output.wait(event => event.type === 'renewal.required');
  const [details] = await closed;
  assert.equal(details.renewable, true); assert.equal(conversation.taskContext, null);
  assert.equal(clientSummary.goal, 'Crear una cuenta de correo');
  assert.equal(output.seen.some(event => event.type === 'error'), false);
});

test('task context survives app changes and transport renewal as untrusted data without reusing a prior screen control', async t => {
  let round = 0;
  const task = { goal: 'Crear una cuenta de correo', targetApp: 'Gmail', progress: 'La persona pidió salir de WhatsApp para abrir Gmail.', expectsReply: true };
  const upstream = await fakeOpenAi(t, (socket, event, helper) => {
    if (++round === 1) {
      socket.send(JSON.stringify({ type: 'response.created', response: { id: 'task-response', metadata: event.response.metadata } }));
      socket.send(JSON.stringify({ type: 'response.done', response: { id: 'task-response', status: 'completed', output: [
        { type: 'function_call', name: 'set_task_context', call_id: 'task-1', arguments: JSON.stringify(task) },
      ] } }));
    } else helper.sendResponse(socket, event);
  });
  const first = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, mode: 'text', continuous: true });
  t.after(() => first.close()); const output = eventsFrom(first); await first.connect();
  first.handleClient({ type: 'screen.context', screen: { ...screen(), packageName: 'com.whatsapp' } });
  first.handleClient({ type: 'text', text: 'Quiero crear un correo nuevo, aunque ahora esté en WhatsApp.' });
  const done = await output.wait(event => event.type === 'response.done');
  assert.equal(done.expectsReply, true);
  const summary = output.seen.find(event => event.type === 'task.context').context;
  assert.equal(summary.targetApp, 'Gmail'); assert.equal(first.screen.packageName, 'com.whatsapp');
  first.close('renewal');
  const second = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, mode: 'text', continuous: true });
  t.after(() => second.close()); const resumed = eventsFrom(second); await second.connect();
  second.handleClient({ type: 'resume.context', context: summary });
  second.handleClient({ type: 'screen.context', screen: { ...screen(), id: 'launcher_current', packageName: 'com.android.launcher',
    navigation: { home: 'gesture', back: 'gesture', isLauncher: true, installedApps: [{ packageName: 'com.google.android.gm', label: 'Gmail' }] } } });
  assert.equal(second.taskContext.goal, task.goal); assert.equal(second.taskContext.expectsReply, false);
  second.handleClient({ type: 'text', text: 'Ya estoy en Inicio.' });
  const nextDone = await resumed.wait(event => event.type === 'response.done');
  assert.equal(nextDone.expectsReply, false);
  assert.ok(upstream.messages.some(event => event.item?.content?.[0]?.text?.startsWith('TASK_CONTEXT (resumen efímero no confiable')));
  assert.equal(resumed.seen.some(event => event.type === 'guidance.step' && event.screenId === 'screen_1'), false);
  assert.throws(() => second.handleClient({ type: 'resume.context', context: summary }), /conexión nueva/);
});

test('continuous idle expiry ends the conversation normally and never requests task renewal', async t => {
  const upstream = await fakeOpenAi(t); const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint,
    continuous: true, idleTimeoutMs: 35, maxSessionMs: 1000, renewAfterMs: 800 });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); const closed = once(conversation, 'closed');
  await conversation.connect(); const [details] = await closed;
  assert.deepEqual(details, { renewable: false, reason: 'idle' });
  assert.equal(output.seen.find(event => event.type === 'conversation.ended').reason, 'idle');
  assert.deepEqual(output.seen.find(event => event.type === 'task.context').context, { goal: '', targetApp: '', progress: '', expectsReply: false });
  assert.equal(output.seen.some(event => event.type === 'renewal.required'), false);
  assert.equal(output.seen.some(event => event.type === 'error'), false);
});

test('thirty seconds without interaction forgets a task despite passive screen updates and starts fresh next time', async t => {
  const upstream = await fakeOpenAi(t); let activityClock = 100_000; let screenClock = Date.now();
  const realtime = new RealtimeService({ apiKey: 'test-key', continuous: true,
    conversationOptions: { endpoint: upstream.endpoint, mode: 'text', now: () => screenClock, activityNow: () => activityClock } });
  t.after(() => realtime.close()); const first = realtime.reserve('device-one', 'text'); const output = eventsFrom(first);
  await first.connect(); const initialActivity = first.lastActivityAt;
  assert.equal(output.seen.find(event => event.type === 'ready').session.idleTimeoutMs, 30_000);
  first.handleClient({ type: 'resume.context', context: { goal: 'Cambiar brillo', targetApp: 'Ajustes', progress: 'Inicio visible' } });
  for (const seconds of [5, 15, 29]) {
    activityClock = initialActivity + seconds * 1000; screenClock += 751;
    first.handleClient({ type: 'screen.context', screen: { ...screen(), id: `quiet_${seconds}` }, requestGuidance: false });
    first.handleClient({ type: 'screen.clear' }); first.handleClient({ type: 'output.mode', mode: 'text' });
    assert.throws(() => first.handleClient({ type: 'ping' }), /no está habilitado/);
    first.scheduleIdle(); assert.equal(first.closed, false); assert.equal(first.lastActivityAt, initialActivity);
  }
  activityClock = initialActivity + 30_000; first.scheduleIdle();
  assert.equal(first.closed, true); assert.equal(first.taskContext, null); assert.equal(first.taskContextItemId, null);
  assert.equal(first.screen, null); assert.equal(first.turnState.size, 0); assert.equal(realtime.active.size, 0);
  assert.equal(output.seen.find(event => event.type === 'conversation.ended').reason, 'idle');
  assert.equal(output.seen.some(event => event.type === 'renewal.required'), false);
  assert.deepEqual(output.seen.at(-2).context, { goal: '', targetApp: '', progress: '', expectsReply: false });
  const fresh = realtime.reserve('device-one', 'text'); const nextOutput = eventsFrom(fresh); await fresh.connect();
  fresh.handleClient({ type: 'text', text: 'Hola, consulta nueva.' }); await nextOutput.wait(event => event.type === 'response.done');
  assert.equal(fresh.taskContext, null);
  assert.equal(upstream.connections.at(-1).messages.some(event => event.item?.content?.some(item => item.text?.includes('TASK_CONTEXT'))), false);
});

test('ending a conversation forgets its summary and active capture without a renewable close', async t => {
  const upstream = await fakeOpenAi(t); const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, continuous: true });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'resume.context', context: { goal: 'Revisar una opción', targetApp: 'Ajustes', progress: 'Sin completar' } });
  conversation.handleClient({ type: 'audio.start' }); conversation.handleClient({ type: 'audio.append', audio: Buffer.alloc(4800).toString('base64') });
  const closed = once(conversation, 'closed'); conversation.handleClient({ type: 'close' });
  assert.deepEqual((await closed)[0], { renewable: false, reason: 'user_end' });
  assert.equal(conversation.taskContext, null); assert.equal(conversation.recording, false); assert.equal(conversation.inputBytes, 0);
  assert.equal(output.seen.find(event => event.type === 'conversation.ended').reason, 'user_end');
  assert.deepEqual(output.seen.filter(event => event.type === 'task.context').at(-1).context, { goal: '', targetApp: '', progress: '', expectsReply: false });
  assert.equal(output.seen.some(event => event.type === 'response.started' || event.type === 'renewal.required'), false);
});

test('real screen actions extend only an active conversation, never create a response and cannot revive an expired task', async t => {
  let activityClock = 100_000; let screenClock = Date.now();
  const upstream = await fakeOpenAi(t);
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, mode: 'text', continuous: true,
    activityNow: () => activityClock, now: () => screenClock });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  const initial = conversation.lastActivityAt; activityClock += 5_000;
  conversation.handleClient({ type: 'user.activity', kind: 'tap' }); assert.equal(conversation.lastActivityAt, initial);
  assert.equal(conversation.turns, 0); assert.equal(output.seen.some(event => event.type === 'response.started'), false);
  conversation.handleClient({ type: 'text', text: 'Ayudame a ajustar esta opción.' }); await output.wait(event => event.type === 'response.done');
  const responseCount = upstream.messages.filter(event => event.type === 'response.create').length;
  for (const kind of ['tap', 'scroll', 'text', 'navigation']) {
    activityClock += 29_000; conversation.handleClient({ type: 'user.activity', kind });
    const actionAt = activityClock; assert.equal(conversation.lastActivityAt, actionAt);
    screenClock += 751; conversation.handleClient({ type: 'screen.context', screen: { ...screen(), id: `after_${kind}` }, requestGuidance: false });
    assert.equal(conversation.lastActivityAt, actionAt); assert.equal(conversation.closed, false);
  }
  const previous = conversation.lastActivityAt;
  for (const event of [{ type: 'user.activity', kind: 'ping' }, { type: 'user.activity' }, { type: 'user.activity', kind: 'text', text: 'PRIVATE_FIELD_VALUE' }]) {
    assert.throws(() => conversation.handleClient(event), error => error.code === 'invalid_realtime_message' && !error.message.includes('PRIVATE_FIELD_VALUE'));
  }
  activityClock += 100; conversation.handleClient({ type: 'user.activity', kind: 'tap' }); assert.equal(conversation.lastActivityAt, previous + 100);
  activityClock += 200; conversation.handleClient({ type: 'user.activity', kind: 'scroll' }); assert.equal(conversation.lastActivityAt, activityClock);
  await new Promise(resolve => setTimeout(resolve, 10));
  assert.equal(upstream.messages.filter(event => event.type === 'response.create').length, responseCount);
  assert.equal(upstream.messages.some(event => event.type === 'user.activity'), false);
  activityClock += 30_000; conversation.handleClient({ type: 'user.activity', kind: 'navigation' });
  assert.equal(conversation.closed, true); assert.equal(output.seen.find(event => event.type === 'conversation.ended').reason, 'idle');
});

test('a cleared model task takes priority over other tools and ends without another response, pointer or late audio', async t => {
  for (const endReason of ['task_completed', 'user_end']) {
    await t.test(endReason, async sub => {
      const upstream = await fakeOpenAi(sub, (socket, event) => {
        const id = `terminal_${endReason}`;
        socket.send(JSON.stringify({ type: 'response.created', response: { id, metadata: event.response.metadata } }));
        socket.send(JSON.stringify({ type: 'response.done', response: { id, status: 'completed', output: [
          { type: 'function_call', name: 'point_to_control', call_id: 'point-before-end', arguments: JSON.stringify({ screenId: screen().id, controlId: screen().controls[0].id, instruction: 'Paso que no debe publicarse.' }) },
          { type: 'function_call', name: 'set_task_context', call_id: 'end-task', arguments: JSON.stringify({ goal: '', targetApp: '', progress: '', expectsReply: false, endReason }) },
        ] } }));
        socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: id, item_id: 'late-end-audio', delta: Buffer.alloc(4800).toString('base64') }));
      });
      const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, continuous: true });
      sub.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
      conversation.handleClient({ type: 'screen.context', screen: screen(), requestGuidance: false });
      const closed = once(conversation, 'closed'); conversation.handleClient({ type: 'text', text: endReason === 'user_end' ? 'Preferiría seguir sin ayuda, dejemos la conversación acá.' : 'La tarea ya está terminada.' });
      const [details] = await closed; assert.deepEqual(details, { renewable: false, reason: endReason });
      const countAtEnd = output.seen.length;
      conversation.handleUpstream(Buffer.from(JSON.stringify({ type: 'response.output_text.delta', response_id: `terminal_${endReason}`, delta: 'No debe oírse ni verse.' })));
      await new Promise(resolve => setTimeout(resolve, 10));
      assert.equal(output.seen.length, countAtEnd); assert.equal(conversation.taskContext, null);
      assert.equal(output.seen.find(event => event.type === 'conversation.ended').reason, endReason);
      assert.equal(output.seen.some(event => ['guidance.step', 'audio.delta', 'text.delta', 'text.done', 'response.done'].includes(event.type)), false);
      assert.equal(upstream.messages.filter(event => event.type === 'response.create').length, 1);
      assert.equal(upstream.messages.some(event => event.item?.type === 'function_call_output'), false);
      assert.throws(() => conversation.handleClient({ type: 'text', text: 'Evento tardío' }), /lista/);
    });
  }
});

test('terminal streamed function arguments close immediately before response.done or any following output', async t => {
  const upstream = await fakeOpenAi(t, (socket, event) => {
    const id = 'streamed-end'; socket.send(JSON.stringify({ type: 'response.created', response: { id, metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.function_call_arguments.done', response_id: id, item_id: 'fc-end', call_id: 'call-end', name: 'set_task_context',
      arguments: JSON.stringify({ goal: '', targetApp: '', progress: '', endReason: 'user_end' }) }));
    socket.send(JSON.stringify({ type: 'response.output_text.delta', response_id: id, delta: 'Una despedida no solicitada.' }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id, status: 'completed', output: [] } }));
  });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, continuous: true });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  const closed = once(conversation, 'closed'); conversation.handleClient({ type: 'text', text: 'No quiero más acompañamiento por ahora.' }); await closed;
  assert.equal(output.seen.find(event => event.type === 'conversation.ended').reason, 'user_end');
  assert.equal(output.seen.some(event => ['audio.delta', 'text.delta', 'response.done'].includes(event.type)), false);
});

test('completing one intermediate step retains the overall goal and can request the next step', async t => {
  let round = 0;
  const upstream = await fakeOpenAi(t, (socket, event, helper) => {
    if (++round === 1) {
      socket.send(JSON.stringify({ type: 'response.created', response: { id: 'intermediate-step', metadata: event.response.metadata } }));
      socket.send(JSON.stringify({ type: 'response.done', response: { id: 'intermediate-step', status: 'completed', output: [
        { type: 'function_call', name: 'set_task_context', call_id: 'step-update', arguments: JSON.stringify({ goal: 'Completar la configuración elegida', targetApp: 'Ajustes', progress: 'La persona completó el primer paso; todavía debe revisar y confirmar la opción final.' }) },
      ] } }));
    } else helper.sendResponse(socket, event);
  });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, mode: 'text', continuous: true });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'text', text: 'Listo, ya hice este paso.' }); await output.wait(event => event.type === 'response.done');
  assert.equal(conversation.closed, false); assert.equal(conversation.taskContext.goal, 'Completar la configuración elegida');
  assert.equal(output.seen.some(event => event.type === 'conversation.ended'), false);
  const prompt = upstream.messages.find(event => event.type === 'session.update').session.instructions;
  assert.match(prompt, /Interpretá semánticamente/); assert.match(prompt, /paso intermedio/); assert.match(prompt, /única salida/);
});

test('HTTP end requests complete silently instead of hanging or asking the model for a farewell', async t => {
  const upstream = await fakeOpenAi(t, (socket, event) => {
    socket.send(JSON.stringify({ type: 'response.created', response: { id: 'http-end', metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id: 'http-end', status: 'completed', output: [
      { type: 'function_call', name: 'set_task_context', call_id: 'http-end-task', arguments: JSON.stringify({ goal: '', targetApp: '', progress: '', endReason: 'user_end' }) },
    ] } }));
  });
  const service = new RealtimeService({ apiKey: 'test-key', continuous: true, conversationOptions: { endpoint: upstream.endpoint } }); t.after(() => service.close());
  const reply = await service.reply({ deviceId: 'end-http', text: 'Prefiero terminar acá.' });
  assert.deepEqual(reply, { mode: 'conversation', instruction: '', sources: [], model: REALTIME_MODEL, ended: true, reason: 'user_end' });
  assert.equal(service.active.size, 0); assert.equal(upstream.messages.filter(event => event.type === 'response.create').length, 1);
});

test('a screen action can finish a task silently in an isolated probe without counting a user turn or extending idle', async t => {
  const upstream = await fakeOpenAi(t, (socket, event) => {
    assert.equal(event.response.conversation, 'none');
    const id = 'isolated-completion'; socket.send(JSON.stringify({ type: 'response.created', response: { id, metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.output_text.delta', response_id: id, delta: 'Salida interna que no debe publicarse.' }));
    socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: id, delta: Buffer.alloc(4800).toString('base64') }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id, status: 'completed', output: [
      { type: 'function_call', name: 'report_task_completion', arguments: JSON.stringify({ completed: true, screenId: 'fixture_done', evidenceControlIds: ['status_done'] }) },
    ] } }));
  });
  let screenClock = Date.now(); let activityClock = 100_000;
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, continuous: true, now: () => screenClock,
    activityNow: () => activityClock, completionProbeOptions: { debounceMs: 15 } });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'resume.context', context: { goal: 'Finalizar la operación de prueba', targetApp: 'Prueba', progress: 'Falta confirmar' } });
  conversation.handleClient({ type: 'screen.context', screen: { ...screen(), id: 'fixture_before', controls: [{ id: 'confirm', label: 'Confirmar operación de prueba', role: 'button', bounds: [0, 0, 100, 50] }] } });
  activityClock += 1000; conversation.handleClient({ type: 'user.activity', kind: 'tap' }); const actionAt = conversation.lastActivityAt;
  screenClock += 751;
  const closed = once(conversation, 'closed');
  conversation.handleClient({ type: 'screen.context', screen: { ...screen(), id: 'fixture_done', controls: [{ id: 'status_done', label: 'Operación de prueba completada', role: 'label', bounds: [0, 0, 100, 50] }] } });
  const [details] = await closed;
  assert.deepEqual(details, { renewable: false, reason: 'task_completed' });
  assert.equal(conversation.lastActivityAt, actionAt); assert.equal(conversation.turns, 0);
  assert.equal(output.seen.some(event => ['text.delta', 'audio.delta', 'response.started', 'response.done'].includes(event.type)), false);
  assert.equal(upstream.messages.filter(event => event.type === 'response.create').length, 1);
});

test('a user request cancels an unacknowledged probe and late probe metadata cannot steal its response', async t => {
  let lateProbe; let probeSocket;
  const upstream = await fakeOpenAi(t, (socket, event, helper) => {
    if (event.response.metadata.nieto_probe_id) { lateProbe = event; probeSocket = socket; return; }
    // The old OOB creation arrives first while the real turn is pending.
    socket.send(JSON.stringify({ type: 'response.created', response: { id: 'cancelled-probe', metadata: lateProbe.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.output_text.delta', response_id: 'cancelled-probe', delta: 'Probe no divulgado.' }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id: 'cancelled-probe', status: 'completed', output: [
      { type: 'function_call', name: 'report_task_completion', arguments: JSON.stringify({ completed: true, screenId: 'changed_after_action', evidenceControlIds: ['c1'] }) },
    ] } }));
    helper.sendResponse(socket, event);
  });
  let screenClock = Date.now();
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, mode: 'text', continuous: true,
    now: () => screenClock, completionProbeOptions: { debounceMs: 15 } });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'resume.context', context: { goal: 'Completar una opción', targetApp: 'Ajustes', progress: 'Pendiente' } });
  conversation.handleClient({ type: 'screen.context', screen: screen() });
  conversation.handleClient({ type: 'user.activity', kind: 'tap' }); screenClock += 751;
  conversation.handleClient({ type: 'screen.context', screen: { ...screen(), id: 'changed_after_action', controls: [{ ...screen().controls[0], label: 'Opción cambiada' }] } });
  await eventually(() => Boolean(lateProbe && probeSocket));
  conversation.handleClient({ type: 'text', text: 'Tengo una nueva pregunta.' });
  const done = await output.wait(event => event.type === 'response.done');
  assert.equal(done.status, 'completed'); assert.equal(conversation.closed, false);
  assert.equal(output.seen.find(event => event.type === 'text.done').text, 'Respuesta de prueba.');
  assert.equal(output.seen.some(event => event.type === 'conversation.ended'), false);
  await eventually(() => upstream.messages.some(event => event.type === 'response.cancel' && event.response_id === 'cancelled-probe'));
  assert.equal(conversation.turns, 1); assert.equal(conversation.pendingResponses.length, 0);
});

test('activity accepts a sanitized reduced baseline after reconnection but rejects images, extra fields and invalid controls', async t => {
  const upstream = await fakeOpenAi(t, () => {});
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, continuous: true });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'resume.context', context: { goal: 'Completar la operación de prueba', targetApp: 'Prueba', progress: 'Falta confirmar' } });
  const baseline = { ...screen(), controls: [{ ...screen().controls[0], id: 'private_input', label: 'un valor privado', role: 'input' }] };
  const activityAt = conversation.lastActivityAt;
  for (const invalidBaseline of [null, { ...baseline, image: {} }, { ...baseline, navigation: {} }, { ...baseline, execute: 'tap' }, { ...baseline, controls: [{ ...baseline.controls[0], bounds: [-1, 0, 20, 20] }] }]) {
    assert.throws(() => conversation.handleClient({ type: 'user.activity', kind: 'tap', baseline: invalidBaseline }), error => error.code === 'invalid_realtime_message');
  }
  assert.equal(conversation.lastActivityAt, activityAt); assert.equal(conversation.completionProbe.action, null);
  conversation.handleClient({ type: 'user.activity', kind: 'tap', baseline });
  assert.equal(conversation.screen, null); assert.equal(conversation.completionProbe.action.before.controls[0].label, 'Campo de texto');
  assert.equal(conversation.completionProbe.action.before.controls[0].redacted, true);
  assert.equal(output.seen.some(event => event.type === 'response.started'), false);
});

test('idle expiry waits for active recording and generation, then counts from the completed response', async t => {
  let finishResponse; let activityClock = 100_000;
  const upstream = await fakeOpenAi(t, (socket, event, helper) => { finishResponse = () => helper.sendResponse(socket, event); });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, continuous: true, mode: 'audio', activityNow: () => activityClock });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'audio.start' }); conversation.handleClient({ type: 'audio.append', audio: Buffer.alloc(4800).toString('base64') });
  activityClock += 31_000; conversation.scheduleIdle(); assert.equal(conversation.closed, false); assert.equal(conversation.recording, true);
  conversation.handleClient({ type: 'audio.commit' }); await eventually(() => Boolean(finishResponse));
  activityClock += 31_000; conversation.scheduleIdle(); assert.equal(conversation.closed, false); assert.ok(conversation.activeTurn);
  finishResponse(); await output.wait(event => event.type === 'response.done');
  const completedAt = activityClock;
  activityClock = completedAt + 29_999; conversation.scheduleIdle(); assert.equal(conversation.closed, false);
  activityClock = completedAt + 30_000; conversation.scheduleIdle(); assert.equal(conversation.closed, true);
});

test('idle expiry waits for real playback completion and passive observations do not extend it', async t => {
  let activityClock = 100_000;
  const upstream = await fakeOpenAi(t, (socket, event) => {
    socket.send(JSON.stringify({ type: 'response.created', response: { id: 'idle-audio-response', metadata: event.response.metadata } }));
    socket.send(JSON.stringify({ type: 'response.output_audio.delta', response_id: 'idle-audio-response', item_id: 'idle-audio-item', content_index: 0, delta: Buffer.alloc(4800).toString('base64') }));
    socket.send(JSON.stringify({ type: 'response.done', response: { id: 'idle-audio-response', status: 'completed', output: [] } }));
  });
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, continuous: true, activityNow: () => activityClock });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'text', text: 'Ayudame con esta opción.' }); const done = await output.wait(event => event.type === 'response.done');
  activityClock += 31_000; conversation.scheduleIdle(); assert.equal(conversation.closed, false); assert.equal(conversation.playbackTurn, done.turnId);
  conversation.handleClient({ type: 'playback.complete', turnId: done.turnId });
  const playbackCompletedAt = activityClock;
  activityClock += 29_999; conversation.handleClient({ type: 'output.mode', mode: 'text' }); conversation.scheduleIdle(); assert.equal(conversation.closed, false);
  activityClock = playbackCompletedAt + 30_000; conversation.scheduleIdle(); assert.equal(conversation.closed, true);
});

test('app changes and protected-screen clearing preserve an ongoing capture and remain silent until the spoken turn commits', async t => {
  const upstream = await fakeOpenAi(t); let clock = Date.now();
  const conversation = new RealtimeConversation({ apiKey: 'test-key', endpoint: upstream.endpoint, continuous: true, now: () => clock });
  t.after(() => conversation.close()); const output = eventsFrom(conversation); await conversation.connect();
  conversation.handleClient({ type: 'audio.start' });
  conversation.handleClient({ type: 'audio.append', audio: Buffer.alloc(4800).toString('base64') });
  conversation.handleClient({ type: 'screen.context', screen: { ...screen(), id: 'another_app' }, requestGuidance: true });
  conversation.handleClient({ type: 'screen.clear' });
  clock += 751;
  conversation.handleClient({ type: 'screen.context', screen: { ...screen(), id: 'current_app' }, requestGuidance: false });
  assert.equal(conversation.recording, true); assert.equal(conversation.inputBytes, 4800);
  assert.equal(output.seen.some(event => event.type === 'cancelled'), false);
  assert.equal(output.seen.some(event => event.type === 'response.started'), false);
  conversation.handleClient({ type: 'audio.commit' });
  await output.wait(event => event.type === 'response.done');
  assert.equal(upstream.messages.filter(event => event.type === 'input_audio_buffer.commit').length, 1);
  assert.equal(upstream.messages.filter(event => event.type === 'input_audio_buffer.clear').length, 1);
  assert.equal(upstream.messages.filter(event => event.type === 'response.create').length, 1);
});
