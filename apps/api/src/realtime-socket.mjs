import { WebSocketServer, WebSocket } from 'ws';
import { RealtimeError } from './realtime.mjs';

const safeEvent = error => ({ type: 'error', code: error instanceof RealtimeError ? error.code : 'realtime_unavailable',
  message: error instanceof RealtimeError ? error.message : 'No pude completar la conexión de voz.', fatal: !(error instanceof RealtimeError) || error.fatal });

/** The mobile protocol is deliberately not an arbitrary proxy to OpenAI events. */
export function attachRealtimeSocket(server, app) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: 24_000, perMessageDeflate: false });
  const clients = new Set();
  server.on('upgrade', async (req, socket, head) => {
    const reject = (status, message) => { if (!socket.destroyed) socket.end(`HTTP/1.1 ${status} ${message}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`); };
    socket.on('error', () => {});
    let conversation;
    try {
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname !== '/api/assistant/realtime' || url.search) { reject(404, 'Not Found'); return; }
      const deviceId = await app.authorizeRealtime(req);
      if (socket.destroyed) return;
      if (!app.realtime.status().configured) throw new RealtimeError('openai_not_configured', 'Falta OPENAI_API_KEY en el servidor.', 503);
      wss.handleUpgrade(req, socket, head, client => {
        client.on('error', () => { if (client.readyState !== WebSocket.CLOSED) client.terminate(); });
        // Invalid handshakes never reserve a slot: ws can reject them without
        // throwing or invoking this callback.
        try { conversation = app.realtime.reserve(deviceId, 'audio'); }
        catch (error) { client.send(JSON.stringify(safeEvent(error))); client.close(1008, 'Conversación no disponible'); return; }
        clients.add(client);
        let stopped = false; let windowStart = Date.now(); let messageCount = 0;
        const send = event => {
          if (stopped || client.readyState !== WebSocket.OPEN) return;
          if (client.bufferedAmount > 512_000) { stop(1013, 'La reproducción no sigue el ritmo de la conexión.'); return; }
          client.send(JSON.stringify(event));
        };
        const stop = (code = 1000, reason = 'Conversación cerrada') => {
          if (stopped) return; stopped = true;
          conversation.close(); clients.delete(client);
          if (client.readyState === WebSocket.OPEN) client.close(code, reason);
          else if (client.readyState !== WebSocket.CLOSED) client.terminate();
        };
        conversation.on('event', event => { send(event); if (event.type === 'error' && event.fatal) stop(1011, 'Conexión de voz interrumpida'); });
        conversation.once('closed', () => stop());
        client.on('message', (raw, binary) => {
          try {
            if (binary) throw new RealtimeError('invalid_realtime_message', 'Usá mensajes JSON para la conversación.', 400, false);
            if (Date.now() - windowStart > 10_000) { windowStart = Date.now(); messageCount = 0; }
            if (++messageCount > 250) throw new RealtimeError('realtime_rate_limit', 'La conexión envió demasiados mensajes.', 429);
            let event; try { event = JSON.parse(raw.toString()); } catch { throw new RealtimeError('invalid_realtime_message', 'El mensaje JSON no es válido.', 400, false); }
            conversation.handleClient(event);
          } catch (error) { const event = safeEvent(error); send(event); if (event.fatal) stop(1008, 'Límite o protocolo de voz'); }
        });
        client.once('close', () => stop()); client.once('error', () => stop());
        void conversation.connect().catch(() => stop(1011, 'No se pudo abrir Realtime'));
      });
    } catch (error) { conversation?.close(); reject(error instanceof RealtimeError ? error.status : 500, 'Realtime unavailable'); }
  });
  const close = () => {
    for (const client of clients) client.terminate(); clients.clear();
    app.realtime.close(); wss.close();
  };
  server.closeRealtime = close;
  server.once('close', close);
  return server;
}
