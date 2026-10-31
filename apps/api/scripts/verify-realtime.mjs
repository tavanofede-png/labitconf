import { RealtimeConversation, REALTIME_MODEL } from '../src/realtime.mjs';

const mode = process.argv.includes('--text') ? 'text' : 'audio';
const conversation = new RealtimeConversation({ apiKey: process.env.OPENAI_API_KEY, mode, deviceId: 'nieto-realtime-verification', responseTimeoutMs: 30_000, maxSessionMs: 45_000 });
const start = Date.now(); let audioBytes = 0; let outputChars = 0;
conversation.on('event', event => {
  if (event.type === 'audio.delta') audioBytes += Buffer.from(event.audio, 'base64').length;
  if (event.type === 'text.done') outputChars = event.text.length;
});
let finish;
const result = new Promise((resolve, reject) => {
  finish = { resolve, reject };
  conversation.once('result', resolve); conversation.once('failure', reject);
});
result.catch(() => {});
try {
  await conversation.connect();
  conversation.handleClient({ type: 'text', text: 'Respondé únicamente con estas dos palabras, sin herramientas: Conexión lista.' });
  const response = await result;
  if (response.status !== 'completed' || !response.text.trim() || (mode === 'audio' && !audioBytes)) throw new Error('incomplete');
  console.log(JSON.stringify({ ok: true, model: REALTIME_MODEL, mode, receivedText: true, outputChars, audioBytes, elapsedMs: Date.now() - start, keyPrinted: false }, null, 2));
} catch (error) {
  finish?.reject(error);
  console.error(JSON.stringify({ ok: false, model: REALTIME_MODEL, mode, code: error.code ?? 'verification_failed', message: error.code ? error.message : 'La verificación no obtuvo una respuesta completa.', keyPrinted: false }, null, 2));
  process.exitCode = 1;
} finally { conversation.close(); }
