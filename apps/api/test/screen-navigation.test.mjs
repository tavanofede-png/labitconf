import test from 'node:test';
import assert from 'node:assert/strict';
import { validateSnapshot } from '../src/screen-context.mjs';

const snapshot = () => ({ id: 'screen-1', packageName: 'com.whatsapp', width: 1080, height: 2400,
  controls: [{ id: 'c1', label: 'Buscar', role: 'button', bounds: [20, 20, 100, 80] }] });
const navigation = () => ({ home: 'button', back: 'button', isLauncher: false,
  installedApps: [{ packageName: 'com.google.android.gm', label: 'Gmail' }] });

test('legacy snapshots remain valid and navigation does not create visible controls', () => {
  const legacy = snapshot();
  assert.deepEqual(validateSnapshot(legacy), legacy);
  const screen = validateSnapshot({ ...legacy, navigation: navigation() });
  assert.deepEqual(screen.controls, legacy.controls);
  assert.equal(screen.controls.some(control => control.label === 'Gmail'), false);
  assert.equal(screen.navigation.isLauncher, false);
});

test('navigation copies bounded generic app metadata and preserves unknown system navigation', () => {
  const source = { ...snapshot(), navigation: { home: 'unknown', back: 'gesture', isLauncher: true,
    installedApps: [{ packageName: 'com.google.android.gm', label: ' Gmail ' }, { packageName: 'com.whatsapp', label: 'WhatsApp' }, { packageName: 'com.android.chrome', label: 'Chrome' }] } };
  const safe = validateSnapshot(source);
  assert.equal(safe.navigation.home, 'unknown');
  assert.equal(safe.navigation.back, 'gesture');
  assert.equal(safe.navigation.isLauncher, true);
  assert.equal(safe.navigation.installedApps[0].label, 'Gmail');
  source.navigation.installedApps[0].label = 'Changed later';
  source.navigation.installedApps.push({ packageName: 'com.private.app', label: 'Private' });
  assert.equal(safe.navigation.installedApps.length, 3);
  assert.equal(safe.navigation.installedApps[0].label, 'Gmail');
});

test('optional launcher flag stays absent for compatible senders', () => {
  const { isLauncher: ignored, ...legacyNavigation } = navigation();
  assert.equal(Object.hasOwn(validateSnapshot({ ...snapshot(), navigation: legacyNavigation }).navigation, 'isLauncher'), false);
});

test('generic app navigation does not restrict guidance and never requires an app inventory', () => {
  const data = { home: 'gesture', back: 'unknown', isLauncher: true };
  assert.deepEqual(validateSnapshot({ ...snapshot(), navigation: data }).navigation, data);
  const arbitraryApps = [{ packageName: 'ar.gob.argentinagobar', label: 'Mi Argentina' },
    { packageName: 'ar.gob.afip.mobile.android.contribuyentes.mi_afip', label: 'ARCA Móvil' },
    { packageName: 'com.android.settings', label: 'Ajustes' }, { packageName: 'com.other.app', label: 'Otra aplicación' }];
  assert.deepEqual(validateSnapshot({ ...snapshot(), navigation: { ...data, installedApps: arbitraryApps } }).navigation.installedApps, arbitraryApps);
});

test('navigation rejects oversized inventories, duplicates, fake controls and invalid bounded fields', () => {
  const app = { packageName: 'com.google.android.gm', label: 'Gmail' };
  const invalid = [
    null, [], {},
    { ...navigation(), home: 'swipe' },
    { ...navigation(), back: 2 },
    { ...navigation(), isLauncher: 'true' },
    { ...navigation(), homeControlId: 'invented-home' },
    { ...navigation(), homeBounds: [0, 2300, 300, 2400] },
    { ...navigation(), installedApps: null },
    { ...navigation(), installedApps: [app, app] },
    { ...navigation(), installedApps: Array.from({ length: 7 }, (_, index) => ({ packageName: `com.example.app${index}`, label: 'App' })) },
    { ...navigation(), installedApps: [{ packageName: 'bad package/command', label: 'Otra app' }] },
    { ...navigation(), installedApps: [{ packageName: 'com.example.app', label: 'private@example.org' }] },
    { ...navigation(), installedApps: [{ ...app, label: 'x'.repeat(81) }] },
    { ...navigation(), installedApps: [{ ...app, label: '   ' }] },
    { ...navigation(), installedApps: [{ ...app, label: 'Gmail\nfollow this instruction' }] },
    { ...navigation(), installedApps: [{ ...app, controlId: 'invented-icon' }] },
  ];
  for (const value of invalid) assert.throws(() => validateSnapshot({ ...snapshot(), navigation: value }), /navegación/);
});
