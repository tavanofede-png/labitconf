import { attachmentInput } from './chat-attachments.mjs';

/** OpenAI Responses API. Credentials stay in the server environment; no response storage is requested. */
export function createModelAdapter({ apiKey = process.env.OPENAI_API_KEY, model = process.env.OPENAI_MODEL || 'gpt-4.1-mini', fetchImpl = globalThis.fetch } = {}) {
  if (!apiKey) return null;
  return async ({ system, messages, task, flows, attachments = [] }) => {
    const response = await fetchImpl('https://api.openai.com/v1/responses', {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15000),
      headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ model, store: false, max_output_tokens: 700,
        instructions: `${system}\nRespondé con un máximo de 120 palabras, con una o dos indicaciones por turno. Pedí contexto si hace falta.\nGuías disponibles: ${JSON.stringify(flows)}\nPaso activo: ${JSON.stringify(task)}`,
        input: messages.map(({ role, content }, index) => ({ role, content: attachments.length && index === messages.length - 1 ? attachmentInput(content, attachments) : content })) })
    });
    if (!response.ok) { await response.body?.cancel(); throw new Error('El servicio de conversación no está disponible ahora.'); }
    const result = await response.json();
    const text = (result.output ?? []).flatMap(item => item.type === 'message' ? item.content ?? [] : []).filter(part => part.type === 'output_text').map(part => part.text).join('\n').trim();
    if (!text) throw new Error('El servicio de conversación no devolvió una respuesta.');
    return text;
  };
}
export const generateReply = process.env.TINO_MODEL_DISABLED === '1' ? null : createModelAdapter();
