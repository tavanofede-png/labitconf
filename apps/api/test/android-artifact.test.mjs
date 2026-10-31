import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rename, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { createAndroidArtifacts } from '../src/android-artifact.mjs';
import http from 'node:http';
import { PassThrough } from 'node:stream';
import { once } from 'node:events';
import { createServer } from '../src/server.mjs';
import { RealtimeService } from '../src/realtime.mjs';
import { OpenArgClient } from '../src/openarg.mjs';

test('only the canonical APK with matching version manifest is offered; stale or corrupt copies fail closed', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'nieto-apk-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const artifacts = createAndroidArtifacts(directory);
  const apk = Buffer.from('PK first synthetic APK');
  const path = join(directory, 'nieto-debug.apk');
  await writeFile(path, apk);
  assert.equal(await artifacts.info(), null, 'an APK alone cannot prove its version');
  const manifest = { schemaVersion: 1, apkFile: 'nieto-debug.apk', applicationId: 'org.nieto.assistant', versionCode: 4, versionName: '0.4.0-labitconf', builtAt: new Date().toISOString(), size: apk.length, sha256: createHash('sha256').update(apk).digest('hex') };
  await writeFile(join(directory, 'android-build.json'), JSON.stringify(manifest));
  assert.equal((await artifacts.info()).versionCode, 4);
  const download = await artifacts.open();
  // Atomic rebuild during a download cannot swap the already verified file descriptor.
  const replacement = Buffer.from('PK newer synthetic APK');
  await writeFile(join(directory, 'new.apk'), replacement);
  let replacementBlocked = false;
  try { await rename(join(directory, 'new.apk'), path); }
  catch (error) { assert.equal(process.platform, 'win32'); assert.equal(error.code, 'EPERM'); replacementBlocked = true; }
  const chunks = []; for await (const chunk of download.stream) chunks.push(chunk);
  assert.deepEqual(Buffer.concat(chunks), apk);
  if (replacementBlocked) await rename(join(directory, 'new.apk'), path);
  assert.equal(await artifacts.info(), null, 'new APK with old manifest is not distributed');
  await writeFile(join(directory, 'android-build.json'), JSON.stringify({ ...manifest, apkFile: '../private.json' }));
  assert.equal(await artifacts.open(), null, 'manifest cannot select another file');
  await writeFile(join(directory, 'android-build.json'), '{broken');
  assert.equal(await artifacts.info(), null);
});

test('download aborted during APK verification closes the eventual file stream', async t => {
  let finishOpen; let opened; let responseClosed;
  const opening = new Promise(resolve => { opened = resolve; });
  const closed = new Promise(resolve => { responseClosed = resolve; });
  const verification = new Promise(resolve => { finishOpen = resolve; });
  const server = createServer({ androidArtifacts: { info: async () => null, open: () => { opened(); return verification; } },
    realtime: new RealtimeService({ apiKey: '' }), openarg: new OpenArgClient({ apiKey: '' }) });
  server.on('request', (_req, res) => res.once('close', responseClosed));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { server.closeRealtime(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
  const request = http.get(`http://127.0.0.1:${server.address().port}/downloads/nieto-debug.apk`);
  request.on('error', () => {});
  await opening; request.destroy(); await closed;
  const stream = new PassThrough(); const streamClosed = once(stream, 'close');
  finishOpen({ stream, size: 1, versionName: '0.4.0-labitconf', sha256: 'a'.repeat(64) });
  await streamClosed; assert.equal(stream.destroyed, true);
});
