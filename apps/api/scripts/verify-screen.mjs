import { readFile } from 'node:fs/promises';
import { RealtimeConversation, REALTIME_MODEL } from '../src/realtime.mjs';
import WebSocket from 'ws';

// Fixed synthetic UI only. Never captures or reads a person's screen.
const image = await readFile(new URL('../test/fixtures/synthetic-screen.jpg', import.meta.url));
let providerCode;
const conversation = new RealtimeConversation({ apiKey: process.env.OPENAI_API_KEY ?? '', deviceId: 'synthetic-screen-verification', mode: 'text', responseTimeoutMs: 30_000, maxSessionMs: 45_000,
  socketFactory: (url, options) => { const socket = new WebSocket(url, options); socket.on('message', raw => { try { const event = JSON.parse(raw.toString()); if (event.type === 'error' && /^[a-z0-9_]{1,80}$/.test(event.error?.code ?? '')) providerCode = event.error.code; } catch {} }); return socket; } });
const started = Date.now(); let pointed = false; let textChars = 0;
try {
  const result = new Promise((resolve, reject) => {
    conversation.on('event', event => {
      if (event.type === 'guidance.step' && event.screenId === 'synthetic_screen' && event.controlId === 'search') pointed = true;
      if (event.type === 'text.done') textChars = event.text.length;
    });
    conversation.once('result', resolve); conversation.once('failure', reject);
  }); result.catch(() => {});
  await conversation.connect();
  conversation.handleClient({ type: 'screen.context', requestGuidance: false, screen: { id: 'synthetic_screen', packageName: 'org.nieto.synthetic', width: 360, height: 640,
    controls: [{ id: 'search', label: 'Buscar', role: 'button', bounds: [20, 110, 340, 165] }, { id: 'create', label: 'Crear contacto', role: 'button', bounds: [20, 200, 340, 255] }],
    image: { mimeType: 'image/jpeg', data: image.toString('base64') } } });
  conversation.handleClient({ type: 'text', text: 'Quiero buscar un contacto. Señalame el control Buscar de mi pantalla actual.' });
  const response = await result;
  const ok = response.status === 'completed' && pointed && textChars > 0;
  console.log(JSON.stringify({ ok, model: REALTIME_MODEL, syntheticImage: true, imageBytes: image.length, validatedControlPointed: pointed, receivedText: textChars > 0, elapsedMs: Date.now() - started }));
  if (!ok) process.exitCode = 1;
} catch (error) { console.error(JSON.stringify({ ok: false, model: REALTIME_MODEL, code: error.code ?? 'verification_failed', providerCode, message: error.code ? error.message : 'No se completó la verificación sintética.' })); process.exitCode = 1; }
finally { conversation.close(); }
