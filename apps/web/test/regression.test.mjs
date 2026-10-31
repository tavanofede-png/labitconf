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
const { act, createElement, useState } = await import('react');
const { createRoot } = await import('react-dom/client');
const webRoot = fileURLToPath(new URL('../', import.meta.url));
const cacheRoot = resolve(webRoot, '../../.cache'); await mkdir(cacheRoot, { recursive: true });
const bundleRoot = await mkdtemp(join(cacheRoot, 'web-tests-'));
if (!bundleRoot.startsWith(cacheRoot + sep)) throw new Error('Test bundle must stay inside the workspace cache.');
after(async () => { dom.window.close(); await rm(bundleRoot, { recursive: true, force: true }); });
await build({ entryPoints: [join(webRoot, 'src/Family.tsx'), join(webRoot, 'src/Institution.tsx')], outdir: bundleRoot,
  outExtension: { '.js': '.mjs' }, bundle: true, platform: 'node', format: 'esm', jsx: 'automatic',
  external: ['react', 'react/jsx-runtime', '@phosphor-icons/react'],
  plugins: [{ name: 'no-webgl-in-dom-test', setup(builder) {
    builder.onResolve({ filter: /^\.\/TerritoryMap$/ }, () => ({ path: 'map', namespace: 'test-map' }));
    builder.onLoad({ filter: /.*/, namespace: 'test-map' }, () => ({ contents: 'export function TerritoryMap(){return null}', loader: 'js' }));
  } }] });
const { Family } = await import(pathToFileURL(join(bundleRoot, 'Family.mjs')));
const { Institution } = await import(pathToFileURL(join(bundleRoot, 'Institution.mjs')));
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
