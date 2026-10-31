import { TASK_FLOWS } from './assistant.mjs';
import { createHash, randomUUID } from 'node:crypto';

const hash = value => createHash('sha256').update(value).digest('hex');
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const GUIDED_STEPS = ['open_chat', 'attach', 'location', 'confirm', 'share', 'verify'];
const EVENTS = new Set(['flow_started', 'assistance_ended', 'step_shown', 'step_completed', 'step_repeated', 'user_lost', 'flow_reported', 'practice_started', 'practice_reported', 'cancelled', 'flow_completed', 'practice_completed', 'autonomy_completed']);
const VERIFIED_RESULTS = new Set(['flow_completed', 'practice_completed', 'autonomy_completed']);
export class SessionError extends Error {
  constructor(status, code, message) { super(message); this.name = 'SessionError'; this.status = status; this.code = code; }
}
const requireThat = (condition, status, code, message) => { if (!condition) throw new SessionError(status, code, message); };
const keys = (value, allowed) => requireThat(value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every(key => allowed.includes(key)), 400, 'unexpected_field', 'El pedido contiene campos no permitidos.');
const uuid = (value, field) => { requireThat(typeof value === 'string' && UUID.test(value), 400, 'invalid_idempotency_id', `${field} debe ser un UUID estable del dispositivo.`); return value.toLowerCase(); };
const reported = session => session.state === 'reported' || session.state === 'completed';

export function createSession(state, userId, body, now) {
  keys(body, ['flowId', 'clientSessionId']);
  requireThat((['whatsapp-location', 'screen-assistance'].includes(body.flowId) || Object.hasOwn(TASK_FLOWS,body.flowId)), 400, 'unsupported_flow', 'Flujo no disponible.');
  const clientSessionKey = hash(uuid(body.clientSessionId, 'clientSessionId'));
  const existing = state.sessions.find(session => session.userId === userId && session.clientSessionKey === clientSessionKey);
  if (existing) {
    requireThat(existing.flowId === body.flowId, 409, 'idempotency_conflict', 'Ese identificador de sesión ya corresponde a otro flujo.');
    return { id: existing.id, flowId: existing.flowId, createdAt: existing.createdAt };
  }
  const session = { id: randomUUID(), userId, flowId: body.flowId, clientSessionKey, protocolVersion: 2,
    state: 'created', createdAt: new Date(now).toISOString(), durationSeconds: 0, completedSteps: [], currentStep: null,
    autonomyCompleted: false, eventCounts: {}, eventReceipts: {}, practiceAttempts: [], resultSource: null };
  state.sessions.push(session);
  return { id: session.id, flowId: session.flowId, createdAt: session.createdAt };
}

function stepEvent(record, body, screenAssistance, steps = GUIDED_STEPS) {
  const allowed = screenAssistance ? ['screen-step'] : steps;
  if (body.stepId !== undefined) requireThat(allowed.includes(body.stepId), 400, 'invalid_step', 'El paso no pertenece al recorrido.');
  if (['step_shown', 'step_completed', 'step_repeated'].includes(body.type)) requireThat(body.stepId, 400, 'missing_step', 'Este evento requiere stepId.');
  if (screenAssistance) {
    if (body.type === 'step_shown') record.currentStep = 'screen-step';
    if (body.type === 'step_completed') throw new SessionError(409, 'verification_unavailable', 'El asistente de pantalla no verifica pasos ejecutados en otra aplicación.');
    return;
  }
  const index = steps.indexOf(body.stepId);
  const current = steps.indexOf(record.currentStep);
  if (body.type === 'step_shown') {
    requireThat(current === -1 ? index === 0 : index <= current || (index === current + 1 && record.completedSteps.includes(steps[current])), 409, 'invalid_transition', 'Mostrá los pasos del recorrido en orden antes de avanzar.');
    // Backtracking invalidates this step and every later confirmation.
    if (index <= current) record.completedSteps = record.completedSteps.filter(step => steps.indexOf(step) < index);
    record.currentStep = body.stepId;
  } else if (body.type === 'step_completed') {
    requireThat(record.currentStep === body.stepId && record.completedSteps.length === index, 409, 'invalid_transition', 'Completá únicamente el paso vigente después de los anteriores.');
    record.completedSteps.push(body.stepId);
  } else if (['step_repeated', 'user_lost'].includes(body.type)) {
    requireThat(record.currentStep && (body.stepId === undefined || record.currentStep === body.stepId), 409, 'invalid_transition', 'La dificultad debe corresponder al paso vigente.');
  }
}

export function applySessionEvent(state, userId, sessionId, body, now) {
  keys(body, ['eventId', 'type', 'stepId', 'durationSeconds', 'attemptId']);
  const eventId = uuid(body.eventId, 'eventId');
  const attemptId = body.attemptId === undefined ? undefined : uuid(body.attemptId, 'attemptId');
  requireThat(EVENTS.has(body.type), 400, 'invalid_event', 'Tipo de evento no permitido.');
  if (body.stepId !== undefined) requireThat(['step_shown', 'step_completed', 'step_repeated', 'user_lost'].includes(body.type), 400, 'unexpected_field', 'Este evento no admite stepId.');
  if (body.durationSeconds !== undefined) requireThat(['flow_reported', 'practice_reported', 'cancelled'].includes(body.type) || VERIFIED_RESULTS.has(body.type), 400, 'unexpected_field', 'La duración se registra al terminar el recorrido o intento.');
  if (body.durationSeconds !== undefined) requireThat(Number.isInteger(body.durationSeconds) && body.durationSeconds >= 0 && body.durationSeconds <= 14_400, 400, 'invalid_duration', 'Duración fuera del intervalo permitido (0–14400 segundos enteros).');
  const session = state.sessions.find(entry => entry.id === sessionId && entry.userId === userId);
  requireThat(session, 404, 'session_not_found', 'Sesión inexistente.');
  const receiptKey = hash(`${userId}:${eventId}`);
  const payloadHash = hash(JSON.stringify({ sessionId, type: body.type, stepId: body.stepId ?? null, durationSeconds: body.durationSeconds ?? null, attemptId: attemptId ?? null }));
  const previous = state.sessions.filter(entry => entry.userId === userId).map(entry => entry.eventReceipts?.[receiptKey]).find(Boolean);
  if (previous) {
    requireThat(previous.payloadHash === payloadHash, 409, 'idempotency_conflict', 'Ese eventId ya fue usado para otro evento.');
    return { ok: true, duplicate: true };
  }
  requireThat(!VERIFIED_RESULTS.has(body.type), 409, 'verification_unavailable', 'No hay un verificador semántico habilitado para acreditar envío o autonomía. Solo se admite un reporte explícito de la persona.');
  requireThat(Object.keys(session.eventReceipts ?? {}).length < 5000, 429, 'session_event_limit', 'La sesión alcanzó su límite de eventos.');
  const timestamp = new Date(now).toISOString(); const screenAssistance = session.flowId === 'screen-assistance';
  let record = session;
  if (body.type === 'practice_started') {
    requireThat(!screenAssistance && reported(session), 409, 'flow_incomplete', 'Terminá y reportá el recorrido guiado antes de practicar.');
    requireThat(attemptId, 400, 'missing_attempt', 'La práctica requiere un attemptId nuevo.');
    const attempts = session.practiceAttempts ??= [];
    requireThat(!attempts.some(attempt => attempt.id === attemptId), 409, 'attempt_exists', 'El intento de práctica ya existe.');
    requireThat(!attempts.some(attempt => attempt.state === 'active'), 409, 'practice_active', 'Terminá o cancelá la práctica anterior antes de iniciar otra.');
    requireThat(attempts.length < 100, 429, 'practice_limit', 'Esta sesión alcanzó su límite de prácticas.');
    record = { id: attemptId, state: 'active', startedAt: timestamp, completedSteps: [], currentStep: null, durationSeconds: 0, assisted: false, resultSource: null };
    attempts.push(record);
  } else if (attemptId) {
    record = session.practiceAttempts?.find(attempt => attempt.id === attemptId);
    requireThat(record, 404, 'attempt_not_found', 'Intento de práctica inexistente.');
    requireThat(record.state === 'active', 409, 'attempt_closed', 'El intento de práctica ya terminó.');
    requireThat(!['flow_started', 'flow_reported'].includes(body.type), 409, 'invalid_transition', 'Usá los eventos de práctica para este intento.');
  } else {
    requireThat(!['practice_reported'].includes(body.type), 400, 'missing_attempt', 'La práctica requiere attemptId.');
    requireThat(!reported(session) && session.state !== 'cancelled', 409, 'session_closed', 'La sesión guiada ya terminó.');
  }
  if (body.type === 'flow_started') {
    requireThat(!attemptId && session.state === 'created', 409, 'invalid_transition', 'El recorrido ya fue iniciado.');
    session.state = 'active'; session.startedAt = timestamp;
  } else if (body.type !== 'practice_started') {
    requireThat(record.state === 'active', 409, 'flow_not_started', 'Iniciá el recorrido antes de registrar pasos o resultados.');
    if (body.type === 'assistance_ended') {
      requireThat(screenAssistance && !attemptId, 409, 'invalid_transition', 'Esta decisión corresponde a la asistencia visual activa.');
      requireThat(!session.assistanceEndedAt, 409, 'assistance_already_ended', 'Ya registraste que vas a seguir por tu cuenta.');
      session.assistanceEndedAt = timestamp; session.assistanceEndReason = 'user_continued_alone';
    }
    if (['step_shown', 'step_completed', 'step_repeated', 'user_lost'].includes(body.type)) stepEvent(record, body, screenAssistance, TASK_FLOWS[session.flowId]?.steps.map(s=>s.id) ?? GUIDED_STEPS);
    if (body.type === 'flow_reported' || body.type === 'practice_reported') {
      requireThat(!screenAssistance, 409, 'verification_unavailable', 'La asistencia de pantalla no acredita tareas completadas.');
      requireThat(record.currentStep === (TASK_FLOWS[session.flowId]?.steps.at(-1).id ?? 'verify') && (TASK_FLOWS[session.flowId]?.steps.map(s=>s.id) ?? GUIDED_STEPS).every((step,index)=>record.completedSteps[index]===step), 409, 'flow_incomplete', 'Recorré y confirmá todos los pasos antes de registrar el reporte.');
      record.state = 'reported'; record.reportedAt = timestamp; record.resultSource = 'self_reported';
    } else if (body.type === 'cancelled') { record.state = 'cancelled'; record.cancelledAt = timestamp; }
  }
  if (attemptId && ['user_lost', 'step_repeated'].includes(body.type)) record.assisted = true;
  if (body.durationSeconds !== undefined) record.durationSeconds = Math.max(record.durationSeconds ?? 0, body.durationSeconds);
  session.eventCounts ??= {}; session.eventCounts[body.type] = (session.eventCounts[body.type] ?? 0) + 1;
  session.lastEventAt = timestamp;
  if (['user_lost', 'step_repeated'].includes(body.type)) {
    session.difficultyByStep ??= {}; const step = body.stepId ?? 'unspecified'; session.difficultyByStep[step] = (session.difficultyByStep[step] ?? 0) + 1;
  }
  session.eventReceipts ??= {}; session.eventReceipts[receiptKey] = { payloadHash, recordedAt: timestamp };
  return { ok: true, duplicate: false };
}

/** Old client claims remain visible as unverified reports, never proof of autonomy. */
export function publicSession(session) {
  const legacy = session.state === 'completed';
  return { id: session.id, flowId: session.flowId, state: legacy ? 'reported' : session.state,
    createdAt: session.createdAt, startedAt: session.startedAt, reportedAt: session.reportedAt ?? (legacy ? session.completedAt : undefined),
    durationSeconds: session.durationSeconds ?? 0, completedSteps: session.completedSteps ?? [], autonomyCompleted: false,
    resultSource: legacy ? 'legacy_unverified' : session.resultSource ?? null,
    assistanceLevel: session.assistanceEndReason === 'user_continued_alone' ? 'partial' : 'full',
    assistanceEndedAt: session.assistanceEndedAt, assistanceEndReason: session.assistanceEndReason,
    practiceAttempts: (session.practiceAttempts ?? []).map(({ id, state, startedAt, reportedAt, cancelledAt, durationSeconds, completedSteps, assisted, resultSource }) => ({ id, state, startedAt, reportedAt, cancelledAt, durationSeconds, completedSteps, assisted, resultSource })),
    legacyPracticeReported: legacy && session.autonomyCompleted === true };
}

export function operationalMetrics(sessions) {
  return { completedFlows: 0, autonomyChecks: 0, reportedFlows: sessions.filter(reported).length,
    practiceReports: sessions.reduce((sum, session) => sum + (session.practiceAttempts ?? []).filter(attempt => attempt.state === 'reported').length + (session.state === 'completed' && session.autonomyCompleted ? 1 : 0), 0),
    accompaniedSeconds: sessions.reduce((sum, session) => sum + (session.durationSeconds ?? 0), 0) };
}
