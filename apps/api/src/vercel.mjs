import { createServer } from './server.mjs';
import { BlobStore } from './blob-store.mjs';
import { RealtimeService } from './realtime.mjs';
import { isIP } from 'node:net';
import { waitUntil } from '@vercel/functions';

/** Vercel overwrites this header at ingress. Never trust it in local servers. */
export function vercelClientAddress(req, runtime = process.env.VERCEL) {
  const forwarded = req.headers['x-vercel-forwarded-for'];
  return runtime === '1' && typeof forwarded === 'string' && isIP(forwarded) ? forwarded : req.socket.remoteAddress;
}

/** Export an actual Node HTTP server so Vercel forwards HTTP and WS upgrades. */
export function createCloudServer(options = {}) {
  const store = options.store ?? new BlobStore({ pathname: process.env.BLOB_STATE_PATH || 'tino/production-state.json',
    token: process.env.BLOB_READ_WRITE_TOKEN, storeId: process.env.BLOB_STORE_ID });
  const continuous = options.continuousAssistance ?? process.env.CONTINUOUS_ASSISTANCE === '1';
  const realtime = options.realtime ?? new RealtimeService({ continuous, conversationOptions: { maxSessionMs: 270_000 } });
  return createServer({ clientAddress: vercelClientAddress, ...options, store, realtime,
    // Prototype logins and fixed instructional answers never reach production,
    // even if local/demo flags accidentally exist in the cloud environment.
    accountOptions: { ...options.accountOptions, localDemo: false, judgesDemo: false }, allowLocalAssistant: false,
    continuousAssistance: continuous,
    defer: options.defer ?? (promise => { if (process.env.VERCEL === '1') waitUntil(promise); }),
    // Vercel preserves HTTP URLs but can pass the rewritten Function URL when
    // forwarding an upgrade. Both paths use the same authentication/protocol.
    realtimePaths: ['/api/assistant/realtime', '/api/index'],
    realtimeRewriteQuery: { path: 'assistant/realtime' } });
}

export default createCloudServer();
