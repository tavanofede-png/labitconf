import { cp, mkdir, readFile, writeFile, readdir, stat, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, join, relative } from 'node:path';
const root = resolve(import.meta.dirname, '..');
const destination = join(root, '.vercel/output/static');
await rm(destination, { recursive: true, force: true });
await mkdir(destination, { recursive: true });
await cp(join(root, 'apps/web/dist'), destination, { recursive: true });
await mkdir(join(destination, 'app'), { recursive: true });
await cp(join(root, 'apps/web/dist/index.html'), join(destination, 'app/index.html'));
const landing = (await readFile(join(root, 'apps/web/dist/inicio.html'), 'utf8')).replaceAll('href="/#', 'href="/app/#');
await writeFile(join(destination, 'index.html'), landing);
await writeFile(join(destination, 'inicio.html'), landing);
await writeFile(join(root, '.vercel/output/config.json'), JSON.stringify({ version: 3 }));
async function files(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const result = [];
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) result.push(...await files(path));
    else { const data = await readFile(path); result.push({ file: relative(destination, path), path, size: (await stat(path)).size, sha: createHash('sha1').update(data).digest('hex') }); }
  }
  return result;
}
console.log(JSON.stringify(await files(destination)));
