import test from 'node:test';
import assert from 'node:assert/strict';
import { institutionOverview } from '../src/institution.mjs';

const now = Date.parse('2026-10-30T12:00:00Z');
const at = days => new Date(now - days * 86_400_000).toISOString();
function state() {
  return { users: Array.from({ length: 10 }, (_, i) => ({ id: `u${i}`, territoryId: 'AR-C', institutionMetricsConsent: i < 5 })),
    sessions: Array.from({ length: 5 }, (_, i) => ({ id: `s${i}`, userId: `u${i}`, flowId: 'screen-assistance', createdAt: at(1), state: 'active', eventCounts: { flow_started: 1 }, durationSeconds: 60 })), alerts: [] };
}
test('empty installations, nonconsenting activity and session creation never unlock a cohort', () => {
  const data = state(); data.sessions = data.sessions.slice(0, 1);
  data.sessions.push(...data.users.slice(5).map(user => ({ userId: user.id, state: 'active', createdAt: at(1), eventCounts: { flow_started: 1 } })));
  data.sessions.push(...data.users.slice(1, 5).map(user => ({ userId: user.id, state: 'created', createdAt: at(1), eventCounts: {} })));
  const result = institutionOverview(data, 'AR-C', now);
  assert.equal(result.suppressed, true); assert.equal(result.participants, null); assert.equal(result.metrics, null); assert.deepEqual(result.flows, []);
});
test('each result, difficulty step, help and trend subgroup requires its own effective people', () => {
  const data = state(); data.sessions[0].state = 'completed'; data.sessions[0].completedAt = at(1); data.sessions[0].autonomyCompleted = true;
  data.sessions[0].eventCounts.user_lost = 9; data.sessions[0].difficultyByStep = { attach: 9 };
  data.alerts.push({ userId: 'u0', createdAt: at(1), resolvedAt: at(0) });
  let result = institutionOverview(data, 'AR-C', now);
  assert.equal(result.activity.activePeople, 5); assert.equal(result.activity.startedFlows, 5); assert.equal(result.metrics.accompaniedMinutes, 5);
  for (const key of ['reportedFlows', 'practiceReports', 'completedFlows', 'autonomyChecks', 'helpRequests']) assert.equal(result.metrics[key], null);
  assert.equal(result.difficulties.events, null); assert.deepEqual(result.difficulties.steps, []); assert.equal(result.familyResponse.resolvedRequests, null);
  assert.equal(result.trend.filter(bucket => !bucket.suppressed).length, 1);
  data.sessions[1].createdAt = at(8);
  result = institutionOverview(data, 'AR-C', now); assert.equal(result.activity.activePeople, 5); assert.equal(result.trend.every(bucket => bucket.suppressed), true);
});
test('thirty-day period excludes older sessions, revoked consent and other territories', () => {
  const data = state(); data.sessions[4].createdAt = at(31);
  assert.equal(institutionOverview(data, 'AR-C', now).suppressed, true);
  data.sessions[4].createdAt = at(1); data.users[4].territoryId = 'AR-B';
  assert.equal(institutionOverview(data, 'AR-C', now).suppressed, true);
  data.users[4].territoryId = 'AR-C'; data.users[4].institutionMetricsConsent = false;
  assert.equal(institutionOverview(data, 'AR-C', now).suppressed, true);
});

test('reports and practice attempts have their own cohorts and legacy claims never unlock verified outcomes', () => {
  const data = state();
  for (const session of data.sessions) {
    session.state = 'reported'; session.reportedAt = at(1); session.resultSource = 'self_reported';
    session.practiceAttempts = [{ state: 'reported', reportedAt: at(1) }];
  }
  let result = institutionOverview(data, 'AR-C', now);
  assert.equal(result.metrics.reportedFlows, 5); assert.equal(result.metrics.practiceReports, 5);
  assert.equal(result.metrics.completedFlows, null); assert.equal(result.metrics.autonomyChecks, null);
  assert.equal(result.cohorts.autonomyChecks.suppressionReason, 'verification_unavailable');
  data.sessions[0].practiceAttempts = [];
  result = institutionOverview(data, 'AR-C', now); assert.equal(result.metrics.reportedFlows, 5); assert.equal(result.metrics.practiceReports, null);
  for (const session of data.sessions) { session.state = 'completed'; session.completedAt = at(1); session.autonomyCompleted = true; session.practiceAttempts = []; }
  result = institutionOverview(data, 'AR-C', now);
  assert.equal(result.metrics.reportedFlows, 5); assert.equal(result.metrics.practiceReports, 5);
  assert.equal(result.metrics.completedFlows, null); assert.equal(result.metrics.autonomyChecks, null);
});
