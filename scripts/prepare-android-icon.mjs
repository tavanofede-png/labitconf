import { createRequire } from 'node:module';
import { writeFile, mkdir, rm } from 'node:fs/promises';
import { resolve } from 'node:path';
const require = createRequire(import.meta.url);
const sharp = require(process.env.SHARP_MODULE || 'sharp');
const root = resolve(import.meta.dirname, '..');
const source = resolve(root, 'brand/tino-launcher-t.png');
const resources = resolve(root, 'apps/android/app/src/main/res');
const metadata = await sharp(source).metadata();
if (!metadata.hasAlpha) throw new Error('Android foreground requires transparent alpha.');
// Packaging only. The built-in image tool extracted the background and prepared the supplied mark.
const foreground = await sharp(source).resize(404, 404, { fit: 'contain', background: '#00000000' })
    .extend({ top: 14, bottom: 14, left: 14, right: 14, background: '#00000000' }).png().toBuffer();
await writeFile(resolve(resources, 'drawable-nodpi/tino_launcher_art.png'), foreground);
await rm(resolve(resources, 'drawable-nodpi/tino_launcher_foreground.png'), { force: true });
await mkdir(resolve(resources, 'drawable'), { recursive: true });
// Native alpha-mask tint pins the displayed launcher/bubble color exactly to the approved sRGB value.
await writeFile(resolve(resources, 'drawable/tino_launcher_foreground.xml'), `<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@drawable/tino_launcher_art"
    android:gravity="fill" android:filter="true"
    android:tint="#00D6C9" android:tintMode="src_in" />
`);
for (const [density, size] of [['mdpi', 48], ['hdpi', 72], ['xhdpi', 96], ['xxhdpi', 144], ['xxxhdpi', 192]]) {
    await sharp(foreground).resize(size, size).flatten({ background: '#101416' }).png()
        .toFile(resolve(resources, `mipmap-${density}/ic_launcher.png`));
}
await sharp(foreground).resize(256, 256).flatten({ background: '#101416' }).png()
    .toFile(resolve(resources, 'drawable-nodpi/tino_icon.png'));
console.log('Prepared Android t mark with adaptive safe padding and exact native #00D6C9 tint.');
