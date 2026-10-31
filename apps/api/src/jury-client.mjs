import { createHash } from 'node:crypto';

// A browser instance identifies its demo conversation; it does not grant access
// or replace the real network origin used by admission and rate limits.
export function juryBrowserDeviceId(origin, clientId) {
  const session = typeof clientId === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(clientId)
    ? clientId.toLowerCase() : 'legacy';
  return `jury-web-${createHash('sha256').update(`${origin}|${session}`).digest('hex').slice(0, 24)}`;
}
