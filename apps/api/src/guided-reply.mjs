import { validateTaskContext } from './task-context.mjs';

export const GUIDED_REPLY_TOOL = {
  type: 'function', name: 'prepare_tino_reply',
  description: 'Prepará tu respuesta actual antes de hablar. Conversación libre: kind=conversation. Tarea de teléfono: kind=step y UNA única acción inmediata. Fin solicitado o verificado: kind=end, sin despedida.',
  parameters: { type: 'object', properties: {
    kind: { type: 'string', enum: ['conversation', 'step', 'end'] },
    action: { type: 'string', enum: ['none', 'home', 'back', 'tap', 'swipe', 'type', 'adjust'],
      description: 'home=volver a Inicio, tanto con gesto como con botón. back=Atrás, con gesto o botón. swipe=desplazar o desplegar un panel dentro de la app; NO volver a Inicio. tap=un control actual. none=conversación o fin.' },
    message: { type: 'string', maxLength: 2000, description: 'Tus palabras. Para step: UNA instrucción de hasta 160 caracteres, sólo la acción inmediata, en segunda persona; sin saludo, plan, alternativas ni pasos posteriores. Para conversation: respuesta libre al turno actual. Para end: vacío.' },
    goal: { type: 'string', maxLength: 600, description: 'Objetivo COMPLETO solicitado; no reducirlo al paso de abrir una app. Vacío si no hay tarea.' },
    targetApp: { type: 'string', maxLength: 80, description: 'App de destino FINAL del objetivo; no la pantalla intermedia ni la app actual.' },
    progress: { type: 'string', maxLength: 400, description: 'Avance real y única acción pendiente, sin valores privados.' },
    screenId: { type: 'string', description: 'Snapshot vigente si hay un control que señalar; vacío si no.' },
    controlId: { type: 'string', description: 'Sólo el control de esta acción. Vacío para Inicio/Atrás del sistema: nunca señales otro botón de la app para eso.' },
    endReason: { type: 'string', enum: ['', 'user_end', 'task_completed'] },
  }, required: ['kind','action','message','goal','targetApp','progress','screenId','controlId','endReason'], additionalProperties: false },
};

/** One bounded action is prepared before any voice is released. Words come from the model. */
export function validateGuidedReply(value, screen) {
  const fields = GUIDED_REPLY_TOOL.parameters.required;
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).length !== fields.length ||
      Object.keys(value).some(key => !fields.includes(key)) || fields.some(key => typeof value[key] !== 'string')) throw new Error('invalid_reply');
  if (!['conversation','step','end'].includes(value.kind)) throw new Error('invalid_kind');
  if (!fields.every(key => value[key].length <= (GUIDED_REPLY_TOOL.parameters.properties[key].maxLength ?? 100))) throw new Error('reply_too_long');
  const message = value.message.trim();
  if (value.kind === 'end') {
    if (!['user_end','task_completed'].includes(value.endReason) || message || value.goal || value.targetApp || value.progress || value.action !== 'none' || value.controlId || value.screenId) throw new Error('invalid_end');
    return { kind: 'end', endReason: value.endReason };
  }
  if (!message || value.endReason) throw new Error('invalid_message');
  if (value.kind === 'conversation') {
    if (value.action !== 'none' || value.controlId || value.screenId) throw new Error('conversation_has_action');
    return { kind: 'conversation', message };
  }
  const normalized = message.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  if (value.action === 'swipe' && /(?:volver|volve).*(?:inicio|pantalla principal)/u.test(normalized)) throw new Error('use_home_for_system_navigation');
  if (!['home','back','tap','swipe','type','adjust'].includes(value.action) || message.length > 160 ||
      /\n|(?:^|\s)\d+[.)]|\b(?:despues|luego|posteriormente|finalmente|toco|abro|pulso)\b|\by\s+(?:toca|abri|busca|desliza|volve|entra|selecciona|presiona|move)\b/u.test(normalized)) throw new Error('one_immediate_action_only');
  const context = validateTaskContext({ goal: value.goal, targetApp: value.targetApp, progress: value.progress, expectsReply: false });
  if (!context.goal) throw new Error('step_requires_complete_goal');
  if (['home','back'].includes(value.action) && value.controlId) throw new Error('system_navigation_has_no_app_target');
  if (value.screenId && value.screenId !== screen?.id) throw new Error('stale_or_unknown_control');
  let point;
  if (value.controlId) {
    if (!screen || screen.id !== value.screenId || !screen.controls.some(control => control.id === value.controlId)) throw new Error('stale_or_unknown_control');
    point = { screenId: screen.id, controlId: value.controlId, instruction: message };
  } else if (['tap','adjust'].includes(value.action)) throw new Error('action_requires_current_control');
  return { kind: 'step', action: value.action, message, context, point };
}
