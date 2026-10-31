import test from 'node:test';
import assert from 'node:assert/strict';
import { BlobStore } from '../src/blob-store.mjs';
import { emptyState } from '../src/store.mjs';

class BlobPreconditionFailedError extends Error { constructor() { super('Conflict'); this.name = 'BlobPreconditionFailedError'; } }
function fakeBlob(initial = null) {
  let body = initial, version = initial ? 1 : 0;
  const calls = [];
  const client = {
    BlobPreconditionFailedError,
    async get(pathname, options) {
      calls.push({ type: 'get', pathname, options });
      if (body === null) return null;
      const bytes = Buffer.from(body); const etag = `"${version}"`;
      return { statusCode: 200, blob: { etag, size: bytes.length }, stream: new ReadableStream({ start(controller) { controller.enqueue(bytes); controller.close(); } }) };
    },
    async put(pathname, content, options) {
      calls.push({ type: 'put', pathname, options });
      if (!options.allowOverwrite && body !== null) throw Error('Already exists');
      if (options.ifMatch && options.ifMatch !== `"${version}"`) throw new BlobPreconditionFailedError();
      body = content; version++;
      return { etag: `"${version}"` };
    },
  };
  return { client, calls, content: () => body, replace: value => { body = value; version++; } };
}
const storeOf = fake => new BlobStore({ client: fake.client, sleep: async () => {} });

test('private Blob cold starts create once, read consistently and retain data across instances', async () => {
  const fake = fakeBlob(); const first = storeOf(fake), second = storeOf(fake);
  await Promise.all([first.init(), second.init()]);
  await first.update(state => { state.users.push({ id: 'one' }); return 'saved'; });
  assert.equal((await second.read()).users[0].id, 'one');
  assert.ok(fake.calls.filter(call => call.type === 'get').every(call => call.options.access === 'private' && call.options.useCache === false));
  assert.ok(fake.calls.filter(call => call.type === 'put').every(call => call.options.access === 'private' && call.options.addRandomSuffix === false));
  assert.ok(fake.calls.filter(call => call.type === 'put' && call.options.allowOverwrite).every(call => typeof call.options.ifMatch === 'string'));
});

test('distributed CAS does not lose writes across independently queued function instances', async () => {
  const fake = fakeBlob(JSON.stringify(emptyState()));
  const instances = Array.from({ length: 8 }, () => storeOf(fake));
  await Promise.all(instances.flatMap((store, instance) => Array.from({ length: 8 }, (_, index) => store.update(state => {
    state.users.push({ id: `${instance}-${index}` });
  }))));
  const state = await storeOf(fake).read();
  assert.equal(state.users.length, 64); assert.equal(new Set(state.users.map(user => user.id)).size, 64);
});

test('Blob reads request identity encoding so a compressed weak ETag cannot break conditional writes', async () => {
  const fake = fakeBlob(JSON.stringify(emptyState())); const originalGet = fake.client.get;
  fake.client.get = async (...args) => {
    const result = await originalGet(...args);
    if (args[1].headers?.['accept-encoding'] !== 'identity') result.blob.etag = 'W/' + result.blob.etag;
    return result;
  };
  await storeOf(fake).update(state => state.users.push({ id: 'strong-etag-write' }));
  assert.equal(JSON.parse(fake.content()).users.length, 1);
  assert.ok(fake.calls.filter(call => call.type === 'get').every(call => call.options.headers?.['accept-encoding'] === 'identity'));
});

test('conflict retries reevaluate budget checks against latest durable state and cannot overspend', async () => {
  const initial = emptyState(); initial.reservations = 0;
  const fake = fakeBlob(JSON.stringify(initial)); const instances = Array.from({ length: 8 }, () => storeOf(fake));
  const results = await Promise.allSettled(instances.map(store => store.update(state => {
    if (state.reservations >= 2) throw Error('Budget exhausted');
    state.reservations++; return state.reservations;
  })));
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 2);
  assert.equal(JSON.parse(fake.content()).reservations, 2);
});

test('invalid JSON, corrupt schema and absent ETags fail closed without overwriting existing content', async () => {
  for (const input of ['not-json', '{"version":1,"users":[]}', JSON.stringify({ ...emptyState(), users: null })]) {
    const fake = fakeBlob(input); const store = storeOf(fake);
    await assert.rejects(store.update(state => state.users.push({ id: 'lost' })), /almacenamiento privado/);
    assert.equal(fake.content(), input); assert.equal(fake.calls.filter(call => call.type === 'put').length, 0);
  }
  const fake = fakeBlob(JSON.stringify(emptyState())); const get = fake.client.get;
  fake.client.get = async (...args) => { const result = await get(...args); delete result.blob.etag; return result; };
  await assert.rejects(storeOf(fake).read(), /almacenamiento privado/);
});

test('failed writes are not reported as committed and a rejected callback does not poison the queue', async () => {
  const fake = fakeBlob(JSON.stringify(emptyState())); const store = storeOf(fake);
  await assert.rejects(store.update(state => { state.users.push({ id: 'bad' }); throw Error('Callback refused'); }), /Callback refused/);
  await store.update(state => state.users.push({ id: 'good' }));
  assert.deepEqual((await store.read()).users, [{ id: 'good' }]);
  fake.client.put = async () => { throw Error('Provider includes secret: never expose this'); };
  await assert.rejects(store.update(state => state.users.push({ id: 'unsaved' })), error => error.message.includes('almacenamiento privado') && !error.message.includes('secret'));
  assert.deepEqual(JSON.parse(fake.content()).users, [{ id: 'good' }]);
});

test('bounded contention and oversized state fail without unguarded writes', async () => {
  const fake = fakeBlob(JSON.stringify(emptyState())); let writes = 0;
  fake.client.put = async () => { writes++; throw new BlobPreconditionFailedError(); };
  const store = new BlobStore({ client: fake.client, maxAttempts: 3, sleep: async () => {} });
  await assert.rejects(store.update(state => { state.updated = true; }), /almacenamiento privado/);
  assert.equal(writes, 3);
  const small = new BlobStore({ client: fake.client, maxBytes: 1024 });
  await assert.rejects(small.update(state => { state.padding = 'x'.repeat(1200); }), /almacenamiento privado/);
  assert.equal(writes, 3);
});

test('cloud diagnostics recognize minified SDK errors without logging provider messages or credentials', async () => {
  const fake = fakeBlob(JSON.stringify(emptyState()));
  class Minified extends Error {}
  fake.client.BlobAccessError = Minified;
  fake.client.put = async () => { throw new Minified('Access denied. token SECRET_MUST_NEVER_BE_LOGGED'); };
  const originalCloud = process.env.VERCEL, originalError = console.error;
  const logs = []; process.env.VERCEL = '1'; console.error = value => logs.push(value);
  try { await assert.rejects(storeOf(fake).update(state => { state.probe = true; }), /almacenamiento privado/); }
  finally { console.error = originalError; if (originalCloud === undefined) delete process.env.VERCEL; else process.env.VERCEL = originalCloud; }
  assert.equal(logs.length, 1);
  assert.deepEqual(JSON.parse(logs[0]), { component: 'tino_storage', operation: 'write', type: 'BlobAccessError', reason: ['token', 'denied'] });
  assert.equal(logs.join('').includes('SECRET'), false);
  assert.equal(JSON.parse(fake.content()).probe, undefined);
});
