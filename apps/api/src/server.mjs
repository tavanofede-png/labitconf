import http from 'node:http';
import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { stat } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { JsonStore } from './store.mjs';
import { OpenArgClient, OpenArgError } from './openarg.mjs';
import { normalizeSearch, normalizeAnswer } from './normalize.mjs';
import { getTerritoryIndicators } from './territory.mjs';
import { institutionDashboard, compareProgram, periodFor, summarize } from './institution.mjs';
import { createAccounts, AccountError } from './accounts.mjs';
import { createAssistant, TASK_FLOWS } from './assistant.mjs';
import { generateReply } from './model-adapter.mjs';
import { demoInstitution, demoFamily, DEMO_TERRITORIES } from './dashboard-demo.mjs';
import { createDashboardContext, crossPublicContext } from './dashboard-context.mjs';

const API_ROOT = fileURLToPath(new URL('../', import.meta.url));
const TYPES = new Set(['flow_started', 'step_shown', 'step_completed', 'step_repeated', 'user_lost', 'flow_completed', 'autonomy_completed', 'cancelled']);
const STEPS = new Set(['idle', 'open_chat', 'attach', 'location', 'confirm', 'share', 'verify', 'complete', 'cancelled', 'send_location']);
const TERRITORIES = {
  'AR-C': 'Ciudad Autónoma de Buenos Aires', 'AR-B': 'Provincia de Buenos Aires', 'AR-X': 'Córdoba',
  'AR-S': 'Santa Fe', 'AR-M': 'Mendoza', 'AR-T': 'Tucumán', 'AR-A': 'Salta', 'AR-E': 'Entre Ríos',
  'AR-H': 'Chaco', 'AR-U': 'Chubut', 'AR-K': 'Catamarca', 'AR-W': 'Corrientes', 'AR-P': 'Formosa',
  'AR-Y': 'Jujuy', 'AR-L': 'La Pampa', 'AR-F': 'La Rioja', 'AR-N': 'Misiones', 'AR-Q': 'Neuquén',
  'AR-R': 'Río Negro', 'AR-J': 'San Juan', 'AR-D': 'San Luis', 'AR-Z': 'Santa Cruz',
  'AR-G': 'Santiago del Estero', 'AR-V': 'Tierra del Fuego, Antártida e Islas del Atlántico Sur',
};
const hash = (text) => createHash('sha256').update(text).digest('hex');
const secret = () => randomBytes(32).toString('base64url');
const same = (left, right) => typeof left === 'string' && typeof right === 'string' && timingSafeEqual(Buffer.from(hash(left)), Buffer.from(hash(right)));
const publicGroup = (group) => ({ id: group.id, name: group.name });
const APK_PATHS = [resolve(API_ROOT, '../../artifacts/nieto-debug.apk'), resolve(API_ROOT, '../android/app/build/outputs/apk/debug/app-debug.apk')];
async function apkFile() {
  for (const path of APK_PATHS) { try { const info = await stat(path); if (info.isFile() && info.size > 0) return { path, size: info.size }; } catch { /* build may not exist */ } }
  return null;
}

class ApiError extends Error {
  constructor(status, code, message) { super(message); this.status = status; this.code = code; }
}
const required = (test, status, code, message) => { if (!test) throw new ApiError(status, code, message); };
function text(value, key, max = 100, optional = false) {
  if (optional && value === undefined) return undefined;
  required(typeof value === 'string' && value.trim().length > 0 && value.length <= max, 400, 'invalid_input', `${key} debe ser un texto de 1 a ${max} caracteres.`);
  return value.trim();
}
function keys(body, allowed) {
  required(body && typeof body === 'object' && !Array.isArray(body), 400, 'invalid_input', 'Se esperaba un objeto JSON.');
  required(Object.keys(body).every((key) => allowed.includes(key)), 400, 'unexpected_field', 'El pedido contiene campos no permitidos.');
}
function territory(value = 'AR-C') {
  required(Object.hasOwn(TERRITORIES, value), 400, 'unsupported_territory', 'Elegí una provincia argentina o Ciudad Autónoma de Buenos Aires con su código ISO 3166-2.');
  return value;
}
function sessionsFor(state, userId) { return state.sessions.filter((s) => s.userId === userId); }
function metricsFor(sessions) {
  return { completedFlows: sessions.filter((s) => s.state === 'completed').length,
    autonomyChecks: sessions.filter((s) => s.autonomyCompleted).length,
    accompaniedSeconds: sessions.reduce((sum, s) => sum + s.durationSeconds, 0) };
}
function inviteFor(state, groupId, now) {
  const invitationCode = randomBytes(5).toString('hex').toUpperCase();
  const expiresAt = new Date(now + 15 * 60 * 1000).toISOString();
  state.invitations = state.invitations.filter((i) => !i.usedAt && Date.parse(i.expiresAt) > now);
  state.invitations.push({ id: randomUUID(), groupId, codeHash: hash(invitationCode), expiresAt });
  return { invitationCode, expiresAt };
}
function audit(state, action, actorId, now) {
  state.audit.push({ id: randomUUID(), action, actorId, at: new Date(now).toISOString() });
  if (state.audit.length > 5000) state.audit.splice(0, state.audit.length - 5000);
}
function validateVoice(value) {
  required(typeof value === 'string' && value.length <= 175_000 && /^[A-Za-z0-9+/]+={0,2}$/.test(value), 400, 'invalid_voice', 'Grabá un aviso de hasta 30 segundos.');
  const bytes = Buffer.from(value, 'base64');
  required(bytes.length >= 100 && bytes.length <= 131_072 && bytes.toString('ascii', 4, 8) === 'ftyp' && bytes.toString('base64') === value, 400, 'invalid_voice', 'El aviso debe tener formato de audio M4A válido.');
}

async function bodyOf(req, maxBytes) {
  if (!['POST', 'PATCH'].includes(req.method)) return {};
  required((req.headers['content-type'] ?? '').split(';')[0].trim() === 'application/json', 415, 'content_type', 'Usá Content-Type: application/json.');
  required(Number(req.headers['content-length'] ?? 0) <= maxBytes, 413, 'body_too_large', 'El pedido excede el tamaño permitido.');
  let size = 0; const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    required(size <= maxBytes, 413, 'body_too_large', 'El pedido excede el tamaño permitido.'); chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); }
  catch { throw new ApiError(400, 'invalid_json', 'JSON inválido.'); }
}

export function createApp({
  dataFile = process.env.DATA_FILE ? resolve(process.env.DATA_FILE) : resolve(API_ROOT, 'data/nieto.json'),
  store = new JsonStore(dataFile), openarg = new OpenArgClient({ timeoutMs: Number(process.env.OPENARG_TIMEOUT_MS) || 25_000 }),
  institutionToken = process.env.INSTITUTION_TOKEN ?? '', minimumCohort = Number(process.env.MINIMUM_COHORT) || 5,
  corsOrigins = (process.env.CORS_ORIGINS ?? 'http://localhost:5173,http://127.0.0.1:5173').split(',').map((s) => s.trim()),
  now = Date.now, maxBodyBytes = 16_384, rateLimit = 180, rateWindowMs = 60_000, accountOptions = {}, assistantOptions = { generateReply },
} = {}) {
  minimumCohort = Math.max(5, Math.trunc(minimumCohort));
  const rates = new Map();
  const accounts = createAccounts({ store, now, ...accountOptions });
  const assistant = createAssistant({ now, ...assistantOptions });
  const dashboardContext = createDashboardContext(openarg);
  const handler = async (req, res) => {
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store'); res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer'); res.setHeader('Vary', 'Origin');
    const respond = (status, data) => { res.writeHead(status); res.end(JSON.stringify(data)); };
    try {
      const origin = req.headers.origin;
      if (origin) {
        required(corsOrigins.includes(origin), 403, 'origin_not_allowed', 'Origen no permitido.');
        res.setHeader('Access-Control-Allow-Origin', origin);
        res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
        res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PATCH, DELETE, OPTIONS');
      }
      if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && ['/downloads/nieto-debug.apk', '/api/downloads/nieto-debug.apk'].includes(url.pathname)) {
        const apk = await apkFile(); required(apk, 404, 'apk_not_built', 'El APK todavía no está compilado.');
        res.setHeader('Content-Type', 'application/vnd.android.package-archive'); res.setHeader('Content-Length', apk.size);
        res.setHeader('Content-Disposition', 'attachment; filename="nieto-debug.apk"');
        createReadStream(apk.path).on('error', () => res.destroy()).pipe(res); return;
      }
      required(url.pathname.startsWith('/api/'), 404, 'not_found', 'Ruta inexistente.');
      const route = url.pathname.slice(4).replace(/\/$/, '') || '/';
      const token = /^Bearer ([A-Za-z0-9_\-.]+)$/i.exec(req.headers.authorization ?? '')?.[1] ?? '';
      const current = now();
      // Do not trust X-Forwarded-For without a configured reverse proxy.
      if (rates.size > 5000) for (const [key, value] of rates) if (value.resetAt <= current) rates.delete(key);
      for (const rateKey of [`ip:${req.socket.remoteAddress}`, ...(token ? [`token:${hash(token)}`] : [])]) {
        let rate = rates.get(rateKey);
        if (!rate || rate.resetAt <= current) { rate = { count: 0, resetAt: current + rateWindowMs }; rates.set(rateKey, rate); }
        required(++rate.count <= rateLimit, 429, 'rate_limited', 'Demasiados pedidos. Esperá un minuto.');
      }
      const body = await bodyOf(req, ['/family/messages', '/demo/family/actions'].includes(route) ? 196_608 : maxBodyBytes);
      const method = req.method;
      const accountResponse = await accounts.handle({ route, method, body, bearer: token, remoteAddress: req.socket.remoteAddress });
      if (accountResponse) { respond(...accountResponse); return; }
      const account = token ? await accounts.principal(token) : null;

      if (route.startsWith('/demo/')) {
        required(account?.demo === true, 403, 'demo_only', 'Este escenario requiere una cuenta de demostración.');
        if (method === 'GET' && route === '/demo/institution/dashboard') {
          required(account.role === 'institution', 403, 'wrong_role', 'Ingresá al espacio institucional.');
          const territoryId = url.searchParams.get('territoryId') ?? 'AR'; const months = Number(url.searchParams.get('months') ?? 6); const month = url.searchParams.get('month');
          required(['AR', ...DEMO_TERRITORIES.map(t => t.id)].includes(territoryId) && [1, 3, 6].includes(months) && (!month || /^\d{4}-\d{2}$/.test(month)), 400, 'invalid_filter', 'Elegí una provincia y un período válido.');
          let dashboard;
          try { dashboard = demoInstitution({ now: current, territoryId, months, month }); } catch { throw new ApiError(400, 'invalid_filter', 'El mes no pertenece a los últimos seis meses.'); }
          const publicContext = crossPublicContext(dashboard, await dashboardContext());
          respond(200, { ...dashboard, territories: publicContext.territories, publicContext }); return;
        }
        if (method === 'GET' && route === '/demo/family/dashboard') {
          required(account.role === 'family' && account.groupId, 403, 'wrong_role', 'Ingresá al grupo familiar.');
          const s = await store.read(); const actions = (s.demoFamilyActions ?? []).filter(a => a.groupId === account.groupId);
          const dashboard = demoFamily(current);
          dashboard.group.id = account.groupId;
          dashboard.alerts = dashboard.alerts.map(a => ({ ...a, resolvedAt: actions.find(x => x.type === 'resolve' && x.memberId === a.memberId)?.createdAt ?? null }));
          respond(200, { ...dashboard, messages: actions.filter(a => ['ping', 'voice', 'location_request'].includes(a.type)).slice(-20).reverse() }); return;
        }
        if (method === 'POST' && route === '/demo/family/actions') {
          required(account.role === 'family' && account.groupId, 403, 'wrong_role', 'Ingresá al grupo familiar.');
          keys(body, ['id', 'memberId', 'type', 'audioBase64']);
          required(['marta', 'ricardo', 'lucia'].includes(body.memberId) && ['ping', 'voice', 'resolve', 'location_request'].includes(body.type), 400, 'invalid_action', 'Elegí una persona y una acción válida.');
          required(typeof body.id === 'string' && /^[a-zA-Z0-9_-]{8,80}$/.test(body.id), 400, 'invalid_id', 'Identificador inválido.');
          if (body.type === 'voice') validateVoice(body.audioBase64);
          await store.update(s => {
            s.demoFamilyActions ??= []; s.demoFamilyActions = s.demoFamilyActions.filter(a => Date.parse(a.createdAt) > current - 86_400_000);
            if (!s.demoFamilyActions.some(a => a.id === body.id && a.groupId === account.groupId)) s.demoFamilyActions.push({ id: body.id, memberId: body.memberId, type: body.type, groupId: account.groupId, createdAt: new Date(current).toISOString(), ...(body.type === 'voice' ? { audioBase64: body.audioBase64 } : {}) });
          }); respond(200, { ok: true, simulated: true }); return;
        }
        throw new ApiError(404, 'not_found', 'Ruta inexistente.');
      }

      if (method === 'GET' && route === '/health') {
        const status = openarg.status();
        const apk = await apkFile();
        respond(200, { status: 'ok', version: '2.0.0', openarg: { configured: status.configured, connected: Boolean(status.lastSuccessAt), endpoint: status.endpoint },
          privacy: { minimumCohort, screenStorage: false, conversationStorage: false }, institutionConfigured: Boolean(institutionToken),
          android: { apkAvailable: Boolean(apk), downloadUrl: apk ? '/downloads/nieto-debug.apk' : null },
          assistant: { mode: assistant.modelConnected ? 'conversational' : 'local_conversation', generalConversation: assistant.modelConnected, flows: ['whatsapp-group', 'email-send', 'whatsapp-location'] }, storage: 'local_json_single_process' }); return;
      }
      if (method === 'POST' && route === '/devices') {
        keys(body, ['name', 'territoryId']);
        const name = text(body.name, 'name', 60); const territoryId = territory(body.territoryId);
        const deviceId = randomUUID(); const newToken = secret();
        await store.update((state) => { state.users.push({ id: deviceId, name, territoryId, tokenHash: hash(newToken), createdAt: new Date(current).toISOString() }); });
        respond(201, { deviceId, token: newToken }); return;
      }
      if (method === 'POST' && route === '/family/groups') {
        keys(body, ['name']); const name = text(body.name, 'name', 60); const caregiverToken = secret();
        const result = await store.update((state) => {
          const group = { id: randomUUID(), name, tokenHash: hash(caregiverToken), createdAt: new Date(current).toISOString() };
          state.groups.push(group); audit(state, 'group_created', group.id, current);
          return { group: publicGroup(group), caregiverToken, ...inviteFor(state, group.id, current) };
        }); respond(201, result); return;
      }

      const institutional = route.startsWith('/institution/') || route.startsWith('/openarg/');
      if (institutional) {
        required(institutionToken || account?.institutionApproved, 503, 'institution_not_configured', 'Configurá el acceso institucional autorizado en el backend.');
        required((institutionToken && same(token, institutionToken)) || account?.institutionApproved, 401, 'unauthorized', 'Acceso institucional inválido o ausente.');
      }

      if (method === 'GET' && route === '/openarg/status') {
        const status = await openarg.discover();
        respond(200, { configured: status.configured, connected: Boolean(status.serverInfo) && !status.lastError && status.configured, endpoint: status.endpoint,
          tools: status.tools.map(({ name, description }) => ({ name, description })), error: !status.configured ? { code: 'credentials_required', message: 'Configurá OPENARG_API_KEY en el backend.' } : status.lastError,
          transport: status.transport, lastSuccessAt: status.lastSuccessAt, serverInfo: status.serverInfo }); return;
      }
      if (method === 'GET' && ['/openarg/search', '/openarg/territory'].includes(route)) {
        const territoryId = territory(url.searchParams.get('territoryId') ?? undefined);
        const isTerritory = route.endsWith('territory'); const suppliedQuery = url.searchParams.get('q');
        if (isTerritory && suppliedQuery !== null) required(suppliedQuery.length <= 300, 400, 'invalid_input', 'q admite hasta 300 caracteres.');
        const query = isTerritory ? (suppliedQuery?.trim() ? text(suppliedQuery, 'q', 300) : `personas mayores población acceso internet ${TERRITORIES[territoryId]}`)
          : text(suppliedQuery, 'q', 500);
        if (!openarg.status().configured) {
          const base = { query, datasets: [], sources: [], warnings: ['OpenArg pendiente: configurá OPENARG_API_KEY en el backend. No se muestran datos de reemplazo.'], cached: false, fetchedAt: null, status: 'credentials_required' };
          respond(200, isTerritory ? { ...base, territoryId, indicators: [] } : base); return;
        }
        const scope = `Territorio: ${TERRITORIES[territoryId]}. ${query}`;
        const result = normalizeSearch(await openarg.call('buscar_datasets', { texto: scope, limite: 8 }), query);
        if (isTerritory) {
          const context = await getTerritoryIndicators(openarg, territoryId);
          const sources = [...result.sources, ...context.sources].filter((source, index, all) => all.findIndex((entry) => entry.url === source.url) === index);
          respond(200, { ...result, territoryId, indicators: context.indicators, sources,
            warnings: [...result.warnings, ...context.warnings], cached: result.cached && context.cached });
        }
        else respond(200, result); return;
      }
      if (method === 'POST' && route === '/openarg/ask') {
        keys(body, ['question', 'territoryId']); const question = text(body.question, 'question', 1600); const territoryId = territory(body.territoryId);
        const scopedQuestion = `Contexto territorial: ${TERRITORIES[territoryId]}. ${question} Indicá período de los datos, fuentes oficiales y límites de cobertura. No infieras indicadores de descripciones.`;
        respond(200, normalizeAnswer(await openarg.call('consultar_datos_publicos', { pregunta: scopedQuestion }))); return;
      }
      if (method === 'GET' && ['/institution/analytics', '/institution/territories'].includes(route)) {
        const territoryId = url.searchParams.get('territoryId') === 'AR' ? 'AR' : territory(url.searchParams.get('territoryId') ?? undefined);
        const days = Number(url.searchParams.get('days') ?? '30');
        required([7, 30, 90, 180].includes(days), 400, 'invalid_period', 'Elegí un período de 7, 30, 90 o 180 días.');
        const state = await store.read();
        if (route.endsWith('/analytics')) {
          const dashboard = institutionDashboard(state, territoryId, days, minimumCohort, current);
          const m = dashboard.metrics; const linkCategory = dashboard.safety?.categories.find(c => c.kind === 'suspicious_link');
          const territoryStats = DEMO_TERRITORIES.map(t => { const data = summarize(state, t.id, periodFor(days, current), minimumCohort); return { ...t, suppressed: data.suppressed, activeUsers: data.participants, unsafeLinks: data.safety?.categories.find(c => c.kind === 'suspicious_link')?.count ?? null }; });
          if (territoryStats.some(t => t.suppressed)) territoryStats.forEach(t => { t.activeUsers = null; t.unsafeLinks = null; });
          const model = { ...dashboard, origin: 'tino_consented', territories: territoryStats, metrics: m ? { ...m, activeUsers: dashboard.participants, requests: m.sessions, independent: m.autonomyChecks, completed: m.completedFlows,
            screenMinutesPerDay: dashboard.usage?.screenMinutesPerDay ?? null, tinoMinutesPerDay: dashboard.usage?.tinoMinutesPerDay ?? null, unsafeLinks: linkCategory?.count ?? null } : null,
            flows: dashboard.flows.map(f => ({ ...f, count: f.started })), trend: dashboard.trend.map(row => ({ ...row, month: row.date, requests: row.sessions, autonomyRate: row.completed ? Math.round(row.autonomyChecks / row.completed * 1000) / 10 : null })) };
          const publicContext = crossPublicContext(model, await dashboardContext());
          respond(200, { ...model, territories: publicContext.territories, publicContext });
        }
        else respond(200, { period: periodFor(days, current), minimumCohort,
          territories: Object.entries(TERRITORIES).map(([id, name]) => {
            const result = summarize(state, id, periodFor(days, current), minimumCohort);
            return { territoryId: id, name, suppressed: result.suppressed, participants: result.participants,
              completedFlows: result.metrics?.completedFlows ?? null, autonomyRate: result.metrics?.autonomyRate ?? null };
          }) });
        return;
      }
      if (method === 'GET' && route === '/institution/programs') {
        const territoryId = territory(url.searchParams.get('territoryId') ?? undefined);
        const state = await store.read();
        respond(200, { programs: (state.programs ?? []).filter((p) => p.territoryId === territoryId)
          .map((program) => ({ ...program, evaluation: compareProgram(state, program, minimumCohort, current) })) }); return;
      }
      if (method === 'POST' && route === '/institution/programs') {
        keys(body, ['name', 'territoryId', 'startDate', 'endDate', 'goal']);
        const name = text(body.name, 'name', 100); const territoryId = territory(body.territoryId);
        const dates = [body.startDate, body.endDate];
        required(dates.every((d) => typeof d === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(d) && Number.isFinite(Date.parse(d)) && new Date(d).toISOString().slice(0, 10) === d), 400, 'invalid_dates', 'Usá fechas válidas con formato AAAA-MM-DD.');
        required(body.endDate >= body.startDate && Date.parse(body.endDate) - Date.parse(body.startDate) <= 366 * 86_400_000, 400, 'invalid_dates', 'El programa debe durar entre 1 y 367 días.');
        required(Number.isFinite(body.goal) && body.goal >= 0 && body.goal <= 100, 400, 'invalid_goal', 'La meta de autonomía debe estar entre 0 y 100.');
        const program = { id: randomUUID(), name, territoryId, startDate: body.startDate, endDate: body.endDate,
          goal: body.goal, status: 'planned', createdAt: new Date(current).toISOString() };
        await store.update((s) => { (s.programs ??= []).push(program); audit(s, 'program_created', 'institution', current); });
        respond(201, { program }); return;
      }
      const programMatch = /^\/institution\/programs\/([a-f0-9-]+)$/.exec(route);
      if (method === 'PATCH' && programMatch) {
        keys(body, ['status']); required(['planned', 'active', 'completed'].includes(body.status), 400, 'invalid_status', 'Estado de programa inválido.');
        await store.update((s) => {
          const program = (s.programs ?? []).find((p) => p.id === programMatch[1]);
          required(program, 404, 'program_not_found', 'Programa inexistente.'); program.status = body.status;
          audit(s, 'program_status_changed', 'institution', current);
        }); respond(200, { ok: true }); return;
      }
      if (method === 'GET' && route === '/institution/overview') {
        const territoryId = territory(url.searchParams.get('territoryId') ?? undefined);
        const state = await store.read();
        const users = state.users.filter((u) => u.territoryId === territoryId);
        const suppressed = users.length < minimumCohort;
        if (suppressed) { respond(200, { territoryId, minimumCohort, suppressed, participants: null, metrics: null, flows: [], updatedAt: new Date(current).toISOString() }); return; }
        const ids = new Set(users.map((u) => u.id)); const sessions = state.sessions.filter((s) => ids.has(s.userId));
        const metrics = metricsFor(sessions);
        respond(200, { territoryId, minimumCohort, suppressed, participants: users.length,
          metrics: { completedFlows: metrics.completedFlows, autonomyChecks: metrics.autonomyChecks, accompaniedMinutes: Math.round(metrics.accompaniedSeconds / 60), helpRequests: state.alerts.filter((a) => ids.has(a.userId)).length },
          flows: [...new Set(sessions.map(session => session.flowId))].map(flowId => ({ flowId, started: sessions.filter(session => session.flowId === flowId).length, completed: sessions.filter(session => session.flowId === flowId && session.state === 'completed').length })), updatedAt: new Date(current).toISOString() }); return;
      }

      const state = await store.read();
      const user = token ? state.users.find((u) => same(hash(token), u.tokenHash) || (account?.role === 'user' && account.deviceId === u.id)) : undefined;
      const group = token ? state.groups.find((g) => same(hash(token), g.tokenHash) || (account?.role === 'family' && account.groupId === g.id)) : undefined;
      const needUser = () => required(user, 401, 'unauthorized', 'Token de dispositivo inválido o ausente.');
      const needGroup = () => required(group, 401, 'unauthorized', 'Token familiar inválido o ausente.');
      if (method === 'POST' && route === '/family/messages') {
        needGroup(); keys(body, ['id', 'memberId', 'type', 'audioBase64']);
        required(typeof body.id === 'string' && /^[a-zA-Z0-9_-]{8,80}$/.test(body.id), 400, 'invalid_id', 'Identificador inválido.');
        required(['ping', 'voice', 'location_request'].includes(body.type), 400, 'invalid_type', 'Mensaje familiar inválido.');
        required(state.relationships.some(r => r.groupId === group.id && r.userId === body.memberId && r.permissions.help), 403, 'help_not_consented', 'Esta persona no autorizó el acompañamiento familiar.');
        if (body.type === 'voice') validateVoice(body.audioBase64);
        await store.update(s => {
          s.familyMessages ??= []; s.familyMessages = s.familyMessages.filter(m => Date.parse(m.expiresAt) > current);
          if (!s.familyMessages.some(m => m.id === body.id && m.groupId === group.id)) s.familyMessages.push({ id: body.id, memberId: body.memberId, groupId: group.id, type: body.type,
            author: account?.profile?.name ?? group.name, createdAt: new Date(current).toISOString(), expiresAt: new Date(current + 86_400_000).toISOString(), ...(body.type === 'voice' ? { audioBase64: body.audioBase64 } : {}) });
        }); respond(201, { ok: true }); return;
      }
      if (method === 'GET' && route === '/family/messages') {
        needUser(); const relationship = state.relationships.find(r => r.userId === user.id && r.permissions.help);
        if ((state.familyMessages ?? []).some(m => Date.parse(m.expiresAt) <= current)) await store.update(s => { s.familyMessages = (s.familyMessages ?? []).filter(m => Date.parse(m.expiresAt) > current); });
        const messages = relationship ? (state.familyMessages ?? []).filter(m => m.memberId === user.id && m.groupId === relationship.groupId && Date.parse(m.expiresAt) > current).slice(-5).reverse() : [];
        respond(200, { messages }); return;
      }
      if (method === 'POST' && route === '/usage/batch') {
        needUser(); keys(body, ['consent', 'intervals']);
        required(body.consent === true, 400, 'consent_required', 'El envío de métricas de uso requiere permiso específico en el dispositivo.');
        required(Array.isArray(body.intervals) && body.intervals.length > 0 && body.intervals.length <= 50, 400, 'invalid_intervals', 'Enviá de 1 a 50 intervalos.');
        const entries = body.intervals.map((item) => {
          keys(item, ['id', 'category', 'startedAt', 'endedAt', 'accompaniedSeconds']);
          required(typeof item.id === 'string' && /^[a-zA-Z0-9_-]{8,80}$/.test(item.id), 400, 'invalid_id', 'Identificador de intervalo inválido.');
          required(['communication', 'services', 'navigation', 'learning', 'other'].includes(item.category), 400, 'invalid_category', 'Categoría de uso inválida.');
          const start = Date.parse(item.startedAt); const end = Date.parse(item.endedAt);
          required(typeof item.startedAt === 'string' && typeof item.endedAt === 'string' && Number.isFinite(start) && Number.isFinite(end) && start < end && end <= current && start >= current - 31 * 86_400_000 && end - start <= 86_400_000, 400, 'invalid_interval', 'El intervalo debe haber terminado y pertenecer a los últimos 31 días, con duración máxima de 24 horas.');
          const durationSeconds = (end - start) / 1000;
          required(Number.isFinite(item.accompaniedSeconds) && item.accompaniedSeconds >= 0 && item.accompaniedSeconds <= durationSeconds, 400, 'invalid_duration', 'El tiempo acompañado debe estar dentro del intervalo.');
          return { id: item.id, userId: user.id, category: item.category, startedAt: new Date(start).toISOString(), endedAt: new Date(end).toISOString(), durationSeconds, accompaniedSeconds: item.accompaniedSeconds };
        });
        await store.update((s) => {
          s.usage ??= [];
          for (const entry of entries) {
            const existing = s.usage.find((e) => e.userId === user.id && e.id === entry.id);
            if (existing) { required(JSON.stringify(existing) === JSON.stringify(entry), 409, 'interval_conflict', 'El identificador ya corresponde a otro intervalo.'); continue; }
            required(!s.usage.some((e) => e.userId === user.id && Date.parse(e.startedAt) < Date.parse(entry.endedAt) && Date.parse(e.endedAt) > Date.parse(entry.startedAt)), 409, 'overlapping_intervals', 'Los intervalos de uso no pueden superponerse.');
            s.usage.push(entry);
          }
          audit(s, 'usage_consent_confirmed', user.id, current);
        }); respond(200, { ok: true }); return;
      }
      if (method === 'POST' && route === '/safety/events') {
        needUser(); keys(body, ['id', 'kind', 'category']);
        required(typeof body.id === 'string' && /^[a-zA-Z0-9_-]{8,80}$/.test(body.id), 400, 'invalid_id', 'Identificador de evento inválido.');
        required(['suspicious_link', 'permission_request', 'sensitive_action'].includes(body.kind), 400, 'invalid_kind', 'Tipo de señal inválido.');
        required(['messaging', 'browser', 'banking', 'other'].includes(body.category), 400, 'invalid_category', 'Categoría inválida.');
        await store.update((s) => {
          s.safety ??= [];
          const existing = s.safety.find((e) => e.userId === user.id && e.id === body.id);
          if (existing) { required(existing.kind === body.kind && existing.category === body.category, 409, 'event_conflict', 'El evento ya existe con otros valores.'); return; }
          s.safety.push({ id: body.id, userId: user.id, kind: body.kind, category: body.category, createdAt: new Date(current).toISOString(), resolvedAt: null });
        }); respond(200, { ok: true }); return;
      }
      const safetyMatch = /^\/safety\/events\/([a-zA-Z0-9_-]{8,80})$/.exec(route);
      if (method === 'PATCH' && safetyMatch) {
        needUser(); keys(body, ['resolved']); required(body.resolved === true, 400, 'invalid_resolution', 'La resolución requiere confirmación.');
        await store.update((s) => { const entry = (s.safety ?? []).find((e) => e.id === safetyMatch[1] && e.userId === user.id);
          required(entry, 404, 'event_not_found', 'Evento inexistente.'); entry.resolvedAt ??= new Date(current).toISOString(); });
        respond(200, { ok: true }); return;
      }
      if (method === 'PATCH' && route === '/devices/me') {
        needUser(); keys(body, ['name', 'territoryId']);
        const name = text(body.name, 'name', 60, true); const territoryId = body.territoryId === undefined ? undefined : territory(body.territoryId);
        await store.update((s) => { const u = s.users.find((u) => u.id === user.id); if (name) u.name = name; if (territoryId) u.territoryId = territoryId; });
        respond(200, { ok: true }); return;
      }
      if (method === 'POST' && route === '/assistant/sessions') {
        needUser(); keys(body, ['flowId']); required(body.flowId === 'whatsapp-location' || Object.hasOwn(TASK_FLOWS, body.flowId), 400, 'unsupported_flow', 'Flujo no disponible.');
        const session = { id: randomUUID(), flowId: body.flowId, userId: user.id, state: 'created', createdAt: new Date(current).toISOString(), durationSeconds: 0, completedSteps: [], autonomyCompleted: false, eventCounts: {} };
        await store.update((s) => { s.sessions.push(session); });
        respond(201, { id: session.id, flowId: session.flowId, createdAt: session.createdAt, ...(TASK_FLOWS[session.flowId] ? { flow: TASK_FLOWS[session.flowId] } : {}) }); return;
      }
      if (method === 'GET' && route === '/assistant/flows') { needUser(); respond(200, { flows: Object.values(TASK_FLOWS) }); return; }
      if (method === 'POST' && route === '/assistant/message') {
        needUser(); keys(body, ['sessionId', 'text', 'conversationId', 'history']); const message = text(body.text, 'text', 1000);
        if (body.sessionId) required(state.sessions.some((s) => s.id === body.sessionId && s.userId === user.id), 404, 'session_not_found', 'Sesión inexistente.');
        const conversationId = body.conversationId ?? 'default';
        required(typeof conversationId === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(conversationId), 400, 'invalid_conversation', 'Conversación inválida.');
        const history = body.history ?? [];
        required(Array.isArray(history) && history.length <= 12 && history.every(line => line && ['user','assistant'].includes(line.role) && typeof line.content === 'string' && line.content.length <= 1000 && Object.keys(line).every(key => ['role','content'].includes(key))), 400, 'invalid_history', 'Historial inválido.');
        const activeSession = body.sessionId ? state.sessions.find(session => session.id === body.sessionId && session.userId === user.id && ['created','active'].includes(session.state)) : null;
        const activeFlow = TASK_FLOWS[activeSession?.flowId];
        const task = activeFlow ? { flowId: activeFlow.id, title: activeFlow.title, instruction: activeFlow.steps[Math.min(activeSession.completedSteps.length, activeFlow.steps.length - 1)].instruction } : null;
        const result = await assistant.reply({ key: hash(token) + ':' + conversationId, text: message, history, task });
        respond(200, result); return;
      }
      const eventMatch = /^\/assistant\/sessions\/([a-f0-9-]+)\/events$/.exec(route);
      if (method === 'POST' && eventMatch) {
        needUser(); keys(body, ['type', 'stepId', 'durationSeconds', 'verification']);
        if (body.verification !== undefined) required(body.type === 'flow_completed' && body.verification === 'user_confirmed', 400, 'invalid_verification', 'Confirmación inválida.');
        required(TYPES.has(body.type), 400, 'invalid_event', 'Tipo de evento no permitido.');
        const eventSession = state.sessions.find(session => session.id === eventMatch[1] && session.userId === user.id);
        required(eventSession, 404, 'session_not_found', 'Sesión inexistente.');
        const taskDefinition = TASK_FLOWS[eventSession.flowId];
        if (body.stepId !== undefined) required(taskDefinition ? taskDefinition.steps.some(step => step.id === body.stepId) : STEPS.has(body.stepId), 400, 'invalid_step', 'stepId no pertenece al flujo.');
        if (['step_shown', 'step_completed', 'step_repeated'].includes(body.type)) required(body.stepId, 400, 'missing_step', 'Este evento requiere stepId.');
        if (body.durationSeconds !== undefined) required(Number.isFinite(body.durationSeconds) && body.durationSeconds >= 0 && body.durationSeconds <= 14_400, 400, 'invalid_duration', 'Duración fuera del intervalo permitido (0–14400).');
        await store.update((s) => {
          const session = s.sessions.find((entry) => entry.id === eventMatch[1] && entry.userId === user.id);
          required(session, 404, 'session_not_found', 'Sesión inexistente.');
          required(session.state !== 'cancelled', 409, 'session_closed', 'La sesión fue cancelada.');
          if (body.type === 'flow_completed' && TASK_FLOWS[session.flowId]) {
            required(body.verification === 'user_confirmed', 400, 'confirmation_required', 'Confirmá el resultado antes de completar.');
            required(TASK_FLOWS[session.flowId].steps.every(step => session.completedSteps.includes(step.id)), 409, 'flow_incomplete', 'Completá los pasos de la guía antes de confirmar el resultado.');
          }
          if (body.type === 'autonomy_completed') required(session.state === 'completed', 409, 'flow_incomplete', 'Completá el flujo antes de registrar autonomía.');
          session.eventCounts[body.type] = (session.eventCounts[body.type] ?? 0) + 1;
          if (body.type === 'flow_started' && session.state !== 'completed') session.state = 'active';
          if (body.type === 'step_completed' && !session.completedSteps.includes(body.stepId)) session.completedSteps.push(body.stepId);
          if (body.type === 'flow_completed') { session.state = 'completed'; session.completedAt ??= new Date(current).toISOString(); session.verification = body.verification ?? 'screen_observed'; }
          if (body.type === 'autonomy_completed') session.autonomyCompleted = true;
          if (body.type === 'cancelled' && session.state !== 'completed') session.state = 'cancelled';
          if (body.durationSeconds !== undefined) session.durationSeconds = Math.max(session.durationSeconds, Math.round(body.durationSeconds));
        }); respond(200, { ok: true }); return;
      }
      if (method === 'GET' && route === '/assistant/history') {
        needUser(); respond(200, { sessions: sessionsFor(state, user.id).map(({ id, flowId, state, createdAt, completedAt, durationSeconds, completedSteps, autonomyCompleted, verification }) =>
          ({ id, flowId, state, createdAt, completedAt, durationSeconds, completedSteps, autonomyCompleted, verification })).reverse() }); return;
      }
      if (method === 'POST' && route === '/family/link') {
        needUser(); keys(body, ['invitationCode', 'permissions']); const code = text(body.invitationCode, 'invitationCode', 32).toUpperCase();
        keys(body.permissions, ['progress', 'help']);
        required(typeof body.permissions.progress === 'boolean' && typeof body.permissions.help === 'boolean', 400, 'invalid_permissions', 'Elegí permisos explícitos de progreso y ayuda.');
        const permissions = { progress: body.permissions.progress, help: body.permissions.help };
        const result = await store.update((s) => {
          required(!s.relationships.some((r) => r.userId === user.id), 409, 'already_linked', 'El dispositivo ya tiene un grupo. Revocá la vinculación anterior.');
          const invitation = s.invitations.find((i) => same(hash(code), i.codeHash) && !i.usedAt && Date.parse(i.expiresAt) > current);
          required(invitation, 400, 'invalid_invitation', 'El código no existe, ya se usó o venció.');
          invitation.usedAt = new Date(current).toISOString();
          s.relationships.push({ id: randomUUID(), userId: user.id, groupId: invitation.groupId, permissions, createdAt: new Date(current).toISOString() });
          s.consents.push({ id: randomUUID(), userId: user.id, groupId: invitation.groupId, permissions, acceptedAt: new Date(current).toISOString() });
          audit(s, 'family_link_accepted', user.id, current);
          return { group: publicGroup(s.groups.find((g) => g.id === invitation.groupId)), permissions };
        }); respond(200, result); return;
      }
      if (method === 'GET' && route === '/family/me') {
        needUser(); const relationship = state.relationships.find((r) => r.userId === user.id);
        respond(200, { group: relationship ? publicGroup(state.groups.find((g) => g.id === relationship.groupId)) : null, permissions: relationship?.permissions ?? { progress: false, help: false } }); return;
      }
      if (method === 'DELETE' && route === '/family/link') {
        needUser(); await store.update((s) => {
          s.relationships = s.relationships.filter((r) => r.userId !== user.id);
          for (const consent of s.consents) if (consent.userId === user.id && !consent.revokedAt) consent.revokedAt = new Date(current).toISOString();
          for (const alert of s.alerts) if (alert.userId === user.id) alert.revokedAt = new Date(current).toISOString();
          audit(s, 'family_link_revoked', user.id, current);
        }); respond(200, { ok: true }); return;
      }
      if (method === 'POST' && route === '/family/help') {
        needUser(); keys(body, []);
        const alertId = await store.update((s) => {
          const relation = s.relationships.find((r) => r.userId === user.id && r.permissions.help);
          required(relation, 403, 'help_not_consented', 'Vinculá un familiar y autorizá las solicitudes de ayuda.');
          const pending = s.alerts.find((a) => a.userId === user.id && a.groupId === relation.groupId && !a.resolvedAt && !a.revokedAt);
          if (pending) return pending.id;
          const alert = { id: randomUUID(), userId: user.id, groupId: relation.groupId, type: 'help_requested', message: 'Solicitó acompañamiento desde TINO.', createdAt: new Date(current).toISOString() };
          s.alerts.push(alert); return alert.id;
        }); respond(200, { ok: true, alertId }); return;
      }
      if (method === 'GET' && route === '/family/summary') {
        needGroup(); const relations = state.relationships.filter((r) => r.groupId === group.id);
        const members = relations.map((r) => {
          const u = state.users.find(u => u.id === r.userId); const sessions = sessionsFor(state, u.id);
          const dates = Array.from({ length: 7 }, (_, i) => new Date(current - (6 - i) * 86_400_000).toISOString().slice(0, 10));
          const usage = (state.usage ?? []).filter(e => e.userId === u.id);
          return { id: u.id, name: u.name, territoryId: u.territoryId, permissions: r.permissions, location: null,
            ...(r.permissions.progress ? { ...metricsFor(sessions), tinoMinutes: Math.round(sessions.filter(s => s.createdAt.slice(0, 10) === dates[6]).reduce((n, s) => n + s.durationSeconds, 0) / 60),
              weeklyTino: dates.map(d => Math.round(sessions.filter(s => s.createdAt.slice(0, 10) === d).reduce((n, s) => n + s.durationSeconds, 0) / 60)),
              activities: sessions.slice(-10).reverse().map(s => ({ title: s.state === 'completed' ? 'Completó una tarea' : 'Practicó con TINO', detail: TASK_FLOWS[s.flowId]?.title ?? 'Compartir ubicación', at: s.createdAt })),
              learned: sessions.filter(s => s.autonomyCompleted).map(s => TASK_FLOWS[s.flowId]?.title ?? 'Compartir ubicación').at(-1) ?? null } : {}),
            ...(r.permissions.usage ? { screenMinutes: Math.round(usage.filter(e => e.startedAt.slice(0, 10) === dates[6]).reduce((n, e) => n + e.durationSeconds, 0) / 60), weeklyScreen: dates.map(d => Math.round(usage.filter(e => e.startedAt.slice(0, 10) === d).reduce((n, e) => n + e.durationSeconds, 0) / 60)) } : {}) };
        });
        const helpIds = new Set(relations.filter((r) => r.permissions.help).map((r) => r.userId));
        const alerts = state.alerts.filter((a) => a.groupId === group.id && !a.revokedAt && helpIds.has(a.userId))
          .map(({ id, type, message, createdAt, resolvedAt, userId }) => ({ id, type, message, createdAt, resolvedAt,
            memberId: userId, requesterName: state.users.find((u) => u.id === userId)?.name }));
        const joinRequests = account?.id === group.adminAccountId ? (state.familyJoinRequests ?? []).filter(r => r.groupId === group.id && !r.resolvedAt)
          .map(r => { const applicant = state.accounts.find(a => a.id === r.accountId); return { id: r.id, name: applicant?.profile?.name ?? 'Persona de apoyo', email: applicant?.email, createdAt: r.createdAt }; }) : [];
        const messages = (state.familyMessages ?? []).filter(m => m.groupId === group.id && Date.parse(m.expiresAt) > current && helpIds.has(m.memberId)).slice(-5).reverse();
        respond(200, { origin: 'tino_consented', group: publicGroup(group), members, alerts, joinRequests, messages }); return;
      }
      const resolveMatch = /^\/family\/alerts\/([a-f0-9-]+)\/resolve$/.exec(route);
      if (method === 'POST' && resolveMatch) {
        needGroup(); keys(body, []);
        await store.update((s) => {
          const alert = s.alerts.find((a) => a.id === resolveMatch[1] && a.groupId === group.id && !a.revokedAt);
          required(alert, 404, 'alert_not_found', 'Alerta inexistente.'); alert.resolvedAt ??= new Date(current).toISOString();
        }); respond(200, { ok: true }); return;
      }
      if (method === 'POST' && route === '/family/invitations') {
        needGroup(); keys(body, []); respond(201, await store.update((s) => inviteFor(s, group.id, current))); return;
      }
      throw new ApiError(404, 'not_found', 'Ruta inexistente.');
    } catch (error) {
      if (res.headersSent) { res.end(); return; }
      const known = error instanceof ApiError || error instanceof OpenArgError || error instanceof AccountError;
      if (error.retryAfter !== null && error.retryAfter !== undefined) res.setHeader('Retry-After', String(error.retryAfter));
      respond(known ? error.status : 500, { error: { code: known ? error.code : 'internal_error', message: known ? error.message : 'No se pudo completar el pedido.' } });
    }
  };
  handler.store = store; handler.openarg = openarg; return handler;
}

export function createServer(options) { return http.createServer(createApp(options)); }

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const port = Number(process.env.API_PORT ?? process.env.PORT) || 8787; const host = process.env.HOST ?? '0.0.0.0';
  const app = createApp(); await app.store.init(); const server = http.createServer(app);
  server.listen(port, host, () => console.log(`NIETO API 2.0 en http://${host}:${port} — OpenArg ${app.openarg.status().configured ? 'configurado, pendiente de consulta' : 'pendiente de OPENARG_API_KEY'}`));
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
}
