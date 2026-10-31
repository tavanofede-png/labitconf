import test from 'node:test';
import assert from 'node:assert/strict';
import { OpenArgClient, extractEvidence } from '../src/openarg.mjs';
import { normalizeSearch } from '../src/normalize.mjs';

function transport(onCall = () => ({ content: [{ type: 'text', text: 'Fuente: [INDEC](https://www.indec.gob.ar).\nPeríodo: 2022' }] })) {
  const requests = [];
  return { requests, fetch: async (url, opts) => {
    const message = JSON.parse(opts.body); requests.push({ url, ...message, headers: opts.headers });
    if (message.method === 'initialize') return Response.json({ id: message.id, result: { protocolVersion: '2025-03-26', serverInfo: { name: 'openarg' } } }, { headers: { 'Mcp-Session-Id': 'session-123' } });
    if (message.method === 'notifications/initialized') return new Response(null, { status: 202 });
    if (message.method === 'tools/list') return Response.json({ id: message.id, result: { tools: [{ name: 'buscar_datasets' }, { name: 'listar_fuentes' }] } });
    const value = await onCall(message, opts); return value instanceof Response ? value : Response.json({ id: message.id, result: value });
  } };
}

test('MCP lifecycle, session header, source attribution, in-flight consolidation and cache', async () => {
  const fake = transport(); const client = new OpenArgClient({ apiKey: 'test-key', fetchImpl: fake.fetch });
  const [a, b] = await Promise.all([client.call('listar_fuentes'), client.call('listar_fuentes')]);
  assert.equal(a.tool, 'listar_fuentes'); assert.deepEqual(a, b);
  assert.equal(a.sources[0].url, 'https://www.indec.gob.ar/');
  assert.equal(a.period.evidence[0], 'Período: 2022');
  assert.deepEqual(fake.requests.map((r) => r.method), ['initialize', 'notifications/initialized', 'tools/list', 'tools/call']);
  assert.equal(fake.requests[3].headers.Authorization, 'Bearer test-key');
  assert.equal(fake.requests[3].headers['Mcp-Session-Id'], 'session-123');
  assert.equal((await client.call('listar_fuentes')).cached, true);
  assert.equal(fake.requests.length, 4);
  assert.equal(JSON.stringify(client.status()).includes('test-key'), false);
});

test('no credentials makes no tool query and cannot synthesize data', async () => {
  let calls = 0; const client = new OpenArgClient({ apiKey: '', fetchImpl: async () => { calls++; } });
  await assert.rejects(client.call('listar_fuentes'), (e) => e.code === 'credentials_required'); assert.equal(calls, 0);
});

test('HTTP 200 isError is a credential failure, never a successful dataset', async () => {
  const fake = transport(() => ({ isError: true, content: [{ type: 'text', text: 'Falta la clave de OpenArg.' }] }));
  const client = new OpenArgClient({ apiKey: 'invalid-key', fetchImpl: fake.fetch });
  await assert.rejects(client.call('listar_fuentes'), (e) => e.code === 'credentials_required');
  assert.equal(client.cache.size, 0); assert.equal(client.status().status, 'credentials_required');
});

test('SSE stream returns matching id while ignoring progress notifications', async () => {
  const fake = transport((message) => new Response(`data: {"jsonrpc":"2.0","method":"notifications/progress"}\n\ndata: ${JSON.stringify({ id: message.id, result: { content: [{ type: 'text', text: 'Dato real' }] } })}\n\n`, { headers: { 'Content-Type': 'text/event-stream' } }));
  const client = new OpenArgClient({ apiKey: 'key', fetchImpl: fake.fetch });
  assert.equal((await client.call('listar_fuentes')).text, 'Dato real');
});

test('honors explicit short Retry-After exactly once and surfaces quota exhaustion', async () => {
  let count = 0; const sleeps = [];
  const fake = transport(() => ++count === 1 ? new Response('Límite', { status: 429, headers: { 'Retry-After': '2' } }) : new Response('cupo mensual agotado', { status: 402 }));
  const client = new OpenArgClient({ apiKey: 'key', fetchImpl: fake.fetch, sleep: async (ms) => { sleeps.push(ms); } });
  await assert.rejects(client.call('listar_fuentes'), (e) => e.code === 'quota_exhausted');
  assert.equal(count, 2); assert.deepEqual(sleeps, [2000]);
});

test('a network timeout is not automatically retried', async () => {
  const fake = transport((_, opts) => new Promise((resolve, reject) => opts.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true })));
  const client = new OpenArgClient({ apiKey: 'key', fetchImpl: fake.fetch, timeoutMs: 10 });
  await assert.rejects(client.call('listar_fuentes'), (e) => e.code === 'timeout');
  assert.equal(fake.requests.filter((r) => r.method === 'tools/call').length, 1);
});

test('discover clears a failed transport after recovery without requiring a data query', async () => {
  const fake = transport(); let offline = true;
  const client = new OpenArgClient({ apiKey: 'key', fetchImpl: (...args) => offline ? Promise.reject(new Error('offline')) : fake.fetch(...args) });
  const failed = await client.discover(); assert.equal(failed.transportConnected, false); assert.equal(failed.transportError.code, 'unavailable');
  offline = false; const recovered = await client.discover();
  assert.equal(recovered.transportConnected, true); assert.equal(recovered.transportError, null); assert.equal(recovered.lastError, null);
  assert.equal(recovered.queryError, null); assert.equal(recovered.lastSuccessAt, null); assert.equal(recovered.status, 'connected');
  assert.equal(fake.requests.some(request => request.method === 'tools/call'), false);
});

test('successful discovery cannot erase query quota errors and cached values cannot fake query recovery', async () => {
  let quota = false;
  const fake = transport(() => quota ? new Response('cupo mensual agotado', { status: 402 }) : { content: [{ type: 'text', text: 'Dato público' }] });
  const client = new OpenArgClient({ apiKey: 'key', fetchImpl: fake.fetch });
  await client.call('listar_fuentes'); quota = true;
  await assert.rejects(client.call('buscar_datasets', { texto: 'otra consulta' }), error => error.code === 'quota_exhausted');
  let status = await client.discover(); assert.equal(status.transportConnected, true); assert.equal(status.transportError, null); assert.equal(status.queryError.code, 'quota_exhausted');
  assert.equal((await client.call('listar_fuentes')).cached, true); assert.equal(client.status().queryError.code, 'quota_exhausted');
  quota = false; await client.call('listar_fuentes', {}, { refresh: true }); status = client.status();
  assert.equal(status.queryError, null); assert.equal(status.lastError, null);
});

test('a query transport failure is recovered by discovery while preserving independent query error state', async () => {
  const fake = transport(); let offline = false;
  const client = new OpenArgClient({ apiKey: 'key', fetchImpl: (...args) => offline ? Promise.reject(new Error('offline')) : fake.fetch(...args) });
  await client.discover(); offline = true;
  await assert.rejects(client.call('listar_fuentes'), error => error.code === 'unavailable');
  assert.equal(client.status().transportConnected, false); assert.equal(client.status().queryError, null);
  offline = false; const restored = await client.discover(); assert.equal(restored.transportConnected, true); assert.equal(restored.transportError, null);
});

test('MCP isError rate limit retries once when explicit seconds are given', async () => {
  let count = 0; const sleeps = [];
  const fake = transport(() => { count++; return { isError: true, content: [{ type: 'text', text: 'Demasiadas consultas: esperá 1 segundo.' }] }; });
  const client = new OpenArgClient({ apiKey: 'key', fetchImpl: fake.fetch, sleep: async (ms) => sleeps.push(ms) });
  await assert.rejects(client.call('listar_fuentes'), (e) => e.code === 'rate_limited');
  assert.equal(count, 2); assert.deepEqual(sleeps, [1000]);
});

test('normalizes official numbered MCP format without fabricating dates or indicators', () => {
  const text = '1. **Censo población** (INDEC)\n   Descripción pública.\n   Tablas consultables: `censo_2022` (100 filas)\n   Fuente: [descarga oficial](https://www.indec.gob.ar/censo.csv)\n\nUsá `describir_tabla` con el nombre de una tabla para ver sus columnas y período.';
  const normalized = normalizeSearch({ text, warnings: [], cached: false, retrievedAt: '2026-10-30T00:00:00Z' }, 'población');
  assert.equal(normalized.datasets.length, 1); assert.equal(normalized.datasets[0].title, 'Censo población');
  assert.equal(normalized.datasets[0].resources[0].id, 'censo_2022');
  assert.equal(normalized.datasets[0].period, undefined); assert.equal(normalized.datasets[0].updatedAt, undefined);
  assert.equal(normalized.sources[0].url, 'https://www.indec.gob.ar/censo.csv');
  assert.deepEqual(extractEvidence('[no](javascript:alert(1))').sources, []);
});
