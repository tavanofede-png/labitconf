import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import { resolve, join, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { build } from 'esbuild';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'https://demo.example/', pretendToBeVisual: true });
globalThis.window = dom.window; globalThis.document = dom.window.document;
globalThis.HTMLElement = dom.window.HTMLElement; globalThis.IS_REACT_ACT_ENVIRONMENT = true;
const { act, createElement } = await import('react');
const { createRoot } = await import('react-dom/client');
const webRoot = fileURLToPath(new URL('../', import.meta.url));
const cacheRoot = resolve(webRoot, '../../.cache'); await mkdir(cacheRoot, { recursive: true });
const bundleRoot = await mkdtemp(join(cacheRoot, 'demo-ui-tests-'));
if (!bundleRoot.startsWith(cacheRoot + sep)) throw new Error('Test output outside workspace cache');
await build({ entryPoints: [join(webRoot, 'src/demo/Demo.tsx')], outfile: join(bundleRoot, 'Demo.mjs'), bundle: true,
  platform: 'node', format: 'esm', jsx: 'automatic', loader: { '.css': 'empty' }, external: ['react', 'react/jsx-runtime', '@phosphor-icons/react'] });
const { Demo } = await import(pathToFileURL(join(bundleRoot, 'Demo.mjs')));
after(async () => { dom.window.close(); if (bundleRoot.startsWith(cacheRoot + sep)) await rm(bundleRoot, { recursive: true, force: true }); });

async function mount(t, view = 'phone') {
  const container = document.createElement('div'); document.body.append(container); const root = createRoot(container);
  t.after(async () => { await act(async () => root.unmount()); container.remove(); });
  await act(async () => root.render(createElement(Demo, { view }))); return { container, root };
}
const click = async element => { assert.ok(element, 'Expected control'); await act(async () => element.click()); };
const button = (container, label) => [...container.querySelectorAll('button')].find(element => element.textContent.trim() === label);
const choose = (container, label) => [...container.querySelectorAll('.d-task-option')].find(element => element.querySelector('strong')?.textContent === label);
async function fill(container, id, text) {
  const input = container.querySelector(`#${id}`); assert.ok(input, `Missing field ${id}`);
  await act(async () => { Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(input, text); input.dispatchEvent(new dom.window.Event('input', { bubbles: true })); }); return input;
}

test('phone group flow requires selected contacts and a name before creating the practice group', async t => {
  const { container } = await mount(t);
  await click(choose(container, 'Crear un grupo')); await click(container.querySelector('#wa-menu')); await click(container.querySelector('#new-group'));
  await click(container.querySelector('#contact-lucia')); await click(container.querySelector('#contacts-next'));
  assert.match(container.querySelector('[role=alert]').textContent, /al menos dos/);
  await click(container.querySelector('#contact-ana')); await click(container.querySelector('#contacts-next')); await click(container.querySelector('#create-group'));
  assert.match(container.querySelector('[role=alert]').textContent, /nombre/);
  await fill(container, 'group-name', 'Familia de práctica'); await click(container.querySelector('#create-group'));
  assert.match(container.querySelector('.d-chat-thread').textContent, /Creaste el grupo «Familia de práctica»/);
  assert.match(container.querySelector('.d-sent-banner').textContent, /Grupo de práctica creado/);
  await click(container.querySelector('#attach')); await click(container.querySelector('#location-option'));
  assert.match(container.querySelector('.d-location-caption').textContent, /Familia de práctica/);
  await click(container.querySelector('#share-location'));
  assert.ok(container.querySelector('.d-location-preview'));
  assert.match(container.querySelector('.d-sent-banner').textContent, /Ubicación de ejemplo compartida/);
});

test('web location flow sends only its synthetic point after the explicit share action', async t => {
  const { container } = await mount(t, 'web');
  t.mock.method(globalThis, 'fetch', () => { throw new Error('Practice must not call remote services'); });
  await click(choose(container, 'Compartir ubicación')); await click(container.querySelector('#chat-lucia')); await click(container.querySelector('#attach')); await click(container.querySelector('#location-option'));
  assert.match(container.querySelector('.d-location-caption').textContent, /no solicita GPS/); assert.equal(container.querySelector('.d-location-preview'), null);
  await click(container.querySelector('#share-location'));
  assert.match(container.querySelector('.d-location-preview').textContent, /Plaza del Congreso/);
  assert.match(container.querySelector('.d-sent-banner').textContent, /Ubicación de ejemplo compartida/);
  await click(container.querySelector('[aria-label="Volver en WhatsApp"]'));
  await click(container.querySelector('#chat-pedro'));
  assert.equal(container.querySelector('.d-location-preview'), null);
  assert.equal(container.querySelector('.d-sent-banner'), null);
  await click(container.querySelector('[aria-label="Volver en WhatsApp"]'));
  await click(container.querySelector('#chat-lucia'));
  assert.ok(container.querySelector('.d-location-preview'));
});

test('Gmail preserves typing focus, uses readonly practice passwords and requires consent', async t => {
  const { container } = await mount(t); await click(choose(container, 'Crear un Gmail'));
  const original = container.querySelector('#gmail-first-name'); original.focus();
  await fill(container, 'gmail-first-name', 'Ma'); await fill(container, 'gmail-first-name', 'María');
  assert.equal(document.activeElement, original); assert.equal(container.querySelector('#gmail-first-name'), original);
  await fill(container, 'gmail-last-name', 'López'); await click(container.querySelector('#gmail-next'));
  await click(button(container, 'Usar fecha de ejemplo')); await click(container.querySelector('#gmail-next'));
  await click(button(container, 'Usar dirección de ejemplo')); await click(container.querySelector('#gmail-next'));
  const passwords = [...container.querySelectorAll('input[type=password]')]; assert.equal(passwords.length, 2); assert.ok(passwords.every(input => input.readOnly));
  await click(container.querySelector('#gmail-password')); await click(container.querySelector('#gmail-next')); await click(container.querySelector('#gmail-next'));
  assert.match(container.querySelector('[role=alert]').textContent, /Aceptá/);
  await click(container.querySelector('input[type=checkbox]')); await click(container.querySelector('#gmail-next'));
  assert.match(container.querySelector('.d-google-review').textContent, /maria\.lopez64@gmail\.example/);
  assert.match(container.querySelector('.d-tick-line').textContent, /No se creó una cuenta real/);
});

test('changing phone/tablet/web retains the draft and reset clears the exercise', async t => {
  const { container, root } = await mount(t);
  await click(choose(container, 'Crear un grupo')); await click(container.querySelector('#wa-menu')); await click(container.querySelector('#new-group'));
  await click(container.querySelector('#contact-lucia')); await click(container.querySelector('#contact-ana')); await click(container.querySelector('#contacts-next')); await fill(container, 'group-name', 'Mi grupo');
  const originalInput = container.querySelector('#group-name'); const originalHash = window.location.hash;
  await click(container.querySelector('.d-skip-link'));
  assert.equal(document.activeElement, container.querySelector('#demo-content'));
  assert.equal(window.location.hash, originalHash); assert.equal(container.querySelector('#group-name'), originalInput);
  await click(button(container, 'Tablet')); assert.ok(container.querySelector('.d-phone.is-tablet')); assert.equal(container.querySelector('#group-name').value, 'Mi grupo');
  await act(async () => root.render(createElement(Demo, { view: 'web' })));
  assert.equal(container.querySelector('.d-phone'), null); assert.ok(container.querySelector('.d-normal-app')); assert.equal(container.querySelector('#group-name').value, 'Mi grupo');
  await click(button(container, 'Reiniciar')); assert.ok(container.querySelector('.d-app-launcher')); assert.equal(container.querySelector('#group-name'), null);
});
