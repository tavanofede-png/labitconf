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
  const users = new Set(state.users.filter(user => !user.demo && (territoryId === 'AR' || user.territoryId === territoryId) && (user.institutionMetricsConsent === true || (state.accounts ?? []).some(a => !a.demo && a.deviceId === user.id && a.preferences?.aggregates === true))).map(user => user.id));
  const sessions = state.sessions.filter((session) => users.has(session.userId) && inPeriod(session.createdAt, period));
  const usage = (state.usage ?? []).filter((entry) => users.has(entry.userId) && inPeriod(entry.startedAt, period));
  const safety = (state.safety ?? []).filter((entry) => users.has(entry.userId) && inPeriod(entry.createdAt, period));
  const help = state.alerts.filter((entry) => users.has(entry.userId) && inPeriod(entry.createdAt, period));
  const cohort = new Set([...sessions, ...usage, ...safety, ...help].map((entry) => entry.userId));
  const base = { territoryId, period, minimumCohort, suppressed: cohort.size < minimumCohort };
  if (base.suppressed) return { ...base, participants: null, metrics: null, flows: [], trend: [], usage: null, safety: null, help: null };

  // Each independent collection and each published breakdown must also reach the cohort threshold.
  const sessionsPublishable = unique(sessions) >= minimumCohort;
  const completed = sessions.filter(session => session.state === 'completed' && session.resultSource === 'verified');
  const autonomy = completed.filter((session) => session.autonomyCompleted);
  const ages = (state.accounts ?? []).filter(a => a.preferences?.aggregates === true && cohort.has(a.deviceId)).map(a => ageOn(a.profile?.birthDate, period.to)).filter(age => age !== null);
  const metrics = sessionsPublishable ? {
    sessions: sessions.length, completedFlows: unique(completed)>=minimumCohort ? completed.length : null, autonomyChecks: unique(autonomy)>=minimumCohort ? autonomy.length : null,
    accompaniedMinutes: round(sum(sessions, (s) => s.durationSeconds) / 60),
    completionRate: unique(completed)>=minimumCohort && sessions.length ? round(completed.length * 100 / sessions.length) : null,
    autonomyRate: unique(autonomy)>=minimumCohort && completed.length ? round(autonomy.length * 100 / completed.length) : null,
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
    completed: unique(entries.filter(s=>s.state==='completed'&&s.resultSource==='verified'))>=minimumCohort ? entries.filter(s=>s.state==='completed'&&s.resultSource==='verified').length : null,
    cancelled: entries.filter((s) => s.state === 'cancelled').length,
    autonomyChecks: unique(entries.filter(s=>s.state==='completed'&&s.resultSource==='verified'&&s.autonomyCompleted))>=minimumCohort ? entries.filter(s=>s.state==='completed'&&s.resultSource==='verified'&&s.autonomyCompleted).length : null,
    averageMinutes: round(sum(entries, (s) => s.durationSeconds) / entries.length / 60),
    repeatedSteps: sum(entries, (s) => s.eventCounts?.step_repeated ?? 0),
    lostEvents: sum(entries, (s) => s.eventCounts?.user_lost ?? 0),
    steps: group(entries.flatMap((s) => s.completedSteps.map((stepId) => ({ userId: s.userId, stepId }))), (s) => s.stepId,
      (stepId, rows) => ({ stepId, completed: rows.length })),
  })) : [];
  const trend = sessionsPublishable ? group(sessions, (s) => s.createdAt.slice(0, 10), (date, entries) => ({
    date, sessions: entries.length, completed: unique(entries.filter(s=>s.state==='completed'&&s.resultSource==='verified'))>=minimumCohort ? entries.filter(s=>s.state==='completed'&&s.resultSource==='verified').length : null,
    autonomyChecks: unique(entries.filter(s=>s.state==='completed'&&s.resultSource==='verified'&&s.autonomyCompleted))>=minimumCohort ? entries.filter(s=>s.state==='completed'&&s.resultSource==='verified'&&s.autonomyCompleted).length : null,
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

/** Every scalar and breakdown has its own contributing-person cohort. */
export function institutionOverview(state, territoryId, now, minimumCohort = 5) {
  const end = now + 1; const start = end - 30 * DAY;
  const eligible = new Set(state.users.filter(user => !user.demo && user.territoryId === territoryId && (user.institutionMetricsConsent === true || (state.accounts ?? []).some(a => !a.demo && a.deviceId === user.id && a.preferences?.aggregates === true))).map(user => user.id));
  // Session-based period is explicit: existing sessions do not contain a dated event log.
  const sessions = state.sessions.filter(session => eligible.has(session.userId) && windowContains(session.createdAt, start, end) && hasActivity(session));
  const alerts = state.alerts.filter(alert => eligible.has(alert.userId) && !alert.revokedAt && windowContains(alert.createdAt, start, end));
  const all = [...sessions, ...alerts];
  const contributors = distinct(all); const suppressed = contributors < minimumCohort;
  const groups = {};
  const scalar = (id, entries, value) => {
    const people = distinct(entries); const hidden = people < minimumCohort;
    groups[id] = { suppressed: hidden, suppressionReason: hidden ? 'insufficient_contributors' : null, contributors: hidden ? null : people, minimumCohort };
    return hidden ? null : value;
  };
  const started = sessions.filter(session => count(session, 'flow_started') > 0);
  const reports = sessions.filter(session => ['reported', 'completed'].includes(session.state) && windowContains(session.reportedAt ?? session.completedAt, start, end));
  const practices = sessions.flatMap(session => [
    ...(session.practiceAttempts ?? []).filter(attempt => attempt.state === 'reported' && windowContains(attempt.reportedAt, start, end)).map(attempt => ({ userId: session.userId, attempt })),
    ...(session.state === 'completed' && session.autonomyCompleted ? [{ userId: session.userId, legacy: true }] : []),
  ]);
  const timed = sessions.filter(session => Number.isFinite(session.durationSeconds) && session.durationSeconds > 0);
  const difficult = sessions.filter(session => count(session, 'user_lost') + count(session, 'step_repeated') > 0);
  const resolved = alerts.filter(alert => alert.resolvedAt && Date.parse(alert.resolvedAt) >= Date.parse(alert.createdAt) && Date.parse(alert.resolvedAt) <= end);
  const activePeople = scalar('activePeople', all, contributors);
  const startedFlows = scalar('startedFlows', started, started.length);
  const metrics = {
    completedFlows: null, autonomyChecks: null,
    reportedFlows: scalar('reportedFlows', reports, reports.length),
    practiceReports: scalar('practiceReports', practices, practices.length),
    accompaniedMinutes: scalar('accompaniedMinutes', timed, Math.round(timed.reduce((sum, session) => sum + session.durationSeconds, 0) / 60)),
    helpRequests: scalar('helpRequests', alerts, alerts.length),
  };
  groups.completedFlows = groups.autonomyChecks = { suppressed: true, suppressionReason: 'verification_unavailable', contributors: null, minimumCohort };
  const difficultyEvents = scalar('difficultyEvents', difficult, difficult.reduce((sum, session) => sum + count(session, 'user_lost') + count(session, 'step_repeated'), 0));
  const resolvedRequests = scalar('resolvedRequests', resolved, resolved.length);
  const responseMinutes = scalar('familyResponseMinutes', resolved, resolved.length ? Math.round(resolved.reduce((sum, alert) => sum + Date.parse(alert.resolvedAt) - Date.parse(alert.createdAt), 0) / resolved.length / 60_000) : null);
  const stepIds = [...new Set(difficult.flatMap(session => Object.keys(session.difficultyByStep ?? {})))].sort();
  const steps = stepIds.map(stepId => {
    const entries = difficult.filter(session => (session.difficultyByStep?.[stepId] ?? 0) > 0);
    const events = scalar(`step:${stepId}`, entries, entries.reduce((sum, session) => sum + session.difficultyByStep[stepId], 0));
    const cohort = groups[`step:${stepId}`]; if (cohort.suppressed) delete groups[`step:${stepId}`];
    return { stepId, events, ...cohort };
  }).filter(step => !step.suppressed);
  const flows = [...new Set(sessions.map(session => session.flowId))].sort().map(flowId => {
    const flowStarted = started.filter(session => session.flowId === flowId);
    const flowReports = reports.filter(session => session.flowId === flowId);
    const startedValue = scalar(`flow:${flowId}:started`, flowStarted, flowStarted.length);
    const reportedValue = scalar(`flow:${flowId}:reported`, flowReports, flowReports.length);
    return { flowId, started: startedValue, completed: null, reported: reportedValue, startedCohort: groups[`flow:${flowId}:started`], completedCohort: groups.completedFlows, reportedCohort: groups[`flow:${flowId}:reported`] };
  }).filter(flow => flow.started !== null || flow.reported !== null);
  const trend = Array.from({ length: 6 }, (_, index) => {
    const from = start + index * 5 * DAY; const to = from + 5 * DAY;
    const activity = all.filter(entry => windowContains(entry.createdAt, from, to));
    const cohort = distinct(activity); const hidden = cohort < minimumCohort;
    return { from: new Date(from).toISOString(), to: new Date(to).toISOString(), suppressed: hidden, suppressionReason: hidden ? 'insufficient_contributors' : null,
      activePeople: hidden ? null : cohort };
  });
  return { territoryId, minimumCohort, suppressed, suppressionReason: suppressed ? 'insufficient_consented_contributors' : null,
    participants: activePeople, metrics: suppressed ? null : metrics, flows: suppressed ? [] : flows,
    period: { from: new Date(start).toISOString(), to: new Date(end).toISOString(), days: 30, basis: 'session_created_and_help_requested', timezone: 'UTC' },
    activity: { ...groups.activePeople, activePeople, startedFlows, startedCohort: groups.startedFlows },
    difficulties: { ...groups.difficultyEvents, events: difficultyEvents, steps },
    familyResponse: { ...groups.resolvedRequests, resolvedRequests, meanResponseMinutes: responseMinutes },
    trend, cohorts: groups,
    evidence: { source: 'NIETO first-party operational records', consent: 'explicit_current_institution_metrics', methodVersion: '2026-10-31.2',
      interpretation: 'Actividad y resultados reportados por el cliente; no acreditan autonomía ni impacto causal.', comparison: 'Solo comparar igual territorio, ventana de 30 días y método; la composición de personas puede cambiar.' },
    gaps: [
      'La ventana selecciona sesiones creadas y ayudas solicitadas en los últimos 30 días; no existe un historial fechado completo de cada paso previo.',
      'Las fechas son de recepción en la API: una entrega diferida puede desplazar actividad a otra ventana; no se reconstruye la hora exacta del teléfono.',
      'Los resultados y prácticas son reportes del cliente, no una validación independiente de envío, aprendizaje o autonomía.',
      'No hay verificador semántico habilitado: tareas completadas verificadas y autonomía no se publican. Los registros heredados solo cuentan como reportes no verificados.',
      'Se suprime cada métrica y subgrupo con menos de cinco personas consentidas que aporten a esa métrica; un valor nulo no significa cero.',
      'La cantidad de dispositivos no verifica personas únicas; el alta y la identidad siguen siendo de prototipo.',
      'No se mide uso total del teléfono, contenido de pantalla, bienestar, emergencias ni impacto antes/después.',
    ], updatedAt: new Date(now).toISOString() };
}

const distinct = entries => new Set(entries.map(entry => entry.userId)).size;
const hasActivity = session => Object.values(session.eventCounts ?? {}).some(count => count > 0) || ["active","reported","completed","cancelled"].includes(session.state);
const count = (session,type) => Number.isSafeInteger(session.eventCounts?.[type]) ? session.eventCounts[type] : 0;

const windowContains = (date,start,end) => Date.parse(date)>=start && Date.parse(date)<end;
