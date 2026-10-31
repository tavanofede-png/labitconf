import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { transform } from 'esbuild';

const source = await readFile(new URL('../src/demo/model.ts', import.meta.url), 'utf8');
const compiled = await transform(source, { loader: 'ts', format: 'esm', target: 'es2022' });
const { createInitialState, reducer, getGuidance, getDemoEmail, hasSharedLocation, taskStatus, DEMO_PASSWORD } = await import(`data:text/javascript;base64,${Buffer.from(compiled.code).toString('base64')}`);
const run = (state, ...actions) => actions.reduce(reducer, state);
const field = (name, value) => ({ type: 'SET_GMAIL_FIELD', field: name, value });

function gmailUntilPassword() {
  return run(createInitialState(), { type: 'START_TASK', task: 'gmail' }, field('firstName', 'Julia'), field('lastName', 'Ejemplo'), { type: 'NEXT' },
    field('day', '29'), field('month', '2'), field('year', '2000'), { type: 'NEXT' }, field('username', 'julia.demo'), { type: 'NEXT' });
}

test('group practice completes only after selecting contacts and naming the group; back does not create it', () => {
  let state = run(createInitialState(), { type: 'START_TASK', task: 'group' }, { type: 'OPEN_MENU' }, { type: 'NEW_GROUP' });
  state = reducer(state, { type: 'NEXT' });
  assert.equal(state.screen, 'wa-contacts'); assert.match(state.error, /dos contactos/); assert.deepEqual(state.completedTasks, []);
  state = run(state, { type: 'TOGGLE_CONTACT', contactId: 'ana' }, { type: 'TOGGLE_CONTACT', contactId: 'lucia' }, { type: 'NEXT' });
  assert.equal(state.screen, 'wa-group-name');
  state = reducer(state, { type: 'CREATE_GROUP' }); assert.equal(state.groupCreated, false); assert.ok(state.error);
  state = run(state, { type: 'SET_GROUP_NAME', value: '  Amigos de la plaza  ' }, { type: 'BACK' });
  assert.equal(state.screen, 'wa-contacts'); assert.equal(state.groupName, '  Amigos de la plaza  '); assert.equal(state.groupCreated, false);
  state = run(state, { type: 'NEXT' }, { type: 'CREATE_GROUP' });
  assert.equal(state.groupCreated, true); assert.equal(state.groupName, 'Amigos de la plaza'); assert.equal(state.chatId, 'group');
  assert.equal(state.screen, 'wa-chat'); assert.deepEqual(state.completedTasks, ['group']); assert.equal(taskStatus(state, 'group'), 'complete');
  state = reducer(state, { type: 'BACK' }); assert.equal(state.screen, 'wa-chats');
});

test('only known contacts can be selected and toggling a contact cannot duplicate it', () => {
  let state = run(createInitialState(), { type: 'START_TASK', task: 'group' }, { type: 'NEW_GROUP' });
  state = run(state, { type: 'TOGGLE_CONTACT', contactId: 'unknown' }, { type: 'TOGGLE_CONTACT', contactId: 'ana' }, { type: 'TOGGLE_CONTACT', contactId: 'ana' });
  assert.deepEqual(state.selectedContacts, []); assert.deepEqual(state.completedTasks, []);
  state = run(state, { type: 'TOGGLE_CONTACT', contactId: 'ana' }, { type: 'NEXT' });
  assert.equal(state.screen, 'wa-contacts'); assert.equal(getGuidance(state).targetId, 'contact-lucia');
});

test('location practice requires opening a chat, attachments, location and an explicit share', () => {
  let state = reducer(createInitialState(), { type: 'START_TASK', task: 'location' });
  state = run(state, { type: 'SHARE_LOCATION' }, { type: 'OPEN_LOCATION' }, { type: 'OPEN_ATTACHMENTS' });
  assert.equal(state.screen, 'wa-chats'); assert.equal(state.locationShared, false);
  state = run(state, { type: 'OPEN_CHAT', contactId: 'lucia' }, { type: 'OPEN_ATTACHMENTS' }, { type: 'OPEN_LOCATION' });
  assert.equal(state.screen, 'wa-location'); assert.deepEqual(state.completedTasks, []); assert.equal(getGuidance(state).targetId, 'share-location');
  state = run(state, { type: 'BACK' }, { type: 'OPEN_LOCATION' }); assert.equal(state.locationShared, false);
  state = reducer(state, { type: 'SHARE_LOCATION' });
  assert.equal(state.screen, 'wa-chat'); assert.equal(state.locationShared, true); assert.deepEqual(state.completedTasks, ['location']);
  state = reducer(state, { type: 'SHARE_LOCATION' }); assert.deepEqual(state.completedTasks, ['location']);
});

test('shared locations belong only to their recipient and returning to the original chat preserves its own card', () => {
  let state = run(createInitialState(), { type: 'OPEN_APP', app: 'whatsapp' }, { type: 'OPEN_CHAT', contactId: 'lucia' },
    { type: 'OPEN_ATTACHMENTS' }, { type: 'OPEN_LOCATION' }, { type: 'SHARE_LOCATION' });
  assert.equal(state.task, 'location'); assert.deepEqual(state.sharedLocationChatIds, ['lucia']); assert.equal(hasSharedLocation(state), true);
  assert.equal(getGuidance(state).targetId, 'demo-home');
  state = run(state, { type: 'BACK' }, { type: 'OPEN_CHAT', contactId: 'pedro' });
  assert.equal(state.locationShared, false); assert.equal(hasSharedLocation(state), false); assert.equal(getGuidance(state).targetId, 'attach');
  state = run(state, { type: 'BACK' }, { type: 'OPEN_CHAT', contactId: 'lucia' });
  assert.equal(state.locationShared, true); assert.equal(hasSharedLocation(state), true); assert.deepEqual(state.sharedLocationChatIds, ['lucia']);
  state = run(state, { type: 'START_TASK', task: 'group' }, { type: 'NEW_GROUP' }, { type: 'TOGGLE_CONTACT', contactId: 'ana' },
    { type: 'TOGGLE_CONTACT', contactId: 'lucia' }, { type: 'NEXT' }, { type: 'SET_GROUP_NAME', value: 'Grupo de ejemplo' }, { type: 'CREATE_GROUP' },
    { type: 'OPEN_ATTACHMENTS' }, { type: 'OPEN_LOCATION' }, { type: 'SHARE_LOCATION' });
  assert.equal(state.chatId, 'group'); assert.equal(hasSharedLocation(state), true); assert.deepEqual(state.sharedLocationChatIds, ['lucia', 'group']);
  // A newly shared group location must never appear as a send to an unrelated private chat.
  state = run(state, { type: 'START_TASK', task: 'location' }, { type: 'BACK' }, { type: 'OPEN_APP', app: 'whatsapp' });
  assert.deepEqual(state.sharedLocationChatIds, []);
  state = run(state, { type: 'OPEN_CHAT', contactId: 'group' }, { type: 'OPEN_ATTACHMENTS' }, { type: 'OPEN_LOCATION' }, { type: 'SHARE_LOCATION' },
    { type: 'BACK' }, { type: 'OPEN_CHAT', contactId: 'lucia' });
  assert.equal(hasSharedLocation(state, 'group'), true); assert.equal(hasSharedLocation(state), false); assert.equal(state.locationShared, false);
  assert.equal(getGuidance(state).targetId, 'attach');
});

test('Gmail practice validates birth and username and cannot finish without accepting practice terms', () => {
  let state = run(createInitialState(), { type: 'START_TASK', task: 'gmail' }, { type: 'NEXT' });
  assert.equal(state.screen, 'gmail-name'); assert.ok(state.error);
  state = reducer(state, field('firstName', 'Julia')); assert.equal(getGuidance(state).targetId, 'gmail-last-name');
  state = run(state, field('lastName', 'Ejemplo'), { type: 'NEXT' }, field('day', '29')); assert.equal(getGuidance(state).targetId, 'gmail-birth-month');
  state = reducer(state, field('month', '2')); assert.equal(getGuidance(state).targetId, 'gmail-birth-year');
  state = run(state, field('year', '2001'), { type: 'NEXT' });
  assert.equal(state.screen, 'gmail-birth'); assert.ok(state.error);
  state = run(state, field('year', '2000'), { type: 'NEXT' }, field('username', 'cuenta..demo'), { type: 'NEXT' });
  assert.equal(state.screen, 'gmail-username'); assert.ok(state.error);
  state = run(state, field('username', 'Julia.Demo'), { type: 'NEXT' }); assert.equal(state.screen, 'gmail-password');
  state = run(state, field('password', DEMO_PASSWORD), field('confirmPassword', DEMO_PASSWORD), { type: 'NEXT' }, { type: 'NEXT' });
  assert.equal(state.screen, 'gmail-terms'); assert.deepEqual(state.completedTasks, []); assert.ok(state.error);
  state = run(state, field('acceptedTerms', true), { type: 'NEXT' });
  assert.equal(state.screen, 'gmail-done'); assert.equal(getDemoEmail(state), 'julia.demo@gmail.example'); assert.deepEqual(state.completedTasks, ['gmail']);
  assert.equal(getGuidance(state).step, getGuidance(state).total);
});

test('Gmail rejects personal passwords instead of keeping them in practice state', () => {
  const state = gmailUntilPassword();
  const rejected = reducer(state, field('password', 'a-personal-password-that-must-not-be-kept'));
  assert.equal(rejected.gmail.password, ''); assert.match(rejected.error, /contraseña personal/);
  assert.equal(JSON.stringify(rejected).includes('a-personal-password-that-must-not-be-kept'), false);
  const next = reducer(rejected, { type: 'NEXT' }); assert.equal(next.screen, 'gmail-password');
});

test('home resumes a Gmail draft, restarting one practice preserves other completed practices, reset clears everything', () => {
  let state = gmailUntilPassword(); const prior = structuredClone(state);
  state = run(state, { type: 'HOME' }, { type: 'OPEN_APP', app: 'gmail' });
  assert.equal(state.screen, 'gmail-password'); assert.equal(state.gmail.firstName, prior.gmail.firstName);
  state = run(state, field('password', DEMO_PASSWORD), field('confirmPassword', DEMO_PASSWORD), { type: 'NEXT' }, field('acceptedTerms', true), { type: 'NEXT' }, { type: 'START_TASK', task: 'location' });
  assert.deepEqual(state.completedTasks, ['gmail']); assert.equal(taskStatus(state, 'location'), 'active');
  const reset = reducer(state, { type: 'RESET' }); assert.deepEqual(reset, createInitialState()); assert.notEqual(reset.gmail, createInitialState().gmail);
  assert.deepEqual(prior, gmailUntilPassword());
});

test('screen-scoped actions cannot mutate future Gmail fields or create completion by jumping ahead', () => {
  let state = createInitialState(); const original = structuredClone(state);
  state = run(state, field('acceptedTerms', true), field('username', 'jump.ahead'), { type: 'CREATE_GROUP' }, { type: 'SHARE_LOCATION' }, { type: 'NEXT' });
  assert.deepEqual(state, original);
  state = reducer(state, { type: 'START_TASK', task: 'gmail' });
  state = run(state, field('acceptedTerms', true), field('password', DEMO_PASSWORD), { type: 'NEXT' });
  assert.equal(state.screen, 'gmail-name'); assert.equal(state.gmail.acceptedTerms, false); assert.equal(state.gmail.password, '');
  assert.deepEqual(state.completedTasks, []);
});

test('the complete browser practice has no network, location or persistence side effects', t => {
  t.mock.method(globalThis, 'fetch', () => { throw new Error('Practice must remain local'); });
  let state = run(createInitialState(), { type: 'START_TASK', task: 'location' }, { type: 'OPEN_CHAT', contactId: 'lucia' }, { type: 'OPEN_ATTACHMENTS' }, { type: 'OPEN_LOCATION' }, { type: 'SHARE_LOCATION' });
  assert.equal(state.locationShared, true);
  state = run(state, { type: 'START_TASK', task: 'group' }, { type: 'NEW_GROUP' }, { type: 'TOGGLE_CONTACT', contactId: 'ana' }, { type: 'TOGGLE_CONTACT', contactId: 'lucia' }, { type: 'NEXT' }, { type: 'SET_GROUP_NAME', value: 'Grupo de prueba' }, { type: 'CREATE_GROUP' });
  assert.equal(state.groupCreated, true);
  assert.doesNotMatch(source, /\b(fetch|localStorage|sessionStorage|geolocation|indexedDB)\s*[.(]/);
});
