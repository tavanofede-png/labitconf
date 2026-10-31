import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { resolve, join, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

// Real React effects and DOM. Only the network, clock, and WebGL map are controlled.
const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/', pretendToBeVisual: true });
globalThis.window = dom.window; globalThis.document = dom.window.document;
globalThis.HTMLElement = dom.window.HTMLElement; globalThis.IS_REACT_ACT_ENVIRONMENT = true;
globalThis.sessionStorage = dom.window.sessionStorage;
window.matchMedia = () => ({ matches: false });
const { act, createElement, useState } = await import('react');
const { createRoot } = await import('react-dom/client');
const webRoot = fileURLToPath(new URL('../', import.meta.url));
const cacheRoot = resolve(webRoot, '../../.cache'); await mkdir(cacheRoot, { recursive: true });
const bundleRoot = await mkdtemp(join(cacheRoot, 'web-tests-'));
if (!bundleRoot.startsWith(cacheRoot + sep)) throw new Error('Test bundle must stay inside the workspace cache.');
after(async () => { dom.window.close(); await rm(bundleRoot, { recursive: true, force: true }); });
await build({ entryPoints: ['Family', 'Institution', 'App', 'Setup', 'DeviceAccess'].map(name => join(webRoot, `src/${name}.tsx`)), outdir: bundleRoot,
  outExtension: { '.js': '.mjs' }, bundle: true, platform: 'node', format: 'esm', jsx: 'automatic',
  external: ['react', 'react/jsx-runtime', '@phosphor-icons/react'],
  plugins: [{ name: 'no-webgl-in-dom-test', setup(builder) {
    builder.onResolve({ filter: /^\.\/TerritoryMap$/ }, () => ({ path: 'map', namespace: 'test-map' }));
    builder.onLoad({ filter: /.*/, namespace: 'test-map' }, () => ({ contents: 'export function TerritoryMap(){return null}', loader: 'js' }));
  } }] });
const { Family } = await import(pathToFileURL(join(bundleRoot, 'Family.mjs')));
const { Institution } = await import(pathToFileURL(join(bundleRoot, 'Institution.mjs')));
const { App } = await import(pathToFileURL(join(bundleRoot, 'App.mjs')));
const { Connection } = await import(pathToFileURL(join(bundleRoot, 'Setup.mjs')));
const { DeviceAccess } = await import(pathToFileURL(join(bundleRoot, 'DeviceAccess.mjs')));
const mapBundle = join(bundleRoot, 'map');
await build({ entryPoints: [join(webRoot, 'src/TerritoryMap.tsx')], outdir: mapBundle,
  outExtension: { '.js': '.mjs' }, bundle: true, platform: 'node', format: 'esm', jsx: 'automatic',
  define: { 'import.meta.env.VITE_MAP_TILE_URL': 'undefined' }, external: ['react', 'react/jsx-runtime', '@phosphor-icons/react'],
  plugins: [{ name: 'delayed-maplibre', setup(builder) {
    builder.onResolve({ filter: /^maplibre-gl$/ }, () => ({ path: 'maplibre', namespace: 'delayed-maplibre' }));
    builder.onLoad({ filter: /.*/, namespace: 'delayed-maplibre' }, () => ({ contents: 'const source = await globalThis.__mapModuleReady; export const Map = source.Map; export const Marker = source.Marker; export const NavigationControl = source.NavigationControl; export const setWorkerUrl = source.setWorkerUrl;', loader: 'js' }));
    builder.onResolve({ filter: /^maplibre-gl\/dist\/.*worker/ }, () => ({ path: 'worker', namespace: 'test-worker' }));
    builder.onLoad({ filter: /.*/, namespace: 'test-worker' }, () => ({ contents: 'export default "test-worker-url";', loader: 'js' }));
  } }] });
const { TerritoryMap } = await import(pathToFileURL(join(mapBundle, 'TerritoryMap.mjs')));
const response = data => new Response(JSON.stringify(data), { headers: { 'Content-Type': 'application/json' } });
const defer = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
function button(container, label) {
  const found = [...container.querySelectorAll('button')].filter(element => element.textContent.trim() === label);
  assert.equal(found.length, 1, `Expected one button ${label}`); return found[0];
}
async function mount(t, component) {
  const container = document.createElement('div'); document.body.append(container); const root = createRoot(container);
  t.after(async () => { await act(async () => root.unmount()); container.remove(); });
  await act(async () => root.render(component)); return { root, container };
}
const summary = id => ({ group: { id, name: `Family ${id}` }, members: [], alerts: [] });

test('A03 a late invitation cannot appear after changing the family identity, even if HTTP ignores abort', async t => {
  const pending = defer(); let requestSignal; let switchIdentity;
  t.mock.method(globalThis, 'fetch', async (path, options) => {
    if (path === '/api/family/summary') return response(summary(options.headers.Authorization.endsWith('group-A') ? 'A' : 'B'));
    assert.equal(path, '/api/family/invitations'); requestSignal = options.signal; return pending.promise;
  });
  function Host() { const [token, setToken] = useState('group-A'); switchIdentity = setToken; return createElement(Family, { token, setToken }); }
  const { container } = await mount(t, createElement(Host));
  await act(async () => button(container, 'Generar invitación').click());
  await act(async () => switchIdentity('group-B'));
  assert.equal(requestSignal.aborted, true);
  await act(async () => pending.resolve(response({ invitationCode: 'PRIVATE-CODE-A', expiresAt: '2099-01-01T00:00:00Z' })));
  assert.match(container.textContent, /Family B/); assert.doesNotMatch(container.textContent, /PRIVATE-CODE-A/);
  assert.equal(button(container, 'Generar invitación').disabled, false);
});

function overview(active) {
  return { territoryId: 'AR-C', minimumCohort: 5, suppressed: false, participants: active,
    metrics: { reportedFlows: null, practiceReports: null, completedFlows: null, autonomyChecks: null, accompaniedMinutes: null, helpRequests: null },
    activity: { activePeople: active, startedFlows: active }, flows: [], updatedAt: new Date().toISOString() };
}
const health = { openarg: { configured: false }, privacy: { minimumCohort: 5 } };
function captureIntervals(t) {
  const timers = new Map(); let id = 0;
  t.mock.method(window, 'setInterval', (callback, interval) => { timers.set(++id, { callback, interval }); return id; });
  t.mock.method(window, 'clearInterval', key => timers.delete(key)); return timers;
}
function activeValue(container) { return container.querySelector('.stat-value').textContent; }

test('A08 visible metrics refresh after activity and export fetches a fresh snapshot rather than stale state', async t => {
  const timers = captureIntervals(t); let active = 5; let calls = 0; let exported;
  t.mock.method(globalThis, 'fetch', async path => { assert.match(path, /^\/api\/institution\/overview/); calls++; return response(overview(active)); });
  t.mock.method(URL, 'createObjectURL', blob => { exported = blob; return 'blob:qa'; });
  t.mock.method(URL, 'revokeObjectURL', () => {});
  t.mock.method(dom.window.HTMLAnchorElement.prototype, 'click', () => {});
  const { container } = await mount(t, createElement(Institution, { token: 'institution-QA', health, connect() {} }));
  assert.equal(activeValue(container), '5'); assert.equal(timers.size, 1);
  const timer = [...timers.values()][0]; assert.equal(timer.interval, 30_000);
  active = 6; await act(async () => timer.callback()); assert.equal(activeValue(container), '6');
  active = 7; await act(async () => button(container, 'Exportar resumen').click());
  assert.equal(activeValue(container), '7'); assert.equal(calls, 3);
  assert.equal(JSON.parse(await exported.text()).overview.activity.activePeople, 7);
});

test('A08 a delayed poll cannot overwrite the newer exported view; unmount aborts and removes the timer', async t => {
  const timers = captureIntervals(t); const late = defer(); let calls = 0; let signal;
  t.mock.method(globalThis, 'fetch', async (_path, options) => {
    signal = options.signal; calls++;
    return calls === 2 ? late.promise : response(overview(calls === 1 ? 5 : 8));
  });
  t.mock.method(URL, 'createObjectURL', () => 'blob:qa'); t.mock.method(URL, 'revokeObjectURL', () => {});
  t.mock.method(dom.window.HTMLAnchorElement.prototype, 'click', () => {});
  const { root, container } = await mount(t, createElement(Institution, { token: 'institution-QA', health, connect() {} }));
  await act(async () => [...timers.values()][0].callback());
  await act(async () => button(container, 'Exportar resumen').click()); assert.equal(activeValue(container), '8');
  await act(async () => late.resolve(response(overview(6)))); assert.equal(activeValue(container), '8');
  await act(async () => root.unmount()); assert.equal(timers.size, 0); assert.equal(signal.aborted, true);
});

test('B07 skip to content keeps the family section, draft and DOM while moving keyboard focus', async t => {
  captureIntervals(t);
  window.history.replaceState(null, '', '/#familia');
  t.mock.method(globalThis, 'fetch', async path => { assert.equal(path, '/api/health'); return response(health); });
  const { container } = await mount(t, createElement(App));
  const editor = container.querySelector('#family-name'); assert.ok(editor);
  editor.value = 'Borrador de familia';
  await act(async () => container.querySelector('.skip-link').click());
  assert.equal(window.location.hash, '#familia');
  assert.equal(document.activeElement, container.querySelector('main'));
  assert.equal(container.querySelector('#family-name'), editor);
  assert.equal(editor.value, 'Borrador de familia');
  assert.equal(container.querySelector('[aria-current="page"]').getAttribute('href'), '#familia');
});

test('B06 a territory changed before MapLibre loads initializes the center, marker and zoom from the current selection', async t => {
  const ready = defer(); globalThis.__mapModuleReady = ready.promise;
  const maps = []; const markers = [];
  class Map {
    constructor(options) { this.center = options.center; this.zoom = options.zoom; this.scrollZoom = { disable() {} }; this.removed = false; maps.push(this); }
    addControl() {} on() {} areTilesLoaded() { return true; }
    flyTo(options) { this.center = options.center; this.zoom = options.zoom; }
    remove() { this.removed = true; }
  }
  class Marker {
    constructor() { this.removed = false; markers.push(this); }
    setLngLat(point) { this.point = point; return this; }
    addTo() { return this; } remove() { this.removed = true; }
  }
  const caba = { id: 'AR-C', name: 'CABA', point: [-58.44, -34.61] };
  const salta = { id: 'AR-A', name: 'Salta', point: [-65.41, -24.79] };
  const { root, container } = await mount(t, createElement(TerritoryMap, { territory: caba }));
  await act(async () => root.render(createElement(TerritoryMap, { territory: salta })));
  assert.equal(maps.length, 0);
  await act(async () => ready.resolve({ Map, Marker, NavigationControl: class {}, setWorkerUrl() {} }));
  assert.equal(maps.length, 1); assert.match(container.textContent, /Salta/);
  assert.deepEqual(maps[0].center, salta.point); assert.deepEqual(markers[0].point, salta.point); assert.equal(maps[0].zoom, 8.6);
  await act(async () => root.render(createElement(TerritoryMap, { territory: caba })));
  assert.deepEqual(maps[0].center, caba.point); assert.deepEqual(markers[0].point, caba.point); assert.equal(maps[0].zoom, 10.2);
  await act(async () => root.unmount()); assert.equal(maps[0].removed, true); assert.equal(markers[0].removed, true);
});

const connectedStatus = name => ({ configured: true, connected: true, endpoint: 'https://mcp.openarg.org/mcp', tools: [{ name, description: `Description ${name}` }] });

test('B08 a late MCP verification cannot restore connected state after logout, even if fetch ignores abort', async t => {
  const late = defer(); let signal;
  t.mock.method(globalThis, 'fetch', async (path, options) => {
    if (path === '/api/institution/devices') return response({ devices: [] });
    assert.equal(path, '/api/openarg/status'); signal = options.signal; return late.promise;
  });
  function Host() { const [token, setToken] = useState('operator-A'); return createElement(Connection, { token, setToken, health, refreshHealth() {} }); }
  const { container } = await mount(t, createElement(Host));
  await act(async () => button(container, 'Verificar conexión real').click());
  await act(async () => button(container, 'Cerrar acceso institucional').click());
  assert.equal(signal.aborted, true);
  await act(async () => late.resolve(response(connectedStatus('PRIVATE-OLD-TOOLS'))));
  assert.match(container.textContent, /Acceso pendiente/);
  assert.doesNotMatch(container.textContent, /MCP conectado|PRIVATE-OLD-TOOLS|Verificando MCP/);
});

test('B08 returning to the same credential cannot accept an older verification epoch or its errors', async t => {
  const old = defer(); let switchIdentity; let count = 0;
  t.mock.method(globalThis, 'fetch', async (path) => {
    if (path === '/api/institution/devices') return response({ devices: [] });
    assert.equal(path, '/api/openarg/status'); return ++count === 1 ? old.promise : response(connectedStatus('CURRENT-TOOLS'));
  });
  function Host() { const [token, setToken] = useState('operator-A'); switchIdentity = setToken; return createElement(Connection, { token, setToken, health, refreshHealth() {} }); }
  const { container } = await mount(t, createElement(Host));
  await act(async () => button(container, 'Verificar conexión real').click());
  await act(async () => switchIdentity('operator-B'));
  await act(async () => switchIdentity('operator-A'));
  assert.equal(button(container, 'Verificar conexión real').disabled, false);
  await act(async () => button(container, 'Verificar conexión real').click());
  await act(async () => old.resolve(new Response(JSON.stringify({ error: { message: 'PRIVATE-OLD-ERROR' } }), { status: 503 })));
  assert.match(container.textContent, /CURRENT-TOOLS/); assert.doesNotMatch(container.textContent, /PRIVATE-OLD-ERROR/);
});

test('R01 an enrollment code cannot cross operator identities after a late response', async t => {
  const late = defer(); let switchIdentity; let signal;
  t.mock.method(globalThis, 'fetch', async (path, options) => {
    if (path === '/api/institution/devices') return response({ devices: options.headers.Authorization.endsWith('operator-A') ? [{ deviceId: 'device-A', realtimeApproved: true }] : [] });
    assert.equal(path, '/api/institution/device-invitations'); signal = options.signal; return late.promise;
  });
  function Host() { const [token, setToken] = useState('operator-A'); switchIdentity = setToken; return createElement(DeviceAccess, { token }); }
  const { container } = await mount(t, createElement(Host));
  assert.match(container.textContent, /device-A/);
  await act(async () => button(container, 'Generar código para teléfono').click());
  await act(async () => switchIdentity('operator-B'));
  assert.equal(signal.aborted, true);
  await act(async () => late.resolve(response({ enrollmentCode: 'PRIVATE-CODE-A', expiresAt: '2099-01-01T00:00:00Z' })));
  assert.doesNotMatch(container.textContent, /PRIVATE-CODE-A|device-A/);
  assert.equal(button(container, 'Generar código para teléfono').disabled, false);
});

test('R01 a pending device list cannot reveal the previous operator records and is aborted on unmount', async t => {
  const late = defer(); let switchIdentity; const signals = [];
  t.mock.method(globalThis, 'fetch', async (path, options) => {
    assert.equal(path, '/api/institution/devices'); signals.push(options.signal);
    return options.headers.Authorization.endsWith('operator-A') ? late.promise : response({ devices: [] });
  });
  function Host() { const [token, setToken] = useState('operator-A'); switchIdentity = setToken; return createElement(DeviceAccess, { token }); }
  const { root, container } = await mount(t, createElement(Host));
  await act(async () => switchIdentity('operator-B')); assert.equal(signals[0].aborted, true);
  await act(async () => late.resolve(response({ devices: [{ deviceId: 'PRIVATE-DEVICE-A', realtimeApproved: true }] })));
  assert.doesNotMatch(container.textContent, /PRIVATE-DEVICE-A/);
  assert.match(container.textContent, /Todavía no hay teléfonos/);
  await act(async () => root.unmount()); assert.equal(signals[1].aborted, true);
});

test('R01 approved device access can be revoked and the refreshed state removes its revoke action', async t => {
  let approved = true; const calls = [];
  t.mock.method(globalThis, 'fetch', async (path, options) => {
    calls.push({ path, method: options.method, authorization: options.headers.Authorization });
    if (path === '/api/institution/device-invitations') return response({ enrollmentCode: 'ONE-USE-CODE', expiresAt: '2099-01-01T00:00:00Z' });
    if (path === '/api/institution/devices') return response({ devices: [{ deviceId: 'device-QA', realtimeApproved: approved }] });
    assert.equal(path, '/api/institution/devices/device-QA/revoke'); assert.equal(options.method, 'POST'); approved = false; return response({ ok: true });
  });
  const { container } = await mount(t, createElement(DeviceAccess, { token: 'operator-QA' }));
  await act(async () => button(container, 'Generar código para teléfono').click());
  assert.match(container.textContent, /ONE-USE-CODE/);
  await act(async () => button(container, 'Revocar asistencia').click());
  assert.match(container.textContent, /Asistencia sin autorización/);
  assert.equal([...container.querySelectorAll('button')].some(element => element.textContent.trim() === 'Revocar asistencia'), false);
  assert.equal(calls.every(call => call.authorization === 'Bearer operator-QA'), true);
});
