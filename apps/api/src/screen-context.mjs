export const MAX_IMAGE_BYTES = 300_000;
const keys = (value, allowed) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every(key => allowed.includes(key));
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value);
const dimension = value => Number.isInteger(value) && value > 0 && value <= 10_000;
const fail = message => { throw new Error(message); };

function jpegDimensions(bytes) {
  if (bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes.at(-2) !== 0xff || bytes.at(-1) !== 0xd9) return null;
  let at = 2;
  while (at + 4 < bytes.length) {
    if (bytes[at++] !== 0xff) return null;
    while (bytes[at] === 0xff) at++;
    const marker = bytes[at++];
    if (marker === 0xda || marker === 0xd9) return null;
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
    const length = bytes.readUInt16BE(at);
    if (length < 2 || at + length > bytes.length) return null;
    if ([0xc0, 0xc1, 0xc2].includes(marker)) {
      if (length < 8) return null;
      return { height: bytes.readUInt16BE(at + 3), width: bytes.readUInt16BE(at + 5) };
    }
    at += length;
  }
  return null;
}

/** Copies only the reduced, bounded schema. Never stores screen data on disk. */
export function validateSnapshot(screen) {
  if (!keys(screen, ['id', 'packageName', 'width', 'height', 'controls', 'image']) || !id(screen.id) || typeof screen.packageName !== 'string' || !/^[A-Za-z0-9_.]{1,180}$/.test(screen.packageName) || !dimension(screen.width) || !dimension(screen.height) || !Array.isArray(screen.controls) || screen.controls.length > 60) fail('La pantalla no tiene un formato válido.');
  const seen = new Set();
  const controls = screen.controls.map(control => {
    if (!keys(control, ['id', 'label', 'role', 'bounds']) || !id(control.id) || seen.has(control.id) || typeof control.label !== 'string' || !control.label.trim() || control.label.length > 120 || typeof control.role !== 'string' || !control.role.trim() || control.role.length > 40 || !Array.isArray(control.bounds) || control.bounds.length !== 4 || !control.bounds.every(Number.isInteger)) fail('Los controles de la pantalla no tienen un formato válido.');
    const [left, top, right, bottom] = control.bounds;
    if (left < 0 || top < 0 || right <= left || bottom <= top || right > screen.width || bottom > screen.height) fail('Un control está fuera de la pantalla.');
    seen.add(control.id); return { id: control.id, label: control.label.trim(), role: control.role.trim(), bounds: [...control.bounds] };
  });
  let image;
  if (screen.image !== undefined) {
    if (!keys(screen.image, ['mimeType', 'data']) || screen.image.mimeType !== 'image/jpeg' || typeof screen.image.data !== 'string' || !screen.image.data.length || screen.image.data.length > MAX_IMAGE_BYTES * 4 / 3 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(screen.image.data)) fail('La imagen debe ser JPEG de hasta 300 KB.');
    const bytes = Buffer.from(screen.image.data, 'base64'); const size = jpegDimensions(bytes);
    if (!size || !dimension(size.width) || !dimension(size.height) || bytes.length > MAX_IMAGE_BYTES || bytes.toString('base64') !== screen.image.data) fail('La imagen JPEG no es válida o excede los límites.');
    image = { mimeType: 'image/jpeg', data: screen.image.data, bytes: bytes.length };
  }
  return { id: screen.id, packageName: screen.packageName, width: screen.width, height: screen.height, controls, ...(image ? { image } : {}) };
}
