import { readFile, writeFile, mkdir, lstat, realpath } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, sep } from 'node:path';
import WebSocket from 'ws';

const root = fileURLToPath(new URL('..', import.meta.url));
const secretDirectory = resolve(root, '.cache/secrets');
const operatorFile = resolve(secretDirectory, 'tino-production-operator.env');
const deviceFile = resolve(secretDirectory, 'tino-cloud-verification.json');
const MODEL = 'gpt-realtime-2.1-mini';
const suppliedUrl = process.argv.indexOf('--url');
const base = new URL(suppliedUrl < 0 ? 'https://tino-api-labitconf.vercel.app/' : process.argv[suppliedUrl + 1]);
const keepDevice = process.argv.includes('--keep-device');
const preflightOnly = process.argv.includes('--preflight');
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

async function oneRealtimeTurn() {
  const wsUrl = new URL('/api/assistant/realtime', base); wsUrl.protocol = 'wss:';
  return new Promise((resolveResult, reject) => {
    let settled = false, text = '', ready = false, turnId = null;
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
          report.websocketUpgrade = true; report.providerReady = true;
          socket.send(JSON.stringify({ type: 'output.mode', mode: 'text' }));
          socket.send(JSON.stringify({ type: 'text', text: 'Respondé únicamente «Conexión lista», sin usar herramientas. Esta es una comprobación de conectividad.' }));
          report.providerTurns = 1;
        } else if (event.type === 'text.done') {
          requireCheck(ready && typeof event.text === 'string' && event.text.length <= 1000, 'invalid_realtime_text');
          text = event.text.trim(); turnId = event.turnId;
        } else if (event.type === 'audio.delta') throw safeFailure('unexpected_audio');
        else if (event.type === 'response.done') {
          requireCheck(event.status === 'completed' && text && event.turnId === turnId, 'incomplete_realtime_response');
          finish(null, text);
        }
      } catch (error) { finish(error.code ? error : safeFailure('invalid_realtime_event')); }
    });
  });
}

try {
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
  const reply = await oneRealtimeTurn();
  report.receivedText = true; report.responseChars = reply.length;
  report.publicGreeting = [operator, device.token, enrollment].reduce((text, secret) => text.split(secret).join('[redacted]'), reply).slice(0, 280);
  report.ok = true;
  }
} catch (error) {
  report.code = typeof error.code === 'string' && /^[a-zA-Z0-9_]{1,80}$/.test(error.code) ? error.code : 'verification_failed';
  report.stage = stage; process.exitCode = 1;
} finally {
  if (socket && socket.readyState !== WebSocket.CLOSED) { socket.close(); setTimeout(() => socket?.terminate(), 1000).unref(); }
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
