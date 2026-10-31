const DAY = 86_400_000;
const inPeriod = (value, start, end) => Number.isFinite(Date.parse(value)) && Date.parse(value) >= start && Date.parse(value) < end;
const distinct = entries => new Set(entries.map(entry => entry.userId)).size;
const hasActivity = session => Object.values(session.eventCounts ?? {}).some(count => count > 0) || ['active', 'completed', 'cancelled'].includes(session.state);
const count = (session, type) => Number.isSafeInteger(session.eventCounts?.[type]) ? session.eventCounts[type] : 0;

/** Every scalar and breakdown has its own contributing-person cohort. */
export function institutionOverview(state, territoryId, now, minimumCohort = 5) {
  const end = now + 1; const start = end - 30 * DAY;
  const eligible = new Set(state.users.filter(user => user.territoryId === territoryId && user.institutionMetricsConsent === true).map(user => user.id));
  // Session-based period is explicit: existing sessions do not contain a dated event log.
  const sessions = state.sessions.filter(session => eligible.has(session.userId) && inPeriod(session.createdAt, start, end) && hasActivity(session));
  const alerts = state.alerts.filter(alert => eligible.has(alert.userId) && !alert.revokedAt && inPeriod(alert.createdAt, start, end));
  const all = [...sessions, ...alerts];
  const contributors = distinct(all); const suppressed = contributors < minimumCohort;
  const groups = {};
  const scalar = (id, entries, value) => {
    const people = distinct(entries); const hidden = people < minimumCohort;
    groups[id] = { suppressed: hidden, suppressionReason: hidden ? 'insufficient_contributors' : null, contributors: hidden ? null : people, minimumCohort };
    return hidden ? null : value;
  };
  const started = sessions.filter(session => count(session, 'flow_started') > 0);
  const reports = sessions.filter(session => ['reported', 'completed'].includes(session.state) && inPeriod(session.reportedAt ?? session.completedAt, start, end));
  const practices = sessions.flatMap(session => [
    ...(session.practiceAttempts ?? []).filter(attempt => attempt.state === 'reported' && inPeriod(attempt.reportedAt, start, end)).map(attempt => ({ userId: session.userId, attempt })),
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
    const activity = all.filter(entry => inPeriod(entry.createdAt, from, to));
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
