import { lstat, open, readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const DEFAULT_DIRECTORY = fileURLToPath(new URL('../../../artifacts/', import.meta.url));

/** One build output, authenticated against the atomically published build manifest. */
export function createAndroidArtifacts(directory = DEFAULT_DIRECTORY) {
  let verifiedIdentity = null;
  async function load(withStream) {
    let file;
    try {
      const folder = await lstat(directory); if (!folder.isDirectory() || folder.isSymbolicLink()) return null;
      const manifestPath = join(directory, 'android-build.json');
      const manifestInfo = await lstat(manifestPath);
      if (!manifestInfo.isFile() || manifestInfo.isSymbolicLink() || manifestInfo.size > 16_384) return null;
      const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
      if (manifest.schemaVersion !== 1 || manifest.apkFile !== 'nieto-debug.apk' || manifest.applicationId !== 'org.nieto.assistant' ||
          !Number.isSafeInteger(manifest.versionCode) || manifest.versionCode < 1 || typeof manifest.versionName !== 'string' || !/^[A-Za-z0-9._-]{1,80}$/.test(manifest.versionName) ||
          typeof manifest.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(manifest.sha256) || !Number.isSafeInteger(manifest.size) || manifest.size < 1 || !Number.isFinite(Date.parse(manifest.builtAt))) return null;
      const apkPath = join(directory, 'nieto-debug.apk'); const pathInfo = await lstat(apkPath);
      if (!pathInfo.isFile() || pathInfo.isSymbolicLink()) return null;
      file = await open(apkPath, 'r'); const info = await file.stat();
      if (info.size !== manifest.size) { await file.close(); return null; }
      const identity = `${manifest.sha256}:${info.size}:${info.mtimeMs}:${info.ctimeMs}:${info.ino}`;
      if (identity !== verifiedIdentity) {
        const hash = createHash('sha256'); for await (const chunk of file.createReadStream({ start: 0, autoClose: false })) hash.update(chunk);
        if (hash.digest('hex') !== manifest.sha256) { await file.close(); return null; }
        const after = await file.stat();
        if (after.size !== info.size || after.mtimeMs !== info.mtimeMs || after.ctimeMs !== info.ctimeMs) { await file.close(); return null; }
        verifiedIdentity = identity;
      }
      const result = { size: info.size, sha256: manifest.sha256, versionName: manifest.versionName, versionCode: manifest.versionCode, builtAt: manifest.builtAt };
      if (withStream) return { ...result, stream: file.createReadStream({ start: 0, autoClose: true }) };
      await file.close(); return result;
    } catch { await file?.close().catch(() => {}); return null; }
  }
  return { info: () => load(false), open: () => load(true) };
}
