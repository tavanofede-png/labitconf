import { emptyState } from './store.mjs';

const collections = ['users', 'groups', 'invitations', 'relationships', 'consents', 'sessions', 'alerts', 'audit'];
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const unavailable = () => new Error('No se pudo acceder al almacenamiento privado persistente. No se reemplazaron sus datos.');
const storageFailure = (operation, error) => {
  if (process.env.VERCEL === '1') {
    const allowed = new Set(['BlobAccessError', 'BlobStoreNotFoundError', 'BlobStoreSuspendedError', 'BlobUnknownError',
      'BlobNotFoundError', 'BlobServiceNotAvailable', 'BlobServiceRateLimited', 'BlobRequestAbortedError', 'BlobPreconditionFailedError', 'SyntaxError', 'TypeError', 'Error']);
    const type = error?.constructor?.name;
    console.error(JSON.stringify({ component: 'tino_storage', operation, type: allowed.has(type) ? type : 'StorageError',
      ...(Number.isFinite(error?.retryAfter) ? { retryAfter: error.retryAfter } : {}) }));
  }
  return unavailable();
};

function validateState(value) {
  if (!value || typeof value !== 'object' || value.version !== 1 || collections.some(key => !Array.isArray(value[key]))) throw unavailable();
  return value;
}

/**
 * Private, durable replacement for JsonStore. Every read bypasses the Blob CDN;
 * every mutation is committed against the ETag of that exact read. Callbacks
 * may be retried after contention and must not perform external side effects.
 * No screen, voice, or conversation content is added by this adapter.
 */
export class BlobStore {
  constructor({ pathname = 'tino/production-state.json', token, storeId, client,
    maxAttempts = 16, maxBytes = 8 * 1024 * 1024, sleep = pause } = {}) {
    if (!/^[A-Za-z0-9_-]+(?:\/[A-Za-z0-9_.-]+)*$/.test(pathname) || pathname.includes('..')) throw new Error('Ruta de almacenamiento no válida.');
    if (!Number.isSafeInteger(maxAttempts) || maxAttempts < 1 || maxAttempts > 64) throw new Error('Número de reintentos no válido.');
    if (!Number.isSafeInteger(maxBytes) || maxBytes < 1024) throw new Error('Límite de almacenamiento no válido.');
    this.pathname = pathname; this.maxAttempts = maxAttempts; this.maxBytes = maxBytes; this.sleep = sleep;
    this.credentials = { ...(token ? { token } : {}), ...(storeId ? { storeId } : {}) };
    this.client = client; this.clientPromise = null; this.queue = Promise.resolve();
    this.storageType = 'vercel_blob_private_cas';
  }

  async sdk() {
    if (this.client) return this.client;
    this.clientPromise ??= import('@vercel/blob');
    return this.clientPromise;
  }

  options() { return { ...this.credentials, access: 'private', addRandomSuffix: false, contentType: 'application/json' }; }

  async readVersion() {
    const client = await this.sdk();
    // Compressed downloads can turn a strong storage ETag into W/"...".
    // Conditional writes require the strong ETag of the original bytes.
    const options = { ...this.credentials, access: 'private', useCache: false, headers: { 'accept-encoding': 'identity' } };
    let found;
    try {
      found = await client.get(this.pathname, options);
      if (!found) {
        // A concurrent cold start can win creation. Never overwrite on init.
        try { await client.put(this.pathname, JSON.stringify(emptyState()), { ...this.options(), allowOverwrite: false }); }
        catch { /* A fresh read below decides whether creation succeeded. */ }
        found = await client.get(this.pathname, options);
      }
      if (!found || found.statusCode !== 200 || !found.stream || typeof found.blob?.etag !== 'string' || !found.blob.etag) throw unavailable();
      const size = found.blob.size;
      if (typeof size === 'number' && size > this.maxBytes) { await found.stream.cancel(); throw unavailable(); }
      const reader = found.stream.getReader();
      const chunks = []; let bytes = 0;
      try {
        while (true) {
          const { done, value } = await reader.read(); if (done) break;
          bytes += value.byteLength;
          if (bytes > this.maxBytes) { await reader.cancel(); throw unavailable(); }
          chunks.push(Buffer.from(value));
        }
      } finally { reader.releaseLock(); }
      const state = validateState(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      return { state, etag: found.blob.etag };
    } catch (error) { throw storageFailure('read', error); }
  }

  async init() { await this.readVersion(); }

  async read() {
    await this.queue;
    return (await this.readVersion()).state;
  }

  update(change) {
    if (typeof change !== 'function') return Promise.reject(new TypeError('La actualización debe ser una función.'));
    const work = this.queue.then(async () => {
      const client = await this.sdk();
      for (let attempt = 0; attempt < this.maxAttempts; attempt++) {
        const { state, etag } = await this.readVersion();
        const result = await change(state);
        validateState(state);
        const serialized = JSON.stringify(state);
        if (Buffer.byteLength(serialized) > this.maxBytes) throw unavailable();
        try {
          await client.put(this.pathname, serialized, { ...this.options(), allowOverwrite: true, ifMatch: etag });
          return result;
        } catch (error) {
          const isConflict = (client.BlobPreconditionFailedError && error instanceof client.BlobPreconditionFailedError)
            || error?.name === 'BlobPreconditionFailedError';
          if (!isConflict) throw storageFailure('write', error);
          if (attempt + 1 < this.maxAttempts) await this.sleep(Math.min(100, 4 * (attempt + 1)) + Math.floor(Math.random() * 8));
        }
      }
      throw unavailable();
    });
    this.queue = work.catch(() => {});
    return work;
  }
}
