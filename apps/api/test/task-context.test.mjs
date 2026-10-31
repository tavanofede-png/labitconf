import test from 'node:test';
import assert from 'node:assert/strict';
import { TASK_CONTEXT_TOOL, validateTaskContext, validateTaskContextUpdate } from '../src/task-context.mjs';

const task = () => ({ goal: 'Crear un correo', targetApp: 'Gmail', progress: 'WhatsApp sigue abierto' });

test('task context normalizes a copied global goal and defaults follow-up to false', () => {
  const source = { goal: '  Crear\n un correo  ', targetApp: ' Gmail ', progress: ' WhatsApp sigue abierto ' };
  const context = validateTaskContext(source);
  assert.deepEqual(context, { ...task(), expectsReply: false });
  source.goal = 'Otra tarea';
  assert.equal(context.goal, 'Crear un correo');
  assert.equal(validateTaskContext({ ...task(), expectsReply: true }).expectsReply, true);
});

test('clearing requires no remaining app, progress or expected response', () => {
  assert.deepEqual(validateTaskContext({ goal: '', targetApp: ' ', progress: '' }), { goal: '', targetApp: '', progress: '', expectsReply: false });
  for (const extra of [{ targetApp: 'Gmail' }, { progress: 'Algo pendiente' }, { expectsReply: true }]) {
    assert.throws(() => validateTaskContext({ goal: '', targetApp: '', progress: '', ...extra }), /contexto/);
  }
});

test('only a completely cleared model task may carry a terminal reason and it never enters resumable memory', () => {
  const cleared = { goal: '', targetApp: '', progress: '', expectsReply: false };
  for (const endReason of ['task_completed', 'user_end']) {
    const update = validateTaskContextUpdate({ ...cleared, endReason });
    assert.deepEqual(update, { context: cleared, endReason });
    assert.equal(Object.hasOwn(update.context, 'endReason'), false);
    assert.throws(() => validateTaskContext({ ...cleared, endReason }), /contexto/);
    assert.throws(() => validateTaskContextUpdate({ ...task(), endReason }), /contexto/);
  }
  assert.equal(validateTaskContextUpdate(cleared).endReason, 'user_end');
  assert.deepEqual(validateTaskContextUpdate(task()), { context: { ...task(), expectsReply: false }, endReason: null });
  for (const endReason of ['finished', '', false, undefined]) assert.throws(() => validateTaskContextUpdate({ ...cleared, endReason }), /contexto/);
  assert.throws(() => validateTaskContextUpdate({ ...cleared, endReason: 'task_completed', action: 'click' }), /contexto/);
  assert.throws(() => validateTaskContextUpdate(Object.defineProperty({ ...cleared }, 'endReason', { get() { throw new Error('PRIVATE'); } })), /contexto/);
});

test('task context rejects extra commands, invalid types, accessors and field limits', () => {
  const invalid = [null, [], 'Crear correo', {}, { ...task(), action: 'click' }, { ...task(), controlId: 'c1' },
    { ...task(), goal: null }, { ...task(), progress: [] }, { ...task(), expectsReply: 1 }, { ...task(), expectsReply: undefined },
    { ...task(), goal: 'x'.repeat(601) }, { ...task(), targetApp: 'x'.repeat(81) }, { ...task(), progress: 'x'.repeat(401) },
    { ...task(), goal: 'Texto\u0000oculto' }, new Date(), Object.defineProperty(task(), 'goal', { get() { throw new Error('PRIVATE_VALUE'); } }),
  ];
  for (const value of invalid) assert.throws(() => validateTaskContext(value), error => error instanceof Error && !error.message.includes('PRIVATE_VALUE'));
});

test('4096-byte wire budget preserves valid full-length goals with accents and JSON escapes', () => {
  for (const source of [
    { goal: 'x'.repeat(600), targetApp: 'x'.repeat(80), progress: 'x'.repeat(400) },
    { goal: 'á'.repeat(600), targetApp: 'ñ'.repeat(80), progress: 'ó'.repeat(400) },
    { goal: '"'.repeat(600), targetApp: '\\'.repeat(80), progress: '"\\'.repeat(200) },
    { goal: '界'.repeat(600), targetApp: '語'.repeat(80), progress: '文'.repeat(400) },
  ]) {
    const context = validateTaskContext(source);
    assert.deepEqual(context, { ...source, expectsReply: false });
    assert.equal(context.goal.length, 600);
    assert.ok(Buffer.byteLength(JSON.stringify(context), 'utf8') <= 4096);
  }
});

test('credentials and financial codes never enter task memory or error messages', () => {
  const unsafe = ['sk-proj-not_a_real_credential', 'OPENAI_API_KEY=fake_value', '"apiKey": "fake_value"', 'Bearer fake.access.token', 'Bearer x',
    'password=secret_value', 'Contraseña: secret_value', 'contrasena=secret_value', 'OTP: secret_value', 'PIN=1234',
    'Código 123456', 'Código １２３４５６', '4111111111111111', '4111 1111 1111 1111', '4111-1111-1111-1111'];
  for (const value of unsafe) {
    for (const field of ['goal', 'targetApp', 'progress']) {
      assert.throws(() => validateTaskContext({ ...task(), [field]: value }), error => error instanceof Error && !error.message.includes(value));
    }
  }
  assert.equal(validateTaskContext({ ...task(), progress: 'Se abrió el campo de contraseña; la persona completa sus datos.' }).progress.includes('contraseña'), true);
});

test('task tool describes memory only and accepts no action parameters', () => {
  assert.equal(TASK_CONTEXT_TOOL.type, 'function');
  assert.equal(TASK_CONTEXT_TOOL.name, 'set_task_context');
  assert.deepEqual(Object.keys(TASK_CONTEXT_TOOL.parameters.properties), ['goal', 'targetApp', 'progress', 'expectsReply', 'endReason']);
  assert.deepEqual(TASK_CONTEXT_TOOL.parameters.required, ['goal', 'targetApp', 'progress']);
  assert.equal(TASK_CONTEXT_TOOL.parameters.additionalProperties, false);
  assert.match(TASK_CONTEXT_TOOL.description, /No pulsa/);
  assert.match(TASK_CONTEXT_TOOL.parameters.properties.progress.description, /sin.*guardar confirmaciones sensibles/);
  assert.match(TASK_CONTEXT_TOOL.description, /deben pedirse nuevamente después de renovar/);
});
