import { EventEmitter } from 'node:events';
import { createHash, randomUUID } from 'node:crypto';
import WebSocket from 'ws';

export const REALTIME_MODEL = 'gpt-realtime-2.1-mini';
export const AUDIO_RATE = 24_000;
export const MAX_INPUT_SECONDS = 45;
export const MAX_AUDIO_CHUNK = 12_000;
const MAX_INPUT_BYTES = AUDIO_RATE * 2 * MAX_INPUT_SECONDS;
const MIN_INPUT_BYTES = AUDIO_RATE * 2 / 10;
const MAX_OUTPUT_BYTES = AUDIO_RATE * 2 * 90;
const MAX_OUTPUT_TEXT = 12_000;
const OFFER_TOOL = {
  type: 'function', name: 'offer_guided_flow',
  description: 'Ofrece la guía local para compartir ubicación por WhatsApp solo si la persona la solicita afirmativamente. No la inicia ni ejecuta acciones.',
  parameters: { type: 'object', properties: { flowId: { type: 'string', enum: ['whatsapp-location'] } }, required: ['flowId'], additionalProperties: false },
};
const INSTRUCTIONS = `Sos NIETO, un acompañante de autonomía digital. Hablá en español de Argentina, con claridad, paciencia y respuestas breves. Distingui la conversación de una guía verificada. No ves la pantalla, no recibís imágenes ni mensajes de otras aplicaciones. Nunca inventes botones, coordenadas, pasos verificados, resultados ni envíos. Si la persona quiere compartir ubicación por WhatsApp, podés ofrecer la única guía local con offer_guided_flow. Una negación, una cancelación o una pregunta sobre otra tarea no solicita compartir ubicación. La persona debe elegir iniciar la guía y confirmar cualquier envío. No podés tocar controles, enviar mensajes, pedir ayuda a la familia, cambiar permisos ni registrar progreso. No pidas ni repitas contraseñas, OTP o datos bancarios. Si no podés verificar algo, decilo. No afirmes que una tarea se completó: esa verificación pertenece al motor local. Para asuntos fuera de tu alcance, explicá el límite y ofrecé una alternativa comprensible. No presentes esta conversación como un servicio de emergencias. No hay otra herramienta habilitada.`;

export class RealtimeError extends Error {
  constructor(code, message, status = 502, fatal = true) { super(message); this.name = 'RealtimeError'; this.code = code; this.status = status; this.fatal = fatal; }
}

export function providerError(status, code = '') {
  if (status === 401 || /invalid_api_key|authentication/i.test(code)) return new RealtimeError('openai_credentials', 'La clave de OpenAI no fue autorizada. Revisá la configuración del servidor.', 503);
  if (/insufficient_quota|billing|quota_exceeded/i.test(code) || status === 402) return new RealtimeError('openai_quota', 'OpenAI no tiene cuota o saldo disponible para esta consulta.', 503);
  if (status === 403 || /model_not_found|model_not_supported|permission_denied/i.test(code)) return new RealtimeError('openai_model_access', 'La cuenta no tiene acceso al modelo gpt-realtime-2.1-mini.', 503);
  if (status === 429 || /rate_limit/i.test(code)) return new RealtimeError('openai_rate_limit', 'OpenAI alcanzó el límite de consultas. Esperá un momento y volvé a intentar.', 429);
  return new RealtimeError('openai_unavailable', 'No se pudo completar la conexión con OpenAI. Podés volver a intentar.', 503);
}

function invalid(message) { return new RealtimeError('invalid_realtime_message', message, 400, false); }
function exactKeys(value, allowed) { return value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).every(key => allowed.includes(key)); }
function pcmBytes(audio) {
  if (typeof audio !== 'string' || !audio.length || audio.length > 16_000 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(audio)) throw invalid('El bloque de audio no tiene un formato válido.');
  const bytes = Buffer.from(audio, 'base64');
  if (!bytes.length || bytes.length > MAX_AUDIO_CHUNK || bytes.length % 2 || bytes.toString('base64') !== audio) throw invalid('Usá bloques PCM16 de hasta 12000 bytes.');
  return bytes.length;
}

/** Owns one upstream session. Only the reduced NIETO protocol reaches this class. */
export class RealtimeConversation extends EventEmitter {
  constructor({ apiKey, deviceId = 'verification', mode = 'audio', endpoint = 'wss://api.openai.com/v1/realtime',
    socketFactory = (url, options) => new WebSocket(url, options), connectTimeoutMs = 15_000,
    responseTimeoutMs = 60_000, idleTimeoutMs = 120_000, maxSessionMs = 10 * 60_000, maxTurns = 20,
  } = {}) {
    super();
    const url = new URL(endpoint);
    const openAi = url.protocol === 'wss:' && url.hostname === 'api.openai.com' && !url.port && url.pathname === '/v1/realtime';
    const localTest = url.protocol === 'ws:' && ['127.0.0.1', 'localhost'].includes(url.hostname);
    if ((!openAi && !localTest) || url.username || url.password || url.hash) throw new Error('Endpoint Realtime no permitido.');
    this.apiKey = apiKey; this.deviceId = deviceId; this.mode = mode; this.endpoint = endpoint; this.socketFactory = socketFactory;
    this.connectTimeoutMs = connectTimeoutMs; this.responseTimeoutMs = responseTimeoutMs; this.idleTimeoutMs = idleTimeoutMs; this.maxSessionMs = maxSessionMs; this.maxTurns = maxTurns;
    this.socket = null; this.ready = false; this.closed = false; this.recording = false; this.inputBytes = 0; this.turns = 0;
    this.activeTurn = null; this.turnState = new Map(); this.responses = new Map(); this.pendingResponses = [];
    this.connectPromise = null; this.connectResolve = null; this.connectReject = null;
  }
  get configured() { return Boolean(this.apiKey?.trim()); }
  publish(event) { if (!this.closed) this.emit('event', event); }
  touch() {
    clearTimeout(this.idleTimer);
    this.idleTimer = setTimeout(() => this.fail(new RealtimeError('realtime_idle', 'La conversación se cerró por inactividad. Tocá Hablar para volver a conectar.', 408)), this.idleTimeoutMs);
    this.idleTimer.unref?.();
  }
  connect() {
    if (!this.configured) return Promise.reject(new RealtimeError('openai_not_configured', 'Falta OPENAI_API_KEY en el servidor para conversar con NIETO.', 503));
    if (this.connectPromise) return this.connectPromise;
    this.connectPromise = new Promise((resolve, reject) => { this.connectResolve = resolve; this.connectReject = reject; });
    const url = new URL(this.endpoint); url.searchParams.set('model', REALTIME_MODEL);
    try {
      this.socket = this.socketFactory(url.toString(), { headers: { Authorization: `Bearer ${this.apiKey}`, 'OpenAI-Safety-Identifier': createHash('sha256').update(this.deviceId).digest('hex') }, maxPayload: 512_000, handshakeTimeout: this.connectTimeoutMs });
      this.connectTimer = setTimeout(() => this.fail(new RealtimeError('openai_timeout', 'OpenAI demoró demasiado en preparar la conversación.', 504)), this.connectTimeoutMs);
      this.sessionTimer = setTimeout(() => this.fail(new RealtimeError('realtime_session_limit', 'La conversación llegó a su límite de duración. Podés iniciar otra.', 408)), this.maxSessionMs);
      this.connectTimer.unref?.(); this.sessionTimer.unref?.(); this.touch();
      this.socket.on('message', (raw, binary) => { if (!binary) this.handleUpstream(raw); else this.fail(providerError(502)); });
      this.socket.on('unexpected-response', (_request, response) => { response.resume?.(); this.fail(providerError(response.statusCode)); });
      this.socket.on('error', () => this.fail(providerError(503)));
      this.socket.on('close', () => { if (!this.closed) this.fail(new RealtimeError('openai_disconnected', 'La conexión de voz se interrumpió. Podés volver a conectar.', 503)); });
    } catch { this.fail(providerError(503)); }
    return this.connectPromise;
  }
  send(event) {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) throw providerError(503);
    if (this.socket.bufferedAmount > 256_000) throw new RealtimeError('realtime_backpressure', 'La conexión no puede enviar audio con suficiente rapidez. Volvé a intentar.', 503);
    this.socket.send(JSON.stringify(event));
  }
  configure() {
    this.send({ type: 'session.update', session: { type: 'realtime', model: REALTIME_MODEL,
      instructions: INSTRUCTIONS, output_modalities: [this.mode], max_output_tokens: 800,
      audio: { input: { format: { type: 'audio/pcm', rate: AUDIO_RATE }, turn_detection: null },
        output: { format: { type: 'audio/pcm', rate: AUDIO_RATE }, voice: 'marin' } },
      tools: [OFFER_TOOL], tool_choice: 'auto',
    } });
  }
  handleUpstream(raw) {
    if (this.closed) return;
    let event;
    try { event = JSON.parse(raw.toString()); } catch { this.fail(providerError(502)); return; }
    try {
      if (event.type === 'session.created') { this.configure(); return; }
      if (event.type === 'session.updated') {
        if (!this.ready) { this.ready = true; clearTimeout(this.connectTimer); this.connectResolve?.(this); this.connectReject = null;
          this.publish({ type: 'ready', model: REALTIME_MODEL, audio: { encoding: 'pcm16', sampleRate: AUDIO_RATE, channels: 1 }, maxInputSeconds: MAX_INPUT_SECONDS }); }
        return;
      }
      if (event.type === 'error') {
        if (/response_cancel_not_active|input_audio_buffer_commit_empty/.test(event.error?.code ?? '')) return;
        this.fail(providerError(502, event.error?.code)); return;
      }
      if (event.type === 'response.created') {
        const response = event.response ?? {};
        const metadataTurn = response.metadata?.nieto_turn_id;
        const pending = metadataTurn ? this.pendingResponses.findIndex(id => id === metadataTurn) : 0;
        const turnId = pending >= 0 ? this.pendingResponses.splice(pending, 1)[0] : undefined;
        if (response.id && turnId) this.responses.set(response.id, turnId);
        return;
      }
      const responseId = event.response_id ?? event.response?.id;
      const turnId = this.responses.get(responseId);
      const turn = this.turnState.get(turnId);
      if (!turn || turn.cancelled || turnId !== this.activeTurn) return;
      if (event.type === 'response.output_audio.delta') {
        const size = Buffer.from(event.delta ?? '', 'base64').length; turn.outputBytes += size;
        if (turn.outputBytes > MAX_OUTPUT_BYTES) throw new RealtimeError('realtime_output_limit', 'La respuesta de voz superó el límite de duración.', 502);
        this.publish({ type: 'audio.delta', turnId, audio: event.delta });
      } else if (['response.output_audio_transcript.delta', 'response.output_text.delta'].includes(event.type)) {
        if (typeof event.delta !== 'string') return;
        turn.text += event.delta;
        if (turn.text.length > MAX_OUTPUT_TEXT) throw new RealtimeError('realtime_output_limit', 'La respuesta superó el límite de tamaño.', 502);
        this.publish({ type: 'text.delta', turnId, delta: event.delta });
      } else if (['response.output_audio_transcript.done', 'response.output_text.done'].includes(event.type)) {
        const text = event.transcript ?? event.text ?? turn.text;
        if (typeof text === 'string' && text.length <= MAX_OUTPUT_TEXT) { turn.text = text; this.publish({ type: 'text.done', turnId, text }); }
      } else if (event.type === 'response.done') {
        const response = event.response;
        const calls = (response.output ?? []).filter(item => item.type === 'function_call');
        if (response.status === 'completed' && calls.length) {
          if (turn.toolRounds++ >= 1 || calls.length > 1) throw new RealtimeError('realtime_tool_limit', 'No pude preparar una sugerencia de guía válida. Podés iniciar la guía desde el botón de NIETO.', 502);
          for (const call of calls) {
            let args; try { args = JSON.parse(call.arguments); } catch { args = null; }
            const allowed = call.name === 'offer_guided_flow' && exactKeys(args, ['flowId']) && args.flowId === 'whatsapp-location';
            if (allowed) { turn.flowId = args.flowId; this.publish({ type: 'guidance.offer', turnId, flowId: args.flowId }); }
            this.send({ type: 'conversation.item.create', item: { type: 'function_call_output', call_id: call.call_id,
              output: JSON.stringify(allowed ? { offered: true, flowId: args.flowId, requiresExplicitUserAction: true, started: false } : { error: 'unsupported_tool', executed: false }) } });
          }
          this.requestResponse(turnId); return;
        }
        clearTimeout(this.responseTimer);
        const status = response.status === 'completed' ? 'completed' : response.status === 'cancelled' ? 'cancelled' : 'failed';
        this.publish({ type: 'response.done', turnId, status });
        this.emit('result', { turnId, status, text: turn.text, flowId: turn.flowId });
        this.activeTurn = null; this.touch();
      }
    } catch (error) { this.fail(error instanceof RealtimeError ? error : providerError(502)); }
  }
  newTurn() {
    if (++this.turns > this.maxTurns) throw new RealtimeError('realtime_turn_limit', 'Esta conversación llegó a su límite de turnos. Iniciá una nueva.', 429);
    const turnId = randomUUID(); this.activeTurn = turnId;
    this.turnState.set(turnId, { cancelled: false, text: '', outputBytes: 0, toolRounds: 0, flowId: undefined });
    return turnId;
  }
  requestResponse(turnId) {
    this.pendingResponses.push(turnId);
    this.send({ type: 'response.create', response: { output_modalities: [this.mode], metadata: { nieto_turn_id: turnId }, max_output_tokens: 800 } });
  }
  beginResponse() {
    const turnId = this.newTurn(); this.publish({ type: 'response.started', turnId });
    this.requestResponse(turnId); clearTimeout(this.responseTimer);
    this.responseTimer = setTimeout(() => this.fail(new RealtimeError('openai_timeout', 'La respuesta de OpenAI tardó demasiado. Volvé a intentar.', 504)), this.responseTimeoutMs);
    this.responseTimer.unref?.(); return turnId;
  }
  handleClient(event) {
    if (!this.ready || this.closed) throw invalid('Esperá a que la conexión de NIETO esté lista.');
    this.touch();
    if (!exactKeys(event, ['type', 'audio', 'text'])) throw invalid('El mensaje contiene campos no permitidos.');
    if (event.type === 'close') { if (!exactKeys(event, ['type'])) throw invalid('El cierre no admite parámetros.'); this.close(); return; }
    if (event.type === 'cancel') { if (!exactKeys(event, ['type'])) throw invalid('La cancelación no admite parámetros.'); this.cancel(); return; }
    if (event.type === 'audio.start') {
      if (!exactKeys(event, ['type'])) throw invalid('El inicio de captura no admite parámetros.');
      this.cancel(false); this.recording = true; this.inputBytes = 0;
      this.captureTimer = setTimeout(() => { this.cancel(false); this.publish({ type: 'error', code: 'audio_limit', message: 'La captura llegó al máximo de 45 segundos. Tocá Hablar para intentar nuevamente.', fatal: false }); }, MAX_INPUT_SECONDS * 1000 + 2000);
      this.captureTimer.unref?.(); return;
    }
    if (event.type === 'audio.append') {
      if (!exactKeys(event, ['type', 'audio']) || !this.recording) throw invalid('Primero iniciá una captura de voz.');
      const bytes = pcmBytes(event.audio);
      if (this.inputBytes + bytes > MAX_INPUT_BYTES) { this.cancel(false); throw invalid('La captura supera el máximo de 45 segundos.'); }
      this.inputBytes += bytes; this.send({ type: 'input_audio_buffer.append', audio: event.audio }); return;
    }
    if (event.type === 'audio.commit') {
      if (!exactKeys(event, ['type']) || !this.recording) throw invalid('No hay una captura de voz activa.');
      clearTimeout(this.captureTimer); this.recording = false;
      if (this.inputBytes < MIN_INPUT_BYTES) { this.inputBytes = 0; this.send({ type: 'input_audio_buffer.clear' }); throw invalid('La captura fue demasiado corta. Hablá al menos un momento y volvé a intentar.'); }
      this.inputBytes = 0; this.send({ type: 'input_audio_buffer.commit' }); this.beginResponse(); return;
    }
    if (event.type === 'text') {
      if (!exactKeys(event, ['type', 'text']) || typeof event.text !== 'string' || !event.text.trim() || event.text.length > 1000) throw invalid('La consulta debe tener entre 1 y 1000 caracteres.');
      this.cancel(false);
      this.send({ type: 'conversation.item.create', item: { type: 'message', role: 'user', content: [{ type: 'input_text', text: event.text.trim() }] } });
      this.beginResponse(); return;
    }
    throw invalid('Este tipo de mensaje no está habilitado para NIETO.');
  }
  cancel(notify = true) {
    clearTimeout(this.captureTimer); clearTimeout(this.responseTimer);
    this.recording = false; this.inputBytes = 0;
    if (this.activeTurn) { const turn = this.turnState.get(this.activeTurn); if (turn) turn.cancelled = true; this.activeTurn = null; this.send({ type: 'response.cancel' }); }
    this.send({ type: 'input_audio_buffer.clear' });
    if (notify) this.publish({ type: 'cancelled' });
  }
  fail(error) {
    if (this.closed) return;
    const safe = error instanceof RealtimeError ? error : providerError(502);
    this.publish({ type: 'error', code: safe.code, message: safe.message, fatal: true });
    this.connectReject?.(safe); this.connectReject = null; this.emit('failure', safe); this.close();
  }
  close() {
    if (this.closed) return; this.closed = true;
    for (const timer of [this.connectTimer, this.sessionTimer, this.idleTimer, this.responseTimer, this.captureTimer]) clearTimeout(timer);
    this.connectReject?.(new RealtimeError('realtime_closed', 'La conversación se cerró.', 499)); this.connectReject = null;
    if (this.socket) { this.socket.on('error', () => {}); this.socket.terminate?.(); }
    this.turnState.clear(); this.responses.clear(); this.pendingResponses.length = 0; this.activeTurn = null;
    this.emit('closed');
  }
}

export class RealtimeService {
  constructor({ apiKey = process.env.OPENAI_API_KEY ?? '', conversationOptions = {}, conversationFactory = options => new RealtimeConversation(options), maxConnections = 8, httpTimeoutMs = 65_000 } = {}) {
    this.apiKey = apiKey; this.options = conversationOptions; this.factory = conversationFactory; this.maxConnections = maxConnections; this.httpTimeoutMs = httpTimeoutMs; this.active = new Map();
  }
  status() { const configured = Boolean(this.apiKey?.trim()); return { configured, model: REALTIME_MODEL, voiceStreaming: configured, generalConversation: configured, mode: configured ? 'openai_realtime' : 'unconfigured', screenInput: false }; }
  reserve(deviceId, mode = 'audio') {
    if (!this.status().configured) throw new RealtimeError('openai_not_configured', 'Falta OPENAI_API_KEY en el servidor para conversar con NIETO.', 503);
    if (this.active.has(deviceId)) throw new RealtimeError('realtime_already_active', 'Este teléfono ya tiene una conversación activa. Cerrala antes de iniciar otra.', 409);
    if (this.active.size >= this.maxConnections) throw new RealtimeError('realtime_busy', 'NIETO está atendiendo otras conversaciones. Volvé a intentar en un momento.', 429);
    const conversation = this.factory({ ...this.options, apiKey: this.apiKey, deviceId, mode });
    this.active.set(deviceId, conversation);
    conversation.once('closed', () => { if (this.active.get(deviceId) === conversation) this.active.delete(deviceId); });
    return conversation;
  }
  async reply({ deviceId, text, signal }) {
    const conversation = this.reserve(deviceId, 'text');
    let cleanup = () => {};
    try {
      const result = new Promise((resolve, reject) => {
        const timer = setTimeout(() => { reject(new RealtimeError('openai_timeout', 'La respuesta de OpenAI tardó demasiado.', 504)); conversation.close(); }, this.httpTimeoutMs);
        const onAbort = () => { reject(new RealtimeError('request_cancelled', 'La consulta se canceló.', 499)); conversation.close(); };
        const onFailure = error => reject(error);
        const onResult = result => result.status === 'completed' && result.text.trim() ? resolve(result) : reject(new RealtimeError('openai_response_incomplete', 'OpenAI no completó la respuesta. Podés volver a intentar.', 502));
        conversation.once('failure', onFailure); conversation.once('result', onResult);
        signal?.addEventListener('abort', onAbort, { once: true });
        cleanup = () => { clearTimeout(timer); conversation.off('failure', onFailure); conversation.off('result', onResult); signal?.removeEventListener('abort', onAbort); };
      });
      // Attach the result's rejection handler before connecting to avoid unhandled
      // failures while the same upstream error also rejects connect().
      result.catch(() => {});
      if (signal?.aborted) throw new RealtimeError('request_cancelled', 'La consulta se canceló.', 499);
      await conversation.connect();
      if (signal?.aborted) throw new RealtimeError('request_cancelled', 'La consulta se canceló.', 499);
      conversation.handleClient({ type: 'text', text });
      const value = await result;
      return { mode: value.flowId ? 'guided' : 'conversation', instruction: value.text, ...(value.flowId ? { flowId: value.flowId } : {}), sources: [], model: REALTIME_MODEL };
    } finally { cleanup(); conversation.close(); }
  }
  close() { for (const conversation of [...this.active.values()]) conversation.close(); }
}
