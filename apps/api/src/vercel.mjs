import { createServer } from './server.mjs';
import { BlobStore } from './blob-store.mjs';
import { RealtimeService } from './realtime.mjs';
import { isIP } from 'node:net';

/** Vercel overwrites this header at ingress. Never trust it in local servers. */
export function vercelClientAddress(req, runtime = process.env.VERCEL) {
  const forwarded = req.headers['x-vercel-forwarded-for'];
  return runtime === '1' && typeof forwarded === 'string' && isIP(forwarded) ? forwarded : req.socket.remoteAddress;
}

/** Export an actual Node HTTP server so Vercel forwards HTTP and WS upgrades. */
export function createCloudServer(options = {}) {
  const store = options.store ?? new BlobStore({ pathname: process.env.BLOB_STATE_PATH || 'tino/production-state.json',
    token: process.env.BLOB_READ_WRITE_TOKEN, storeId: process.env.BLOB_STORE_ID });
  const realtime = options.realtime ?? new RealtimeService({ conversationOptions: { maxSessionMs: 270_000 } });
  return createServer({ clientAddress: vercelClientAddress, ...options, store, realtime });
}

export default createCloudServer();
