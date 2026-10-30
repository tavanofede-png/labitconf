import { createHash } from 'node:crypto';

export const OPENARG_TOOLS = Object.freeze([
  'listar_fuentes', 'buscar_datasets', 'describir_tabla', 'obtener_datos',
  'agregar_datos', 'consultar_datos_publicos',
]);

export class OpenArgError extends Error {
  constructor(code, message, { status = 502, retryAfter = null } = {}) {
    super(message); this.name = 'OpenArgError'; this.code = code;
    this.status = status; this.retryAfter = retryAfter;
  }
}

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const stable = (value) => JSON.stringify(value, (_, v) => v && typeof v === 'object' && !Array.isArray(v)
  ? Object.fromEntries(Object.keys(v).sort().map((key) => [key, v[key]])) : v);

function classified(message, status = 502, retryAfter = null) {
  if (status === 401 || status === 403 || /falta la clave|invalid.{0,15}(key|clave)|clave.{0,60}(inválida|invalida|revocada|no tiene.{0,15}formato)|unauth/i.test(message))
    return new OpenArgError('credentials_required', 'OpenArg requiere una clave válida en OPENARG_API_KEY.', { status: 503 });
  if (status === 402 || /cupo.{0,50}(agot|mensual)|quota.{0,20}(exceed|exhaust)|usaste.{0,90}(consultas|preguntas).{0,40}este mes/i.test(message))
    return new OpenArgError('quota_exhausted', 'Se agotó el cupo mensual de OpenArg.', { status: 503 });
  if (status === 429 || /rate.?limit|límite por minuto|demasiadas (consultas|solicitudes)|esper[aá].{0,15}segundo/i.test(message)) {
    const explicitSeconds = /(?:esper[aá]|faltan)\s+(\d+)\s+segundo/i.exec(message)?.[1];
    return new OpenArgError('rate_limited', 'OpenArg alcanzó el límite por minuto. Reintentá más tarde.', { status: 429, retryAfter: retryAfter ?? (explicitSeconds ? Number(explicitSeconds) : null) });
  }
  return new OpenArgError('upstream_error', 'OpenArg no pudo completar la consulta. No se generaron datos de reemplazo.', { status });
}

/** Extract source links without turning returned Markdown into executable HTML. */
export function extractEvidence(text, args = {}) {
  const sources = [];
  for (const match of text.matchAll(/\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)/g)) {
    try {
      const url = new URL(match[2]);
      if (!sources.some((s) => s.url === url.href)) sources.push({ title: match[1], url: url.href });
    } catch { /* malformed upstream links are not evidence */ }
  }
  for (const match of text.matchAll(/https?:\/\/[^\s<>\[\]()"`]+/g)) {
    try {
      const url = new URL(match[0].replace(/[.,;]+$/, ''));
      if (!sources.some((s) => s.url === url.href)) sources.push({ title: url.hostname, url: url.href });
    } catch { /* ignore malformed URL */ }
  }
  const lines = text.split('\n').filter((line) => /per[ií]odo|[uú]ltimo dato|fecha de corte|frescura|cobertura temporal|actualiza|le[ií]d[ao]|vigencia/i.test(line)).slice(0, 15);
  return {
    sources, period: { from: args.desde ?? null, to: args.hasta ?? null, evidence: lines },
    warnings: sources.length ? [] : ['La respuesta no incluyó enlaces a fuentes. Verificá su procedencia antes de usarla.'],
  };
}

async function readMessage(response, id) {
  if (response.status === 202 || response.status === 204) return null;
  if (!(response.headers.get('content-type') ?? '').includes('text/event-stream')) {
    const message = await response.json();
    const found = (Array.isArray(message) ? message : [message]).find((m) => m.id === id);
    if (!found) throw new OpenArgError('invalid_response', 'OpenArg respondió sin el identificador esperado.');
    return found;
  }
  // Return as soon as our response arrives: a stream can stay open for notifications.
  const reader = response.body.getReader();
  const decoder = new TextDecoder(); let buffer = ''; let received = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, '\n');
      received += value?.length ?? 0;
      if (received > 5_000_000) throw new OpenArgError('invalid_response', 'OpenArg excedió el tamaño máximo de respuesta.');
      const blocks = buffer.split('\n\n'); buffer = blocks.pop();
      if (done && buffer.trim()) blocks.push(buffer);
      for (const block of blocks) {
        const data = block.split('\n').filter((line) => line.startsWith('data:')).map((line) => line.slice(5).trimStart()).join('\n');
        if (!data || data === '[DONE]') continue;
        const decoded = JSON.parse(data);
        const found = (Array.isArray(decoded) ? decoded : [decoded]).find((m) => m.id === id);
        if (found) return found;
      }
      if (done) break;
    }
  } finally { await reader.cancel().catch(() => {}); }
  throw new OpenArgError('invalid_response', 'OpenArg cerró el flujo sin una respuesta.');
}

/** Small Streamable HTTP client for OpenArg. No browser or Android receives its key. */
export class OpenArgClient {
  constructor({ apiKey = process.env.OPENARG_API_KEY ?? '', endpoint = 'https://mcp.openarg.org/mcp',
    fetchImpl = globalThis.fetch, timeoutMs = 25_000, cacheTtlMs = 300_000, now = Date.now,
    sleep = pause, maxRetryDelayMs = 5_000 } = {}) {
    const url = new URL(endpoint);
    if (url.protocol !== 'https:' && !['localhost', '127.0.0.1'].includes(url.hostname)) throw new Error('MCP requiere HTTPS.');
    this.apiKey = apiKey; this.endpoint = endpoint; this.fetch = fetchImpl; this.timeoutMs = timeoutMs;
    this.cacheTtlMs = cacheTtlMs; this.now = now; this.sleep = sleep; this.maxRetryDelayMs = maxRetryDelayMs;
    this.cache = new Map(); this.inflight = new Map(); this.sequence = 0; this.sessionId = null;
    this.protocolVersion = '2025-03-26'; this.ready = null; this.serverInfo = null; this.tools = [];
    this.lastSuccessAt = null; this.lastError = null;
  }
  status() {
    return { provider: 'OpenArg', transport: 'streamable-http', endpoint: this.endpoint,
      configured: Boolean(this.apiKey), status: !this.apiKey ? 'credentials_required' : this.lastError?.code ?? (this.lastSuccessAt ? 'connected' : 'not_checked'),
      lastSuccessAt: this.lastSuccessAt, lastError: this.lastError, tools: this.tools.map((t) => ({ name: t.name, description: t.description, inputSchema: t.inputSchema })),
      serverInfo: this.serverInfo, keyLocation: 'backend_environment', cacheTtlSeconds: Math.round(this.cacheTtlMs / 1000) };
  }
  async rpc(method, params, { notification = false, retryRateLimit = true } = {}) {
    const id = notification ? undefined : ++this.sequence;
    const payload = { jsonrpc: '2.0', ...(notification ? {} : { id }), method, ...(params === undefined ? {} : { params }) };
    for (let attempt = 0; attempt < 2; attempt++) {
      const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), this.timeoutMs);
      try {
        const response = await this.fetch(this.endpoint, { method: 'POST', redirect: 'error', signal: controller.signal,
          headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream',
            'MCP-Protocol-Version': this.protocolVersion,
            ...(this.apiKey ? { Authorization: `Bearer ${this.apiKey}` } : {}),
            ...(this.sessionId ? { 'Mcp-Session-Id': this.sessionId } : {}) }, body: JSON.stringify(payload) });
        const sessionId = response.headers.get('mcp-session-id'); if (sessionId) this.sessionId = sessionId;
        if (!response.ok) {
          if (response.status === 404 && this.sessionId && method !== 'initialize') throw new OpenArgError('session_expired', 'La sesión MCP venció.');
          const rawRetry = response.headers.get('retry-after');
          const retryAfter = rawRetry ? Math.max(0, /^\d+$/.test(rawRetry) ? Number(rawRetry) : Math.ceil((Date.parse(rawRetry) - this.now()) / 1000)) : null;
          const error = classified(await response.text(), response.status, retryAfter);
          if (attempt === 0 && retryRateLimit && error.code === 'rate_limited' && retryAfter !== null && Number.isFinite(retryAfter) && retryAfter * 1000 <= this.maxRetryDelayMs) {
            clearTimeout(timer); await this.sleep(retryAfter * 1000); continue;
          }
          throw error;
        }
        if (notification) { await response.body?.cancel(); return null; }
        const message = await readMessage(response, id);
        if (!message) throw new OpenArgError('invalid_response', 'OpenArg no devolvió el resultado esperado.');
        if (message.error) throw classified(String(message.error.message));
        return message.result;
      } catch (error) {
        if (error instanceof OpenArgError) throw error;
        if (controller.signal.aborted) throw new OpenArgError('timeout', 'OpenArg demoró demasiado. La consulta no se repite automáticamente.', { status: 504 });
        throw new OpenArgError('unavailable', 'No se pudo contactar a OpenArg.', { status: 503 });
      } finally { clearTimeout(timer); }
    }
  }
  async connect() {
    if (!this.ready) this.ready = (async () => {
      const result = await this.rpc('initialize', { protocolVersion: this.protocolVersion, capabilities: {}, clientInfo: { name: 'nieto-labitconf', version: '2.0.0' } });
      this.protocolVersion = result.protocolVersion; this.serverInfo = result.serverInfo;
      await this.rpc('notifications/initialized', undefined, { notification: true });
      const listing = await this.rpc('tools/list', {});
      this.tools = listing.tools ?? []; return this.tools;
    })().catch((error) => { this.ready = null; if (error.code === 'session_expired') this.sessionId = null; throw error; });
    return this.ready;
  }
  async discover() {
    try { await this.connect(); return this.status(); }
    catch (error) { this.lastError = { code: error.code, message: error.message }; return this.status(); }
  }
  async call(tool, args = {}, { refresh = false } = {}) {
    if (!OPENARG_TOOLS.includes(tool)) throw new OpenArgError('invalid_tool', 'Herramienta OpenArg no permitida.', { status: 400 });
    if (!this.apiKey) throw new OpenArgError('credentials_required', 'Falta OPENARG_API_KEY en el backend. Creá tu clave en https://openarg.org/desarrolladores.', { status: 503 });
    const key = createHash('sha256').update(stable({ tool, args })).digest('hex');
    const cached = this.cache.get(key);
    if (!refresh && cached && cached.expires > this.now()) return { ...cached.value, cached: true };
    if (this.inflight.has(key)) return this.inflight.get(key);
    const work = (async () => {
      try {
        await this.connect();
        if (!this.tools.some((entry) => entry.name === tool)) throw new OpenArgError('tool_unavailable', 'OpenArg no anunció esa herramienta.');
        let result; let text;
        for (let attempt = 0; attempt < 2; attempt++) {
          try {
            result = await this.rpc('tools/call', { name: tool, arguments: args }, { retryRateLimit: false });
            text = (result.content ?? []).filter((block) => block.type === 'text').map((block) => block.text).join('\n');
            if (result.isError) throw classified(text);
            break;
          } catch (error) {
            if (attempt === 0 && error.code === 'session_expired') { this.ready = null; this.sessionId = null; await this.connect(); continue; }
            if (attempt === 0 && error.code === 'rate_limited' && error.retryAfter !== null && error.retryAfter * 1000 <= this.maxRetryDelayMs) { await this.sleep(error.retryAfter * 1000); continue; }
            throw error;
          }
        }
        const retrievedAt = new Date(this.now()).toISOString();
        const value = { provider: 'OpenArg', transport: 'streamable-http', tool, arguments: args, status: 'ok',
          text, structured: result.structuredContent ?? null, ...extractEvidence(text, args), retrievedAt, cached: false,
          expiresAt: new Date(this.now() + this.cacheTtlMs).toISOString() };
        this.lastSuccessAt = retrievedAt; this.lastError = null;
        if (this.cache.size >= 200) this.cache.delete(this.cache.keys().next().value);
        this.cache.set(key, { value, expires: this.now() + this.cacheTtlMs }); return value;
      } catch (error) {
        this.lastError = { code: error.code ?? 'unavailable', message: error.message };
        throw error;
      } finally { this.inflight.delete(key); }
    })();
    this.inflight.set(key, work); return work;
  }
}
