import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { validateSnapshot } from '../src/screen-context.mjs';

const control = (id, label, role = 'button', extra = {}) => ({ id, label, role, bounds: [20, 20, 300, 70], ...extra });
const screen = (packageName, controls) => ({ id: 'current', packageName, width: 1080, height: 2400, controls });

test('any app and Android settings keep real safe controls without package restrictions', () => {
  for (const packageName of ['com.android.settings', 'com.android.systemui', 'com.example.bank', 'ar.gob.argentinagobar', 'com.unknown.app']) {
    const safe = validateSnapshot(screen(packageName, [control('next', 'Siguiente'), control('help', 'Ayuda')]));
    assert.equal(safe.packageName, packageName);
    assert.deepEqual(safe.controls.map(item => item.id), ['next', 'help']);
  }
});

test('ARCA inputs are redacted while names of fiscal-key and password controls remain usable', () => {
  const safe = validateSnapshot(screen('ar.gob.afip.mobile.android.contribuyentes.mi_afip', [
    control('field', 'never preserve this input', 'input'), control('login', 'Ingresar con clave fiscal'),
    control('change', 'Cambiar contraseña'), control('verify', 'Verificación'), control('help', 'Ayuda'), control('period', 'Período 2026'),
  ]));
  assert.deepEqual(safe.controls[0], control('field', 'Campo de texto', 'input', { redacted: true }));
  assert.deepEqual(safe.controls.slice(1).map(item => item.label), ['Ingresar con clave fiscal', 'Cambiar contraseña', 'Verificación', 'Ayuda', 'Período 2026']);
  assert.equal(JSON.stringify(safe).includes('never preserve'), false);
});

test('secret-looking values are removed individually without invalidating neighboring safe controls', () => {
  const safe = validateSnapshot(screen('com.any.app', [control('password', 'Contraseña: secret'), control('otp', 'Código 123456'),
    control('email', 'person@example.org'), control('submit', 'Ingresar'), control('home', 'Inicio')]));
  assert.deepEqual(safe.controls.map(item => item.id), ['submit', 'home']);
});

test('short form headings and labels remain distinct from protected input values', () => {
  const safe = validateSnapshot(screen('ar.gob.argentinagobar', [control('heading', 'Datos personales', 'heading'),
    control('nameLabel', 'Nombre', 'label'), control('nameInput', 'Private value', 'input'),
    control('surnameLabel', 'Apellido', 'label'), control('surnameInput', 'Private value', 'input'),
    control('period', 'Período 2026', 'label'), control('next', 'Siguiente')]));
  assert.deepEqual(safe.controls.map(item => item.role), ['heading', 'label', 'input', 'label', 'input', 'label', 'button']);
  assert.deepEqual(safe.controls.filter(item => item.role === 'input').map(item => item.label), ['Campo de texto', 'Campo de texto']);
  assert.equal(safe.controls.find(item => item.id === 'period').label, 'Período 2026');
});

test('input and protected-node metadata prevent pixels from entering the accepted snapshot', async () => {
  const image = { mimeType: 'image/jpeg', data: (await readFile(new URL('./fixtures/synthetic-screen.jpg', import.meta.url))).toString('base64') };
  const ordinary = { ...screen('com.any.app', [control('next', 'Siguiente')]), image };
  assert.ok(validateSnapshot(ordinary).image);
  assert.equal(Object.hasOwn(validateSnapshot({ ...ordinary, controls: [control('field', 'Private', 'input'), control('next', 'Siguiente')] }), 'image'), false);
  const protectedSnapshot = validateSnapshot({ ...ordinary, controls: [control('field', 'Private', 'button', { redacted: true }), control('next', 'Siguiente')] });
  assert.equal(protectedSnapshot.controls[0].label, 'Control protegido');
  assert.equal(Object.hasOwn(protectedSnapshot, 'image'), false);
  assert.equal(protectedSnapshot.controls[1].label, 'Siguiente');
});
