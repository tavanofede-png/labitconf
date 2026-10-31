import test from 'node:test';
import assert from 'node:assert/strict';
import { juryBrowserDeviceId } from '../src/jury-client.mjs';

test('separate jury browsers on one network cannot share a conversation identity', () => {
  const a = '08e31101-df30-45ab-bdb4-f2790b438cc2';
  const b = '88e444dc-2895-445e-8425-9901dd3d37f4';
  assert.notEqual(juryBrowserDeviceId('same-origin', a), juryBrowserDeviceId('same-origin', b));
  assert.equal(juryBrowserDeviceId('same-origin', a), juryBrowserDeviceId('same-origin', a.toUpperCase()));
  assert.notEqual(juryBrowserDeviceId('network-a', a), juryBrowserDeviceId('network-b', a));
});

test('legacy and malformed browser identifiers stay bounded and cannot inject device IDs', () => {
  const legacy = juryBrowserDeviceId('network', undefined);
  for (const value of [null, {}, 'another-user', '../production', 'a'.repeat(50000)]) assert.equal(juryBrowserDeviceId('network', value), legacy);
  assert.match(legacy, /^jury-web-[a-f0-9]{24}$/);
});
