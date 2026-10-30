import { createRequire } from 'node:module';
import { mkdir, copyFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
const require = createRequire(import.meta.url);
const sharp = require(process.env.SHARP_MODULE || 'sharp');
const root = resolve(import.meta.dirname, '..');
const [logoInput, iconInput] = process.argv.slice(2);
if (!logoInput || !iconInput) throw new Error('Usage: node scripts/prepare-brand.mjs <transparent-logo.png> <transparent-icon.png>');
const brand = resolve(root, 'brand');
const web = resolve(root, 'apps/web/public/brand');
const android = resolve(root, 'apps/android/app/src/main/res');
await Promise.all([mkdir(brand, { recursive: true }), mkdir(web, { recursive: true }), mkdir(resolve(android, 'drawable-nodpi'), { recursive: true })]);
// Image generation performs the extraction/design; this script only packages and scales those outputs.
for (const [label, path] of [['logo', logoInput], ['icon', iconInput]]) {
  const metadata = await sharp(path).metadata();
  const stats = await sharp(path).stats();
  if (!metadata.hasAlpha || stats.channels[3].min !== 0 || stats.channels[3].max !== 255) throw new Error(`${label} must have a real alpha channel`);
}
const logo = await sharp(logoInput).trim().png().toBuffer();
await writeFile(resolve(brand, 'tino-logo-transparent.png'), logo);
await sharp(logo).resize({ width: 900 }).png().toFile(resolve(web, 'tino-logo.png'));
await sharp(logo).resize({ width: 900 }).webp({ lossless: true }).toFile(resolve(web, 'tino-logo.webp'));
await sharp(logo).resize({ width: 768 }).png().toFile(resolve(android, 'drawable-nodpi/tino_logo.png'));
await sharp(iconInput).resize(1024, 1024, { fit: 'contain', background: '#00000000' }).png().toFile(resolve(brand, 'tino-icon-transparent.png'));
const icon = await sharp(iconInput).resize(1024, 1024, { fit: 'contain', background: '#00000000' }).png().toBuffer();
const appIcon = await sharp(icon).flatten({ background: '#F3F6F6' }).png().toBuffer();
await writeFile(resolve(brand, 'tino-app-icon-1024.png'), appIcon);
await sharp(icon).resize(432, 432).png().toFile(resolve(android, 'drawable-nodpi/tino_launcher_foreground.png'));
await sharp(appIcon).resize(256, 256).png().toFile(resolve(android, 'drawable-nodpi/tino_icon.png'));
for (const size of [16, 32, 48, 180, 192, 512]) await sharp(appIcon).resize(size, size).png().toFile(resolve(web, `tino-icon-${size}.png`));
for (const [density, size] of [['mdpi', 48], ['hdpi', 72], ['xhdpi', 96], ['xxhdpi', 144], ['xxxhdpi', 192]]) {
  const folder = resolve(android, `mipmap-${density}`); await mkdir(folder, { recursive: true });
  await sharp(appIcon).resize(size, size).png().toFile(resolve(folder, 'ic_launcher.png'));
}
// ICO container with PNG entries for common desktop browser sizes.
const images = await Promise.all([16, 32, 48].map(size => sharp(appIcon).resize(size, size).png().toBuffer()));
const header = Buffer.alloc(6 + images.length * 16); header.writeUInt16LE(1, 2); header.writeUInt16LE(images.length, 4);
let offset = header.length;
images.forEach((buffer, index) => { const i = 6 + index * 16; header[i] = [16, 32, 48][index]; header[i + 1] = header[i]; header.writeUInt16LE(1, i + 4); header.writeUInt16LE(32, i + 6); header.writeUInt32LE(buffer.length, i + 8); header.writeUInt32LE(offset, i + 12); offset += buffer.length; });
await writeFile(resolve(root, 'apps/web/public/favicon.ico'), Buffer.concat([header, ...images]));
await copyFile(resolve(web, 'tino-icon-192.png'), resolve(brand, 'tino-web-icon-192.png'));
console.log('Validated transparent alpha; prepared wordmark, web icons, ICO, Android adaptive foreground and five launcher densities.');
