export const MAX_IMAGE_BYTES = 300_000;
const keys = (value, allowed) => value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every(key => allowed.includes(key));
const id = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value);
const dimension = value => Number.isInteger(value) && value > 0 && value <= 10_000;
const fail = message => { throw new Error(message); };
const navigationMethods = new Set(['button', 'gesture', 'unknown']);
const packageName = value => typeof value === 'string' && /^[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*$/.test(value) && value.length <= 120;
const secretLabel = value => {
  const text = value.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  return /\d{6,}|[\w.+-]+@[\w.-]+\.[a-z]{2,}/i.test(text) ||
    /\b(?:codigo(?: de verificacion)?|pin|otp|cvv|cvc)\s+\d{3,}\b/i.test(text) ||
    /\b(?:tarjeta|card|cbu|cvu|cuit|cuil|dni|telefono|phone)\s*[:#=]?\s*[+\d][\d .-]{3,}/i.test(text) ||
    /\b(?:password|contrasena|passcode|otp|pin|cvv|cvc|token|clave(?: fiscal)?)\s*["']?\s*[:=]\s*\S+/i.test(text) ||
    /\b(?:sk-[a-z0-9_-]{8,}|bearer\s+\S+|api[_ -]?key\s*[:=]\s*\S+)/i.test(text);
};

function validateNavigation(navigation) {
  if (!keys(navigation, ['home', 'back', 'installedApps', 'isLauncher']) || !navigationMethods.has(navigation.home) || !navigationMethods.has(navigation.back) || (navigation.installedApps !== undefined && (!Array.isArray(navigation.installedApps) || navigation.installedApps.length > 6)) || (navigation.isLauncher !== undefined && typeof navigation.isLauncher !== 'boolean')) fail('La navegación del dispositivo no tiene un formato válido.');
  const seen = new Set();
  const installedApps = (navigation.installedApps ?? []).map(app => {
    if (!keys(app, ['packageName', 'label']) || !packageName(app.packageName) || seen.has(app.packageName) || typeof app.label !== 'string' || !app.label.trim() || app.label.length > 80 || /[\u0000-\u001f\u007f]/.test(app.label) || secretLabel(app.label)) fail('Las aplicaciones de navegación no tienen un formato válido.');
    seen.add(app.packageName);
    return { packageName: app.packageName, label: app.label.trim() };
  });
  return { home: navigation.home, back: navigation.back, ...(navigation.installedApps !== undefined ? { installedApps } : {}), ...(navigation.isLauncher !== undefined ? { isLauncher: navigation.isLauncher } : {}) };
}

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
  if (!keys(screen, ['id', 'packageName', 'width', 'height', 'controls', 'image', 'navigation']) || !id(screen.id) || typeof screen.packageName !== 'string' || !/^[A-Za-z0-9_.]{1,180}$/.test(screen.packageName) || !dimension(screen.width) || !dimension(screen.height) || !Array.isArray(screen.controls) || screen.controls.length > 60) fail('La pantalla no tiene un formato válido.');
  const seen = new Set();
  let controlsOnly = false;
  const controls = screen.controls.map(control => {
    if (!keys(control, ['id', 'label', 'role', 'bounds', 'redacted']) || !id(control.id) || seen.has(control.id) || typeof control.label !== 'string' || !control.label.trim() || control.label.length > 120 || typeof control.role !== 'string' || !control.role.trim() || control.role.length > 40 || (control.redacted !== undefined && typeof control.redacted !== 'boolean') || !Array.isArray(control.bounds) || control.bounds.length !== 4 || !control.bounds.every(Number.isInteger)) fail('Los controles de la pantalla no tienen un formato válido.');
    const [left, top, right, bottom] = control.bounds;
    if (left < 0 || top < 0 || right <= left || bottom <= top || right > screen.width || bottom > screen.height) fail('Un control está fuera de la pantalla.');
    seen.add(control.id);
    const role = control.role.trim();
    if (role === 'input' || control.redacted === true) {
      controlsOnly = true;
      return { id: control.id, label: role === 'input' ? 'Campo de texto' : 'Control protegido', role, bounds: [...control.bounds], redacted: true };
    }
    if (secretLabel(control.label)) { controlsOnly = true; return null; }
    return { id: control.id, label: control.label.trim(), role, bounds: [...control.bounds] };
  }).filter(Boolean);
  let image;
  if (screen.image !== undefined) {
    if (!keys(screen.image, ['mimeType', 'data']) || screen.image.mimeType !== 'image/jpeg' || typeof screen.image.data !== 'string' || !screen.image.data.length || screen.image.data.length > MAX_IMAGE_BYTES * 4 / 3 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(screen.image.data)) fail('La imagen debe ser JPEG de hasta 300 KB.');
    const bytes = Buffer.from(screen.image.data, 'base64'); const size = jpegDimensions(bytes);
    if (!size || !dimension(size.width) || !dimension(size.height) || bytes.length > MAX_IMAGE_BYTES || bytes.toString('base64') !== screen.image.data) fail('La imagen JPEG no es válida o excede los límites.');
    image = { mimeType: 'image/jpeg', data: screen.image.data, bytes: bytes.length };
  }
  const navigation = screen.navigation !== undefined ? validateNavigation(screen.navigation) : undefined;
  return { id: screen.id, packageName: screen.packageName, width: screen.width, height: screen.height, controls, ...(image && !controlsOnly ? { image } : {}), ...(navigation ? { navigation } : {}) };
}
