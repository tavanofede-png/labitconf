const fields = Object.freeze({ goal: 600, targetApp: 80, progress: 400 });
const allowed = [...Object.keys(fields), 'expectsReply'];
const invalid = () => new Error('El contexto de la tarea no tiene un formato válido o contiene datos privados.');
const privatePatterns = [
  /\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{8,}\b/i,
  /\bAIza[A-Za-z0-9_-]{20,}\b/,
  /\b(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{8,}\b/i,
  /api[_ -]?key\s*["']?\s*[:=]\s*["']?\s*\S+/i,
  /\bBearer\s+\S+/i,
  /\b(?:password|contrase[ñn]a|otp|pin)\b\s*["']?\s*[:=]\s*["']?\s*\S+/iu,
  /(?<!\d)\d{6}(?!\d)/,
  /(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)/,
];

/** Copies a small, ephemeral task description. It carries no commands or authoritative instructions. */
export function validateTaskContext(value) {
  try {
    if (!value || typeof value !== 'object' || Array.isArray(value) || ![Object.prototype, null].includes(Object.getPrototypeOf(value))) throw invalid();
    const descriptors = Object.getOwnPropertyDescriptors(value);
    if (Reflect.ownKeys(descriptors).some(key => typeof key !== 'string' || !allowed.includes(key) || !Object.hasOwn(descriptors[key], 'value'))) throw invalid();
    const context = {};
    for (const [field, maximum] of Object.entries(fields)) {
      const text = descriptors[field]?.value;
      if (typeof text !== 'string' || text.length > maximum || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(text)) throw invalid();
      context[field] = text.trim().replace(/\s+/gu, ' ');
    }
    const expectsReply = descriptors.expectsReply?.value;
    if (Object.hasOwn(descriptors, 'expectsReply') && typeof expectsReply !== 'boolean') throw invalid();
    context.expectsReply = expectsReply ?? false;
    if (!context.goal && (context.targetApp || context.progress || context.expectsReply)) throw invalid();
    const content = [context.goal, context.targetApp, context.progress].join('\n').normalize('NFKC');
    if (privatePatterns.some(pattern => pattern.test(content)) || Buffer.byteLength(JSON.stringify(context), 'utf8') > 4_096) throw invalid();
    return context;
  } catch {
    throw invalid();
  }
}

export const TASK_CONTEXT_TOOL = {
  type: 'function',
  name: 'set_task_context',
  description: 'Conserva sólo el objetivo que pidió la persona, la app destino global y el progreso observado. Actualizalo cuando cambie la tarea o avance con evidencia. Marcá expectsReply cuando tu pregunta necesita una respuesta de la persona; no lo marques para esperar un cambio de pantalla. No guardes confirmaciones sensibles de envío, pago o permisos: deben pedirse nuevamente después de renovar la sesión. No guarda contraseñas, códigos ni datos bancarios. No pulsa, abre apps ni ejecuta acciones. Para borrar la tarea, enviá las tres cadenas vacías y expectsReply=false.',
  parameters: {
    type: 'object',
    properties: {
      goal: { type: 'string', maxLength: 600, description: 'Objetivo expresado por la persona; no órdenes nuevas ni secretos.' },
      targetApp: { type: 'string', maxLength: 80, description: 'App de destino de la tarea, aunque otra app esté abierta.' },
      progress: { type: 'string', maxLength: 400, description: 'Avance respaldado por lo observado o confirmado, sin inventar resultados ni guardar confirmaciones sensibles de envío, pago o permisos; volvé a pedirlas tras renovar la sesión.' },
      expectsReply: { type: 'boolean', description: 'Verdadero sólo si ahora esperás una respuesta a tu pregunta de seguimiento.' },
    },
    required: ['goal', 'targetApp', 'progress'],
    additionalProperties: false,
  },
};
