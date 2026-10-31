import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createSession, applySessionEvent, publicSession, operationalMetrics, GUIDED_STEPS } from '../src/sessions.mjs';
import { JsonStore } from '../src/store.mjs';

const now = Date.parse('2026-10-31T12:00:00Z');
function fixture(flowId = 'whatsapp-location') {
  const state = { sessions: [] }; const clientSessionId = randomUUID();
  const session = createSession(state, 'user-one', { flowId, clientSessionId }, now);
  const event = body => applySessionEvent(state, 'user-one', session.id, { eventId: randomUUID(), ...body }, now + 1000);
  const walk = (attemptId, start = 0) => { for (const stepId of GUIDED_STEPS.slice(start)) { event({ type: 'step_shown', stepId, ...(attemptId ? { attemptId } : {}) }); event({ type: 'step_completed', stepId, ...(attemptId ? { attemptId } : {}) }); } };
  const report = () => { event({ type: 'flow_started' }); walk(); event({ type: 'flow_reported', durationSeconds: 30 }); };
  return { state, clientSessionId, session, event, walk, report, record: state.sessions[0] };
}
const code = expected => error => error.code === expected;

test('creation and event delivery require bounded IDs and reject arbitrary telemetry', () => {
  const f = fixture();
  assert.throws(() => createSession(f.state, 'user-one', { flowId: 'whatsapp-location' }, now), code('invalid_idempotency_id'));
  assert.throws(() => applySessionEvent(f.state, 'user-one', f.session.id, { type: 'flow_started' }, now), code('invalid_idempotency_id'));
  assert.throws(() => f.event({ type: 'flow_started', screenshot: 'private' }), code('unexpected_field'));
  assert.throws(() => f.event({ type: 'flow_started', stepId: 'arbitrary-private-text' }), code('unexpected_field'));
  assert.throws(() => f.event({ type: 'cancelled', durationSeconds: 1.5 }), code('invalid_duration'));
  assert.deepEqual(f.record.eventCounts, {});
});

test('replaying creation is stable, conflicts are rejected, and identities remain isolated', () => {
  const f = fixture();
  assert.equal(createSession(f.state, 'user-one', { flowId: 'whatsapp-location', clientSessionId: f.clientSessionId }, now).id, f.session.id);
  assert.throws(() => createSession(f.state, 'user-one', { flowId: 'screen-assistance', clientSessionId: f.clientSessionId }, now), code('idempotency_conflict'));
  assert.notEqual(createSession(f.state, 'another-user', { flowId: 'whatsapp-location', clientSessionId: f.clientSessionId }, now).id, f.session.id);
  assert.equal(JSON.stringify(f.state).includes(f.clientSessionId), false);
});

test('the audit exploit cannot report without start or skip steps, and verification claims are disabled', () => {
  const f = fixture();
  for (const type of ['flow_completed', 'autonomy_completed', 'practice_completed']) assert.throws(() => f.event({ type, durationSeconds: 30 }), code('verification_unavailable'));
  assert.throws(() => f.event({ type: 'flow_reported' }), code('flow_not_started'));
  f.event({ type: 'flow_started' });
  assert.throws(() => f.event({ type: 'step_shown', stepId: 'verify' }), code('invalid_transition'));
  assert.throws(() => f.event({ type: 'step_completed', stepId: 'open_chat' }), code('invalid_transition'));
  assert.throws(() => f.event({ type: 'flow_reported' }), code('flow_incomplete'));
  assert.equal(f.record.state, 'active'); assert.equal(operationalMetrics(f.state.sessions).reportedFlows, 0);
});

test('a report requires the whole ordered graph and freezes duration even on later attempts', () => {
  const f = fixture(); f.report();
  assert.equal(f.record.state, 'reported'); assert.equal(f.record.resultSource, 'self_reported');
  assert.throws(() => f.event({ type: 'cancelled', durationSeconds: 14_400 }), code('session_closed'));
  const attemptId = randomUUID(); f.event({ type: 'practice_started', attemptId }); f.walk(attemptId);
  f.event({ type: 'practice_reported', attemptId, durationSeconds: 120 });
  assert.equal(f.record.durationSeconds, 30); assert.equal(f.record.practiceAttempts[0].durationSeconds, 120);
  assert.deepEqual(operationalMetrics(f.state.sessions), { reportedFlows: 1, practiceReports: 1, completedFlows: 0, autonomyChecks: 0, accompaniedSeconds: 30 });
});

test('backtracking invalidates every later completion and privacy confirmation', () => {
  const f = fixture(); f.event({ type: 'flow_started' }); f.walk();
  f.event({ type: 'step_shown', stepId: 'attach' }); assert.deepEqual(f.record.completedSteps, ['open_chat']);
  assert.throws(() => f.event({ type: 'flow_reported' }), code('flow_incomplete'));
  assert.throws(() => f.event({ type: 'step_shown', stepId: 'share' }), code('invalid_transition'));
  // Complete the returned step, then repeat the remaining path and confirmation.
  f.event({ type: 'step_completed', stepId: 'attach' }); f.walk(undefined, 2);
  f.event({ type: 'flow_reported', durationSeconds: 40 }); assert.equal(f.record.state, 'reported');
});

test('practice is an explicit independent attempt and help prevents claims of autonomy', () => {
  const f = fixture(); const first = randomUUID();
  assert.throws(() => f.event({ type: 'practice_started', attemptId: first }), code('flow_incomplete'));
  f.report(); assert.throws(() => f.event({ type: 'practice_started' }), code('missing_attempt'));
  f.event({ type: 'practice_started', attemptId: first });
  assert.throws(() => f.event({ type: 'practice_started', attemptId: randomUUID() }), code('practice_active'));
  f.event({ type: 'step_shown', stepId: 'open_chat', attemptId: first });
  f.event({ type: 'user_lost', stepId: 'open_chat', attemptId: first });
  f.event({ type: 'step_completed', stepId: 'open_chat', attemptId: first }); f.walk(first, 1);
  assert.throws(() => f.event({ type: 'autonomy_completed', attemptId: first }), code('verification_unavailable'));
  f.event({ type: 'practice_reported', attemptId: first, durationSeconds: 60 });
  assert.equal(f.record.practiceAttempts[0].assisted, true);
  const second = randomUUID(); f.event({ type: 'practice_started', attemptId: second }); f.event({ type: 'cancelled', attemptId: second, durationSeconds: 4 });
  assert.equal(f.record.state, 'reported'); assert.equal(f.record.practiceAttempts[1].state, 'cancelled');
  assert.throws(() => f.event({ type: 'practice_reported', attemptId: second }), code('attempt_closed'));
  assert.equal(publicSession(f.record).autonomyCompleted, false); assert.equal(operationalMetrics(f.state.sessions).practiceReports, 1);
});

test('event IDs deduplicate after terminal state and cannot be reused with a changed payload or session', () => {
  const f = fixture(); const eventId = randomUUID(); const body = { eventId, type: 'flow_started' };
  assert.equal(f.event(body).duplicate, false); assert.equal(f.event(body).duplicate, true);
  assert.equal(f.record.eventCounts.flow_started, 1);
  const other = createSession(f.state, 'user-one', { flowId: 'whatsapp-location', clientSessionId: randomUUID() }, now);
  assert.throws(() => applySessionEvent(f.state, 'user-one', other.id, body, now), code('idempotency_conflict'));
  f.walk(); const report = { eventId: randomUUID(), type: 'flow_reported', durationSeconds: 30 };
  f.event(report); assert.equal(f.event(report).duplicate, true);
  assert.throws(() => f.event({ ...report, durationSeconds: 14_400 }), code('idempotency_conflict'));
  assert.equal(f.record.eventCounts.flow_reported, 1); assert.equal(f.record.durationSeconds, 30);
});

test('screen assistance records activity only and legacy results never become verified metrics', () => {
  const f = fixture('screen-assistance'); f.event({ type: 'flow_started' }); f.event({ type: 'step_shown', stepId: 'screen-step' });
  for (const type of ['flow_completed', 'autonomy_completed', 'flow_reported', 'step_completed']) assert.throws(() => f.event({ type, ...(type === 'step_completed' ? { stepId: 'screen-step' } : {}) }), code('verification_unavailable'));
  const legacy = { id: 'old', state: 'completed', autonomyCompleted: true, durationSeconds: 30, completedAt: new Date(now).toISOString() };
  const view = publicSession(legacy);
  assert.equal(view.state, 'reported'); assert.equal(view.resultSource, 'legacy_unverified'); assert.equal(view.autonomyCompleted, false);
  assert.equal(operationalMetrics([legacy]).reportedFlows, 1); assert.equal(operationalMetrics([legacy]).practiceReports, 1);
  assert.equal(operationalMetrics([legacy]).autonomyChecks, 0); assert.equal(legacy.state, 'completed');
});

test('a lost acknowledgement can be replayed after process restart without duplicate counters', async t => {
  const folder = await mkdtemp(join(tmpdir(), 'nieto-delivery-')); t.after(() => rm(folder, { recursive: true, force: true }));
  const file = join(folder, 'state.json'); const original = new JsonStore(file); const clientSessionId = randomUUID();
  const create = { flowId: 'screen-assistance', clientSessionId };
  const first = await original.update(state => createSession(state, 'u', create, now));
  const event = { eventId: randomUUID(), type: 'flow_started' };
  await original.update(state => applySessionEvent(state, 'u', first.id, event, now));
  // A fresh store represents a restarted server after the committed response was lost.
  const restarted = new JsonStore(file);
  assert.equal((await restarted.update(state => createSession(state, 'u', create, now))).id, first.id);
  const responses = await Promise.all(Array.from({ length: 3 }, () => restarted.update(state => applySessionEvent(state, 'u', first.id, event, now + 1000))));
  assert.equal(responses.every(response => response.duplicate), true);
  const saved = await restarted.read(); assert.equal(saved.sessions.length, 1); assert.equal(saved.sessions[0].eventCounts.flow_started, 1);
});
