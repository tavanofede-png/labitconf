import assert from 'node:assert/strict';
import { readFile, readdir, lstat } from 'node:fs/promises';
import { resolve, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const expectedRoutes = ['/demo', '/emulador', '/web', '/portal'];
const secretNames = ['OPENAI_API_KEY', 'OPENARG_API_KEY', 'INSTITUTION_TOKEN', 'VERCEL_TOKEN'];

async function configuredSecrets() {
  const values = secretNames.map(name => process.env[name]).filter(Boolean);
  for (const name of ['.env', '.env.local']) {
    let content;
    try { content = await readFile(resolve(root, name), 'utf8'); }
    catch (error) { if (error.code === 'ENOENT') continue; throw error; }
    for (const line of content.split(/\r?\n/)) {
      const match = /^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*)$/.exec(line);
      if (!match || !secretNames.includes(match[1])) continue;
      const raw = match[2].trim();
      values.push(/^(['"])(.*)\1$/.test(raw) ? raw.slice(1, -1) : raw.split(/\s+#/)[0]);
    }
  }
  return [...new Set(values.filter(value => value.length > 12))];
}

async function filesWithin(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name);
    assert.equal((await lstat(path)).isSymbolicLink(), false, 'El build no puede publicar enlaces a archivos externos.');
    if (entry.isDirectory()) files.push(...await filesWithin(path));
    else files.push(path);
  }
  return files;
}

function builtAsset(value, output) {
  const url = new URL(value, 'https://static-build.invalid');
  assert.equal(url.origin, 'https://static-build.invalid', 'El documento inicial no debe cargar scripts externos.');
  const path = resolve(output, '.' + decodeURIComponent(url.pathname));
  assert.ok(path.startsWith(output + sep), 'Una referencia de asset sale del directorio público.');
  return path;
}

async function remoteChecks(base, indexAssets) {
  const origin = new URL(base);
  assert.ok(['https:', 'http:'].includes(origin.protocol), 'La URL debe usar HTTP o HTTPS.');
  assert.ok(!origin.username && !origin.password && !origin.search && !origin.hash && origin.pathname === '/', 'Usá la URL raíz del despliegue, sin credenciales ni parámetros.');
  const request = async path => fetch(new URL(path, origin), { signal: AbortSignal.timeout(20_000), redirect: 'follow' });
  for (const path of ['/', ...expectedRoutes]) {
    const response = await request(path);
    assert.equal(response.status, 200, `${path} no respondió 200.`);
    assert.match(response.headers.get('content-type') ?? '', /text\/html/i, `${path} no devolvió HTML.`);
    const html = await response.text();
    assert.ok(indexAssets.every(asset => html.includes(asset)), `${path} no entregó la versión compilada actual.`);
  }
  for (const path of indexAssets) {
    const response = await request(path);
    assert.equal(response.status, 200, `No se pudo cargar ${path}.`);
    assert.doesNotMatch(response.headers.get('content-type') ?? '', /text\/html/i, 'Un asset fue reemplazado por el index de la SPA.');
    await response.arrayBuffer();
  }
  for (const path of ['/api/health', '/api/assistant/message', '/downloads/nieto-debug.apk', '/.env', '/apps/api/data/nieto.json']) {
    const response = await request(path);
    assert.equal(response.status, 404, `${path} debe quedar fuera de esta publicación estática.`);
    await response.arrayBuffer();
  }
  return { origin: origin.origin, htmlRoutes: expectedRoutes.length + 1, protectedPaths: 5, assets: indexAssets.length };
}

let knownSecrets = [];
try {
  knownSecrets = await configuredSecrets();
  const config = JSON.parse(await readFile(resolve(root, 'vercel.json'), 'utf8'));
  assert.equal(config.framework, 'vite');
  assert.equal(config.buildCommand, 'npm run build');
  assert.equal(config.installCommand, 'npm ci');
  assert.equal(config.outputDirectory, 'apps/web/dist');
  assert.equal(config.functions, undefined, 'Esta demo no despliega Functions de la API local.');
  assert.equal(config.services, undefined, 'Esta demo no despliega el backend local.');
  assert.deepEqual(config.rewrites.map(route => route.source).sort(), expectedRoutes.map(path => `${path}/:path*`).sort(), 'Las reescrituras deben limitarse a las rutas de la demo; /api no puede caer en la SPA.');
  assert.ok(config.rewrites.every(route => route.destination === '/index.html'));

  const output = resolve(root, config.outputDirectory);
  const files = await filesWithin(output);
  let bytes = 0;
  for (const file of files) {
    const name = relative(output, file).split(sep).join('/');
    assert.doesNotMatch(name, /(?:^|\/)(?:\.env(?:\..*)?|\.git|nieto\.json|local\.properties)(?:\/|$)|\.(?:jks|keystore|pem|sqlite|db)$/i, `Archivo privado en el build: ${name}`);
    const content = await readFile(file); bytes += content.length;
    assert.ok(!knownSecrets.some(secret => content.includes(Buffer.from(secret))), `Credencial configurada incluida en el build: ${name}`);
  }
  const html = await readFile(resolve(output, 'index.html'), 'utf8');
  assert.match(html, /<div\s+id=["']root["']/);
  const scriptAssets = [...html.matchAll(/<script\b[^>]*\bsrc=["']([^"']+)["']/g)].map(match => match[1]);
  const styleAssets = [...html.matchAll(/<link\b(?=[^>]*\brel=["']stylesheet["'])[^>]*\bhref=["']([^"']+)["']/g)].map(match => match[1]);
  const indexAssets = [...scriptAssets, ...styleAssets];
  assert.ok(scriptAssets.length > 0, 'El build no tiene un entrypoint JavaScript.');
  for (const asset of indexAssets) await readFile(builtAsset(asset, output));
  const urlFlag = process.argv.indexOf('--url');
  assert.ok(urlFlag < 0 || process.argv[urlFlag + 1], 'Falta la URL después de --url.');
  const remote = urlFlag >= 0 ? await remoteChecks(process.argv[urlFlag + 1], indexAssets) : null;
  console.log(JSON.stringify({ ok: true, staticOnly: true, files: files.length, bytes, configuredSecretsChecked: knownSecrets.length, configuredSecretHits: 0, spaRoutes: expectedRoutes, remote }, null, 2));
} catch (error) {
  let message = error.message;
  for (const secret of knownSecrets) message = message.split(secret).join('[redacted]');
  console.error(JSON.stringify({ ok: false, message, secretsPrinted: false }));
  process.exitCode = 1;
}
