import { ageOn } from './dashboard-demo.mjs';
const DAY = 86_400_000;
const round = (n) => Math.round(n * 10) / 10;
const sum = (items, value) => items.reduce((total, item) => total + value(item), 0);
const unique = (items) => new Set(items.map((item) => item.userId)).size;
export const FLOW_NAMES = { 'whatsapp-location': 'Compartir ubicación por WhatsApp', 'whatsapp-group': 'Crear un grupo de WhatsApp', 'email-send': 'Enviar un correo' };

export function periodFor(days, now) {
  const end = now + 1;
  return { from: new Date(now - days * DAY).toISOString(), to: new Date(end).toISOString(), days };
}
const inPeriod = (value, period) => Date.parse(value) >= Date.parse(period.from) && Date.parse(value) < Date.parse(period.to);

/** Only bounded, aggregate records are returned. Identity and free text never enter this response. */
export function summarize(state, territoryId, period, minimumCohort) {
  const users = new Set(state.users.filter((user) => territoryId === 'AR' || user.territoryId === territoryId).map((user) => user.id));
  const sessions = state.sessions.filter((session) => users.has(session.userId) && inPeriod(session.createdAt, period));
  const usage = (state.usage ?? []).filter((entry) => users.has(entry.userId) && inPeriod(entry.startedAt, period));
  const safety = (state.safety ?? []).filter((entry) => users.has(entry.userId) && inPeriod(entry.createdAt, period));
  const help = state.alerts.filter((entry) => users.has(entry.userId) && inPeriod(entry.createdAt, period));
  const cohort = new Set([...sessions, ...usage, ...safety, ...help].map((entry) => entry.userId));
  const base = { territoryId, period, minimumCohort, suppressed: cohort.size < minimumCohort };
  if (base.suppressed) return { ...base, participants: null, metrics: null, flows: [], trend: [], usage: null, safety: null, help: null };

  // Each independent collection and each published breakdown must also reach the cohort threshold.
  const sessionsPublishable = unique(sessions) >= minimumCohort;
  const completed = sessions.filter((session) => session.state === 'completed');
  const autonomy = completed.filter((session) => session.autonomyCompleted);
  const ages = (state.accounts ?? []).filter(a => a.preferences?.aggregates === true && cohort.has(a.deviceId)).map(a => ageOn(a.profile?.birthDate, period.to)).filter(age => age !== null);
  const metrics = sessionsPublishable ? {
    sessions: sessions.length, completedFlows: completed.length, autonomyChecks: autonomy.length,
    accompaniedMinutes: round(sum(sessions, (s) => s.durationSeconds) / 60),
    completionRate: sessions.length ? round(completed.length * 100 / sessions.length) : null,
    autonomyRate: completed.length ? round(autonomy.length * 100 / completed.length) : null,
    averageMinutes: sessions.length ? round(sum(sessions, (s) => s.durationSeconds) / sessions.length / 60) : null,
    averageAge: ages.length >= minimumCohort ? round(ages.reduce((n, age) => n + age, 0) / ages.length) : null,
  } : null;
  const group = (items, key, build) => {
    const groups = [...new Set(items.map(key))].map((id) => [id, items.filter((item) => key(item) === id)]);
    // Suppress an entire distribution when a small bucket could be derived by subtraction.
    return groups.some(([, entries]) => unique(entries) < minimumCohort) ? [] : groups.map(([id, entries]) => build(id, entries));
  };
  const flows = sessionsPublishable ? group(sessions, (s) => s.flowId, (flowId, entries) => ({
    flowId, name: FLOW_NAMES[flowId] ?? 'Guía digital', started: entries.length,
    completed: entries.filter((s) => s.state === 'completed').length,
    cancelled: entries.filter((s) => s.state === 'cancelled').length,
    autonomyChecks: entries.filter((s) => s.state === 'completed' && s.autonomyCompleted).length,
    averageMinutes: round(sum(entries, (s) => s.durationSeconds) / entries.length / 60),
    repeatedSteps: sum(entries, (s) => s.eventCounts?.step_repeated ?? 0),
    lostEvents: sum(entries, (s) => s.eventCounts?.user_lost ?? 0),
    steps: group(entries.flatMap((s) => s.completedSteps.map((stepId) => ({ userId: s.userId, stepId }))), (s) => s.stepId,
      (stepId, rows) => ({ stepId, completed: rows.length })),
  })) : [];
  const trend = sessionsPublishable ? group(sessions, (s) => s.createdAt.slice(0, 10), (date, entries) => ({
    date, sessions: entries.length, completed: entries.filter((s) => s.state === 'completed').length,
    autonomyChecks: entries.filter((s) => s.state === 'completed' && s.autonomyCompleted).length,
  })).sort((a, b) => a.date.localeCompare(b.date)) : [];
  const publishedUsage = unique(usage) >= minimumCohort ? {
    screenMinutes: round(sum(usage, (s) => s.durationSeconds) / 60),
    accompaniedMinutes: round(sum(usage, (s) => s.accompaniedSeconds) / 60),
    unaccompaniedMinutes: round(sum(usage, (s) => s.durationSeconds - s.accompaniedSeconds) / 60),
    screenMinutesPerDay: round(sum(usage, s => s.durationSeconds) / 60 / new Set(usage.map(s => `${s.userId}:${s.startedAt.slice(0,10)}`)).size),
    tinoMinutesPerDay: round(sum(usage, s => s.accompaniedSeconds) / 60 / new Set(usage.map(s => `${s.userId}:${s.startedAt.slice(0,10)}`)).size),
    categories: group(usage, (s) => s.category, (category, rows) => ({ category, minutes: round(sum(rows, (s) => s.durationSeconds) / 60) })),
  } : null;
  const publishedSafety = unique(safety) >= minimumCohort ? {
    total: safety.length, resolved: safety.filter((s) => s.resolvedAt).length,
    pending: safety.filter((s) => !s.resolvedAt).length,
    categories: group(safety, (s) => s.kind, (kind, rows) => ({ kind, count: rows.length })),
  } : null;
  const publishedHelp = unique(help) >= minimumCohort ? { total: help.length, resolved: help.filter((s) => s.resolvedAt).length } : null;
  return { ...base, participants: cohort.size, metrics, flows, trend, usage: publishedUsage, safety: publishedSafety, help: publishedHelp };
}

export function institutionDashboard(state, territoryId, days, minimumCohort, now) {
  const period = periodFor(days, now);
  return { ...summarize(state, territoryId, period, minimumCohort), updatedAt: new Date(now).toISOString(),
    definitions: {
      autonomyRate: 'Prácticas autónomas registradas / recorridos completados. No se infiere autonomía de tiempo sin asistencia.',
      sessions: 'Sesiones iniciadas dentro del período; estado y duración acumulados al momento de consulta.',
      screenTime: 'Solo intervalos de uso enviados con permiso específico. No equivale a minutos de sesiones guiadas.',
      breakdowns: 'Una distribución se omite si alguna categoría no alcanza la cohorte mínima. Vacío no significa cero.',
    } };
}

export function compareProgram(state, program, minimumCohort, now) {
  const start = Date.parse(program.startDate + 'T00:00:00Z');
  const plannedEnd = Date.parse(program.endDate + 'T00:00:00Z') + DAY;
  const end = Math.min(plannedEnd, now + 1);
  if (end <= start) return { status: 'not_started', before: null, after: null, changePoints: null };
  const duration = end - start;
  const before = summarize(state, program.territoryId, { from: new Date(start - duration).toISOString(), to: new Date(start).toISOString() }, minimumCohort);
  const after = summarize(state, program.territoryId, { from: new Date(start).toISOString(), to: new Date(end).toISOString() }, minimumCohort);
  const available = before.metrics?.autonomyRate != null && after.metrics?.autonomyRate != null;
  return { status: available ? 'available' : 'insufficient_cohort', before, after,
    changePoints: available ? round(after.metrics.autonomyRate - before.metrics.autonomyRate) : null,
    note: 'Comparación descriptiva de ventanas de igual duración. No demuestra causalidad ni controla la composición de participantes.' };
}
