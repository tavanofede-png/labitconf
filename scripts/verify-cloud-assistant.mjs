import { readFile, writeFile, mkdir, lstat, realpath } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, sep } from 'node:path';
import WebSocket from 'ws';
import { validateTaskContext } from '../apps/api/src/task-context.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const secretDirectory = resolve(root, '.cache/secrets');
const operatorFile = resolve(secretDirectory, 'tino-production-operator.env');
const deviceFile = resolve(secretDirectory, 'tino-cloud-verification.json');
const MODEL = 'gpt-realtime-2.1-mini';
const suppliedUrl = process.argv.indexOf('--url');
const base = new URL(suppliedUrl < 0 ? 'https://tino-api-labitconf.vercel.app/' : process.argv[suppliedUrl + 1]);
const keepDevice = process.argv.includes('--keep-device');
const preflightOnly = process.argv.includes('--preflight');
const contextScenario = process.argv.includes('--context');
const greetingScenario = process.argv.includes('--greeting');
const started = Date.now();
let operator = '', device = null, enrollment = '', stage = 'configuration', socket, revoked = false;
const report = { ok: false, model: MODEL, providerTurns: 0, keysPrinted: false, tokensPrinted: false };
const safeFailure = code => Object.assign(new Error('Cloud verification failed.'), { code });
const requireCheck = (condition, code) => { if (!condition) throw safeFailure(code); };

async function request(path, { method = 'GET', body, token, expected = 200 } = {}) {
  const response = await fetch(new URL(path, base), { method, redirect: 'error', signal: AbortSignal.timeout(20_000),
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}) });
  requireCheck(response.status === expected, `http_${response.status}`);
  requireCheck(/application\/json/i.test(response.headers.get('content-type') ?? ''), 'unexpected_response_type');
  return response.json();
}

async function saveDevice() {
  await mkdir(secretDirectory, { recursive: true });
  const directory = await realpath(secretDirectory);
  requireCheck(directory.toLowerCase().startsWith((resolve(root) + sep).toLowerCase()), 'unsafe_credentials_directory');
  try { requireCheck(!(await lstat(deviceFile)).isSymbolicLink(), 'unsafe_credentials_file'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  await writeFile(deviceFile, JSON.stringify({ baseUrl: base.origin, deviceId: device.deviceId, token: device.token,
    createdAt: device.createdAt, verificationOnly: true, revoked }, null, 2), { mode: 0o600 });
}

async function rejectUnauthenticatedSocket() {
  const wsUrl = new URL('/api/assistant/realtime', base); wsUrl.protocol = 'wss:';
  await new Promise((resolveResult, reject) => {
    let finished = false;
    const client = new WebSocket(wsUrl, { handshakeTimeout: 10_000, perMessageDeflate: false });
    const finish = error => { if (finished) return; finished = true; clearTimeout(timer); client.terminate(); error ? reject(error) : resolveResult(); };
    const timer = setTimeout(() => finish(safeFailure('unauthorized_upgrade_timeout')), 12_000);
    client.once('unexpected-response', (_request, response) => { response.resume(); finish(response.statusCode === 401 ? null : safeFailure(`unauthorized_upgrade_${response.statusCode}`)); });
    client.once('open', () => finish(safeFailure('unauthorized_socket_opened')));
    client.on('error', () => { if (!finished) finish(safeFailure('unauthorized_transport_error')); });
  });
}

async function closeSocket() {
  if (!socket || socket.readyState === WebSocket.CLOSED) return;
  const closing = socket;
  await new Promise(resolve => {
    let timer;
    closing.once('close', () => { clearTimeout(timer); resolve(); });
    closing.close(); timer = setTimeout(() => { closing.terminate(); resolve(); }, 3000); timer.unref();
  });
}

async function verifyGreeting() {
  // Public synthetic fixture only: never record the person's microphone.
  const pcm16 = await readFile(resolve(root, 'apps/android/app/src/androidTest/assets/wake-positive.pcm'));
  const count = pcm16.length / 2;
  requireCheck(Number.isInteger(count) && count > 0 && count <= 160_000, 'invalid_synthetic_audio');
  const audio = Buffer.alloc(Math.ceil(count * 1.5) * 2);
  for (let index = 0; index < audio.length / 2; index++) {
    const position = index * 2 / 3, left = Math.min(Math.floor(position), count - 1), right = Math.min(left + 1, count - 1);
    const fraction = position - left;
    audio.writeInt16LE(Math.round(pcm16.readInt16LE(left * 2) * (1 - fraction) + pcm16.readInt16LE(right * 2) * fraction), index * 2);
  }
  const screen = { id: 'synthetic-tino-settings', packageName: 'org.nieto.assistant.tino', width: 1080, height: 1920, controls: [
    { id: 'heading', label: 'TINO', role: 'heading', bounds: [20, 20, 500, 120] },
    { id: 'whatsapp', label: 'Probar WhatsApp', role: 'button', bounds: [20, 200, 500, 300] },
    { id: 'gmail', label: 'Probar Gmail', role: 'button', bounds: [20, 350, 500, 450] },
  ] };
  const checks = [];
  let reply;
  try {
    for (const [index, input] of [{ audio }, { prompt: 'Hola Tino, ¿estás ahí?' }].entries()) {
      reply = await oneRealtimeTurn({ screen, ...input });
      const unsolicited = /whatsapp|gmail|\bprobar\b|\bprob[áa]\b|pr[áa]ctica|demostraci[óo]n/i.test(reply.text);
      checks.push({ input: index === 0 ? 'synthetic_audio' : 'text', noUnsolicitedApp: !unsolicited,
        noTask: !reply.createdTask, noPointer: !reply.pointed, audioReceived: reply.audioBytes > 0 });
      requireCheck(!unsolicited && !reply.createdTask && !reply.pointed && (!input.audio || reply.audioBytes > 0), 'greeting_redirected_to_task');
      await closeSocket();
    }
    report.greeting = { syntheticOnly: true, cases: checks, fixedReplyRequired: false };
    return reply;
  } finally { pcm16.fill(0); audio.fill(0); }
}

async function oneRealtimeTurn({ prompt = 'Respondé únicamente «Conexión lista», sin usar herramientas. Esta es una comprobación de conectividad.', resume, screen, audio } = {}) {
  const wsUrl = new URL('/api/assistant/realtime', base); wsUrl.protocol = 'wss:';
  return new Promise((resolveResult, reject) => {
    let settled = false, text = '', ready = false, turnId = null, context = null, session = null, resumeAcknowledged = false, pointed = false, createdTask = false, audioBytes = 0;
    const finish = (error, result) => { if (settled) return; settled = true; clearTimeout(timeout); error ? reject(error) : resolveResult(result); };
    const timeout = setTimeout(() => { finish(safeFailure('realtime_timeout')); socket?.terminate(); }, 60_000);
    socket = new WebSocket(wsUrl, { headers: { Authorization: `Bearer ${device.token}` }, handshakeTimeout: 20_000, maxPayload: 100_000, perMessageDeflate: false });
    socket.on('unexpected-response', (_request, response) => { response.resume(); finish(safeFailure(`upgrade_${response.statusCode}`)); });
    socket.on('error', () => finish(safeFailure('websocket_transport_error')));
    socket.on('close', () => { if (!settled) finish(safeFailure('websocket_closed_before_response')); });
    socket.on('message', raw => {
      try {
        const event = JSON.parse(raw.toString());
        if (event.type === 'error') {
          const code = typeof event.code === 'string' && /^[a-z0-9_]{1,80}$/.test(event.code) ? event.code : 'realtime_provider_error';
          throw safeFailure(code);
        }
        if (event.type === 'ready') {
          requireCheck(!ready && event.model === MODEL, 'unexpected_realtime_model'); ready = true;
          session = event.session;
          if (contextScenario) requireCheck(session?.renewable === true && session.maxSessionMs === 270_000 && session.renewAfterMs === 225_000, 'continuous_session_metadata_missing');
          report.websocketUpgrade = true; report.providerReady = true;
          socket.send(JSON.stringify({ type: 'output.mode', mode: audio ? 'audio' : 'text' }));
          if (resume) socket.send(JSON.stringify({ type: 'resume.context', context: resume }));
          if (screen) socket.send(JSON.stringify({ type: 'screen.context', screen, requestGuidance: false }));
          if (audio) {
            socket.send(JSON.stringify({ type: 'audio.start' }));
            for (let at = 0; at < audio.length; at += 12_000) socket.send(JSON.stringify({ type: 'audio.append', audio: audio.subarray(at, at + 12_000).toString('base64') }));
            socket.send(JSON.stringify({ type: 'audio.commit' }));
          } else socket.send(JSON.stringify({ type: 'text', text: prompt }));
          report.providerTurns++;
        } else if (event.type === 'task.context') {
          context = validateTaskContext(event.context);
          createdTask ||= Boolean(context.goal);
          if (resume && context.goal === resume.goal && context.targetApp === resume.targetApp && context.expectsReply === false) resumeAcknowledged = true;
        } else if (event.type === 'text.done') {
          requireCheck(ready && typeof event.text === 'string' && event.text.length <= 1000, 'invalid_realtime_text');
          text = event.text.trim(); turnId = event.turnId;
        } else if (event.type === 'guidance.step') pointed = true;
        else if (event.type === 'audio.delta') {
          if (!audio) throw safeFailure('unexpected_audio');
          audioBytes += Buffer.from(event.audio ?? '', 'base64').length;
        }
        else if (event.type === 'response.done') {
          requireCheck(event.status === 'completed' && text && event.turnId === turnId, 'incomplete_realtime_response');
          finish(null, { text, context, session, resumeAcknowledged, pointed, createdTask, audioBytes });
        }
      } catch (error) { finish(error.code ? error : safeFailure('invalid_realtime_event')); }
    });
  });
}

try {
  requireCheck(!(contextScenario && greetingScenario), 'select_one_scenario');
  requireCheck(base.protocol === 'https:' && !base.username && !base.password && !base.search && !base.hash && base.pathname === '/'
    && /^tino-api-labitconf(?:-[a-z0-9-]+)?\.vercel\.app$/.test(base.hostname), 'unsafe_cloud_url');
  requireCheck(!(await lstat(operatorFile)).isSymbolicLink(), 'unsafe_operator_file');
  const configuration = await readFile(operatorFile, 'utf8');
  operator = /^\s*INSTITUTION_TOKEN\s*=\s*(.*?)\s*$/m.exec(configuration)?.[1]?.replace(/^(['"])(.*)\1$/, '$2') ?? '';
  requireCheck(operator.length >= 32, 'production_operator_missing');
  stage = 'health';
  const health = await request('/api/health');
  requireCheck(health.storage === 'vercel_blob_private_cas' && health.assistant?.configured === true && health.assistant.model === MODEL, 'cloud_configuration_mismatch');
  report.storage = health.storage; report.modelVerified = true;
  stage = 'unauthorized'; await request('/api/devices/me', { expected: 401 }); report.unauthorizedRejected = true;
  stage = 'websocket_preflight'; await rejectUnauthenticatedSocket(); report.unauthorizedSocketRejected = true;
  if (preflightOnly) { report.ok = true; report.preflightOnly = true; }
  else {
  stage = 'admission_invitation';
  const invitation = await request('/api/institution/device-invitations', { method: 'POST', body: {}, token: operator, expected: 201 });
  enrollment = invitation.enrollmentCode;
  requireCheck(typeof enrollment === 'string' && /^[A-F0-9]{12}$/.test(enrollment), 'invalid_enrollment_response');
  stage = 'admission_registration';
  const registered = await request('/api/devices', { method: 'POST', body: { name: 'Verificación cloud', territoryId: 'AR-C', enrollmentCode: enrollment }, expected: 201 });
  requireCheck(typeof registered.deviceId === 'string' && typeof registered.token === 'string' && registered.token.length >= 32, 'invalid_device_response');
  device = { ...registered, createdAt: new Date().toISOString() }; await saveDevice();
  stage = 'admission_consent';
  await request('/api/devices/me', { method: 'PATCH', body: { institutionMetricsConsent: false }, token: device.token });
  stage = 'admission_identity';
  const identity = await request('/api/devices/me', { token: device.token });
  requireCheck(identity.deviceId === device.deviceId && identity.realtimeApproved === true && identity.institutionMetricsConsent === false, 'durable_identity_mismatch');
  report.enrollmentVerified = true; report.metricsConsent = false;
  stage = 'realtime';
  let reply;
  if (greetingScenario) reply = await verifyGreeting();
  else if (contextScenario) {
    requireCheck(health.assistant.continuousAssistance === true && health.assistant.dailyLimitsEnforced === false, 'continuous_policy_not_enabled');
    const first = await oneRealtimeTurn({ prompt: 'Estoy en WhatsApp pero quiero aumentar el brillo de la pantalla del teléfono. Ayudame con ese objetivo, no con WhatsApp. No hay captura disponible. Contestá brevemente.' });
    requireCheck(first.context?.goal && /ajustes|configuraci[oó]n|settings/i.test(first.context.targetApp), 'task_context_not_created');
    await closeSocket(); await new Promise(resolve => setTimeout(resolve, 650));
    const second = await oneRealtimeTurn({ resume: first.context, prompt: 'Ahora estoy en Ajustes. Antes de seguir, recordame brevemente cuál era mi objetivo.' });
    requireCheck(second.resumeAcknowledged && /brillo|pantalla|iluminaci[oó]n/i.test(second.text), 'task_context_not_retained');
    report.contextAcrossConnections = true; report.intentIndependentOfCurrentApp = true; report.contextPersisted = false;
    report.session = second.session; report.responseChars = first.text.length + second.text.length; reply = second;
  } else reply = await oneRealtimeTurn();
  report.receivedText = true; report.responseChars ??= reply.text.length;
  report.publicGreeting = [operator, device.token, enrollment].reduce((text, secret) => text.split(secret).join('[redacted]'), reply.text).slice(0, 280);
  report.ok = true;
  }
} catch (error) {
  report.code = typeof error.code === 'string' && /^[a-zA-Z0-9_]{1,80}$/.test(error.code) ? error.code : 'verification_failed';
  report.stage = stage; process.exitCode = 1;
} finally {
  await closeSocket();
  if (device && !keepDevice) {
    try {
      await request(`/api/institution/devices/${device.deviceId}/revoke`, { method: 'POST', body: {}, token: operator });
      revoked = true; await saveDevice();
      const identity = await request('/api/devices/me', { token: device.token });
      requireCheck(identity.realtimeApproved === false, 'revocation_not_verified');
      report.verificationDeviceRevoked = true;
    } catch { report.verificationDeviceRevoked = false; report.ok = false; process.exitCode = 1; }
  }
  report.elapsedMs = Date.now() - started;
  console.log(JSON.stringify(report, null, 2));
}
