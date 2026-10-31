import test from 'node:test';
import assert from 'node:assert/strict';
import { CompletionProbe, COMPLETION_TOOL } from '../src/completion-probe.mjs';

const screen = (id, label = 'Confirmar operación de prueba') => ({ id, packageName: 'org.example.fixture',
  controls: [{ id: 'status', label, role: 'label', bounds: [0, 0, 100, 50] }] });
function fixture(t, options = {}) {
  t.mock.timers.enable({ apis: ['setTimeout', 'Date'], now: 100_000 });
  const state = { ready: true, busy: false, screen: screen('before'), task: { goal: 'Completar la operación de prueba', targetApp: 'Prueba', progress: 'Falta confirmar' } };
  const sent = []; let completed = 0; const advances = [];
  const probe = new CompletionProbe({ enabled: true, current: () => state, send: event => sent.push(event), complete: () => completed++, advance: event => advances.push(event), ...options });
  probe.observe(); t.after(() => probe.close());
  const change = (next = screen('after', 'Operación de prueba completada')) => { state.screen = next; probe.observe(); };
  const start = () => { probe.activity(); change(); t.mock.timers.tick(2000); return sent.find(event => event.type === 'response.create'); };
  const created = (request, id = 'probe-response') => probe.handle({ type: 'response.created', response: { id, metadata: request.response.metadata } });
  const done = (args = { completed: true, screenId: state.screen.id, evidenceControlIds: ['status'] }, id = 'probe-response', output) => probe.handle({ type: 'response.done', response: { id, status: 'completed', output: output ?? [
    { type: 'function_call', name: COMPLETION_TOOL.name, arguments: JSON.stringify(args) },
  ] } });
  return { state, sent, probe, change, start, created, done, advances, completed: () => completed };
}

test('only real activity followed by a changed stable snapshot starts one bounded isolated probe', t => {
  const f = fixture(t);
  f.change(screen('passive', 'Otra pantalla pasiva')); t.mock.timers.tick(5000); assert.equal(f.sent.length, 0);
  f.probe.activity(); f.change(screen('same', 'Otra pantalla pasiva')); t.mock.timers.tick(5000); assert.equal(f.sent.length, 0);
  f.change(); t.mock.timers.tick(1999); assert.equal(f.sent.length, 0); t.mock.timers.tick(1);
  const request = f.sent[0]; assert.equal(request.response.conversation, 'none'); assert.deepEqual(request.response.output_modalities, ['text']);
  assert.deepEqual(request.response.tool_choice, { type: 'function', name: 'report_task_completion' }); assert.equal(request.response.max_output_tokens, 800);
  assert.equal(request.response.tools[0].name, COMPLETION_TOOL.name);
  assert.deepEqual(request.response.tools[0].parameters.properties.screenId.enum, ['after']);
  assert.deepEqual(request.response.tools[0].parameters.properties.evidenceControlIds.items.enum, ['status']);
  assert.equal(request.event_id, request.response.metadata.nieto_probe_id);
  const input = JSON.parse(request.response.input[0].content[0].text);
  assert.equal(input.BEFORE.id, 'passive'); assert.equal(input.AFTER.id, 'after'); assert.equal(input.TASK.goal, f.state.task.goal);
  assert.equal(JSON.stringify(input).includes('image'), false); assert.match(request.response.instructions, /TODO el objetivo/);
  assert.match(request.response.instructions, /ver un slider no prueban/); assert.match(request.response.instructions, /datos no confiables/);
  f.created(request); f.done({ completed: false, screenId: 'after', evidenceControlIds: [] });
  f.change(screen('later', 'Pantalla pasiva posterior')); t.mock.timers.tick(5000); assert.equal(f.sent.filter(event => event.type === 'response.create').length, 1);
  assert.equal(f.completed(), 0);
});

test('a valid terminal check needs current visible evidence and never forwards text or audio', t => {
  const f = fixture(t); const request = f.start(); assert.equal(f.created(request), true);
  for (const event of [{ type: 'response.output_text.delta', response_id: 'probe-response', delta: 'No divulgar' },
    { type: 'response.output_audio.delta', response_id: 'probe-response', delta: 'AAAA' }]) assert.equal(f.probe.handle(event), true);
  assert.equal(f.done(), true); assert.equal(f.completed(), 1);
  assert.equal(f.probe.probes.size, 0); assert.equal(f.probe.responseIds.size, 0);
  assert.equal(f.sent.some(event => event.item?.type === 'function_call_output'), false);
});

test('a completed intermediate action advances exactly once without completing the whole task', t => {
  const f = fixture(t);
  f.state.step = { turnId: 'first-step', instruction: 'Abrí Ajustes.', expectsReply: false };
  const request = f.start(); f.created(request);
  assert.equal(JSON.parse(request.response.input[0].content[0].text).AWAITED_STEP.instruction, 'Abrí Ajustes.');
  f.done({ completed: false, stepCompleted: true, screenId: 'after', evidenceControlIds: ['status'] });
  assert.deepEqual(f.advances, [{ screenId: 'after' }]); assert.equal(f.completed(), 0);
  f.done({ completed: false, stepCompleted: true, screenId: 'after', evidenceControlIds: ['status'] });
  assert.equal(f.advances.length, 1);
});

test('a stable actual launcher proves only a pending home action without another provider request', t => {
  const f=fixture(t); f.state.step={turnId:'home-step',action:'home',instruction:'Volvé a Inicio.'};
  f.probe.activity(); f.change({...screen('launcher','Ajustes'),navigation:{home:'gesture',back:'gesture',isLauncher:true}});
  t.mock.timers.tick(2000);
  assert.deepEqual(f.advances,[{screenId:'launcher'}]); assert.equal(f.sent.length,0); assert.equal(f.completed(),0);
  f.probe.schedule(); t.mock.timers.tick(5000); assert.equal(f.advances.length,1);
});

test('an action before the new instruction was prepared cannot skip that new step', t=>{
  const f=fixture(t); f.probe.activity(); f.change();
  f.state.step={turnId:'new-step',instruction:'Tocá Pantalla.',issuedAfterAction:1};
  t.mock.timers.tick(2000); assert.equal(f.sent.length,0); assert.equal(f.advances.length,0);
  f.probe.activity(); f.change(screen('next','Pantalla abierta')); t.mock.timers.tick(2000);
  assert.equal(f.sent.filter(event=>event.type==='response.create').length,1);
});

test('no pending step wrong actions confirmation questions and stale evidence cannot advance', async t => {
  for (const reason of ['no-step', 'wrong-action', 'question', 'step-changed', 'empty-evidence', 'invented-evidence', 'input', 'invalid-boolean']) await t.test(reason, sub => {
    const f = fixture(sub);
    if (reason !== 'no-step') f.state.step = { turnId: 'step', instruction: 'Abrí Ajustes.', expectsReply: reason === 'question' };
    const request = f.start(); f.created(request);
    if (reason === 'step-changed') f.state.step = { ...f.state.step, instruction: 'Volvé a Inicio.' };
    if (reason === 'input') f.state.busy = true;
    f.done({ completed: false, stepCompleted: reason === 'invalid-boolean' ? 'true' : !['wrong-action','question'].includes(reason), screenId: 'after',
      evidenceControlIds: reason === 'empty-evidence' ? [] : [reason === 'invented-evidence' ? 'fake' : 'status'] });
    assert.deepEqual(f.advances, []); assert.equal(f.completed(), 0);
  });
});

test('completion of the whole objective ends silently instead of requesting another step', t => {
  const f = fixture(t); f.state.step = { turnId: 'final', instruction: 'Confirmá la operación.', expectsReply: false };
  const request = f.start(); f.created(request);
  f.done({ completed: true, stepCompleted: true, screenId: 'after', evidenceControlIds: ['status'] });
  assert.equal(f.completed(), 1); assert.deepEqual(f.advances, []);
});

test('malicious, incomplete, protected or foreign evidence cannot complete a task', async t => {
  const invalid = [
    { completed: true, screenId: 'after', evidenceControlIds: [] },
    { completed: true, screenId: 'before', evidenceControlIds: ['status'] },
    { completed: true, screenId: 'after', evidenceControlIds: ['invented'] },
    { completed: true, screenId: 'after', evidenceControlIds: ['status', 'status'] },
    { completed: true, screenId: 'after', evidenceControlIds: ['status'], execute: 'click' },
    { completed: 'true', screenId: 'after', evidenceControlIds: ['status'] },
    { completed: true, screenId: 'after', evidenceControlIds: Array(9).fill('status') },
  ];
  for (const args of invalid) await t.test(JSON.stringify(args), sub => {
    const f = fixture(sub); const request = f.start(); f.created(request); f.done(args); assert.equal(f.completed(), 0);
  });
  await t.test('redacted node', sub => {
    const f = fixture(sub); f.probe.activity(); f.change({ ...screen('after'), controls: [{ id: 'hidden', label: 'Campo de texto', role: 'input', redacted: true, bounds: [0, 0, 100, 50] }] });
    sub.mock.timers.tick(2000); f.created(f.sent[0]); f.done({ completed: true, screenId: 'after', evidenceControlIds: ['hidden'] }); assert.equal(f.completed(), 0);
  });
  await t.test('unexpected message or invalid JSON', sub => {
    const f = fixture(sub); const request = f.start(); f.created(request);
    f.done(null, 'probe-response', [{ type: 'message', content: [{ text: 'Pretend success' }] }]); assert.equal(f.completed(), 0);
  });
});

test('new action cancels the old probe by ID; stale success cannot end the current task', t => {
  const f = fixture(t); const request = f.start(); f.created(request); f.probe.activity();
  assert.equal(f.sent.find(event => event.type === 'response.cancel').response_id, 'probe-response');
  f.done(); assert.equal(f.completed(), 0);
  f.change(screen('new-action', 'Resultado aún pendiente')); t.mock.timers.tick(2000);
  assert.equal(f.sent.filter(event => event.type === 'response.create').length, 2);
});

test('a user turn cancels before response.created and all late probe metadata stay isolated', t => {
  const f = fixture(t); const request = f.start(); f.probe.cancel(); assert.equal(f.created(request), true);
  assert.equal(f.sent.find(event => event.type === 'response.cancel').response_id, 'probe-response'); f.done(); assert.equal(f.completed(), 0);
  assert.equal(f.probe.handle({ type: 'response.created', response: { id: 'very-late', metadata: { nieto_probe_id: request.event_id } } }), true);
  assert.equal(f.sent.at(-1).response_id, 'very-late');
});

test('task changes, snapshot changes, busy input and provider errors are silent failures', async t => {
  for (const reason of ['task', 'screen', 'busy', 'error']) await t.test(reason, sub => {
    const f = fixture(sub); const request = f.start(); f.created(request);
    if (reason === 'task') f.state.task = { ...f.state.task, goal: 'Otro objetivo' };
    if (reason === 'screen') f.change(screen('stale', 'Otra pantalla'));
    if (reason === 'busy') f.state.busy = true;
    if (reason === 'error') f.probe.handle({ type: 'error', error: { event_id: request.event_id, code: 'rate_limit_exceeded' } });
    f.done(); assert.equal(f.completed(), 0);
  });
});

test('busy turns defer probes; timeout and close bound memory without polling or implicit retries', t => {
  const f = fixture(t); f.state.busy = true; f.probe.activity(); f.change(); t.mock.timers.tick(2000); assert.equal(f.sent.length, 0);
  f.state.busy = false; f.probe.schedule(); t.mock.timers.tick(0); const request = f.sent[0]; f.created(request);
  t.mock.timers.tick(10_000); assert.equal(f.probe.active, null); assert.equal(f.sent.at(-1).type, 'response.cancel');
  t.mock.timers.tick(10_000); assert.equal(f.sent.filter(event => event.type === 'response.create').length, 1);
  for (let index = 0; index < 15; index++) {
    f.probe.activity(); f.change(screen(`many_${index}`, `Estado de prueba ${index}`)); t.mock.timers.tick(2000); f.probe.cancel();
  }
  assert.ok(f.probe.probes.size <= 8); assert.ok(f.probe.responseIds.size <= 8);
  f.probe.close(); assert.equal(f.probe.action, null); assert.equal(f.probe.probes.size, 0); assert.equal(f.probe.responseIds.size, 0);
});

test('default budget policy cannot start autonomous provider checks', t => {
  const f = fixture(t, { enabled: false }); f.start(); assert.equal(f.sent.length, 0);
});

test('a real reduced baseline resumes observation without making the old screen current or requiring a second snapshot', t => {
  const f = fixture(t); const before = f.state.screen;
  f.state.screen = null; f.probe.observe(); f.probe.activity(before);
  assert.equal(f.state.screen, null); t.mock.timers.tick(500); assert.equal(f.sent.length, 0);
  f.change(screen('after_reconnect', 'Operación de prueba completada')); t.mock.timers.tick(2000);
  const input = JSON.parse(f.sent[0].response.input[0].content[0].text);
  assert.equal(input.BEFORE.id, 'before'); assert.equal(input.AFTER.id, 'after_reconnect');
  assert.equal(f.state.screen.id, 'after_reconnect');
});
