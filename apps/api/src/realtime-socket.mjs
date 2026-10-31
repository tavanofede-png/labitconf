import { WebSocketServer, WebSocket } from 'ws';
import { RealtimeError } from './realtime.mjs';

const safeEvent = error => ({ type: 'error', code: error instanceof RealtimeError ? error.code : 'realtime_unavailable',
  message: error instanceof RealtimeError ? error.message : 'No pude completar la conexión de voz.', fatal: !(error instanceof RealtimeError) || error.fatal });

/** The mobile protocol is deliberately not an arbitrary proxy to OpenAI events. */
export function attachRealtimeSocket(server, app) {
  const wss = new WebSocketServer({ noServer: true, maxPayload: 450_000, perMessageDeflate: false });
  const clients = new Set();
  server.on('upgrade', async (req, socket, head) => {
    const reject = (status, message, routingFailure = '') => { if (!socket.destroyed) socket.end(`HTTP/1.1 ${status} ${message}\r\nConnection: close\r\nContent-Length: 0\r\n${routingFailure ? `X-Tino-Realtime-Rejection: ${routingFailure}\r\n` : ''}\r\n`); };
    socket.on('error', () => {});
    let conversation;
    try {
      const url = new URL(req.url, 'http://localhost');
      if (!app.realtimePaths.has(url.pathname)) { reject(404, 'Not Found', 'route'); return; }
      const query = [...url.searchParams.entries()];
      const rewrite = app.realtimeRewriteQuery;
      const mappedQuery = rewrite && query.length === Object.keys(rewrite).length
        && query.every(([key, value]) => Object.hasOwn(rewrite, key) && rewrite[key] === value);
      if (query.length && !mappedQuery) { reject(404, 'Not Found', 'query'); return; }
      const deviceId = await app.authorizeRealtime(req);
      if (socket.destroyed) return;
      if (!app.realtime.status().configured) throw new RealtimeError('openai_not_configured', 'Falta OPENAI_API_KEY en el servidor.', 503);
      wss.handleUpgrade(req, socket, head, async client => {
        let leaseId = null; let released = false;
        const release = () => {
          if (!leaseId || released) return; released = true;
          // A crashed instance may not finish this write; the durable lease
          // expires after the bounded cloud session and never grants more use.
          void app.admission.release(leaseId).catch(() => {});
        };
        client.on('error', () => { if (client.readyState !== WebSocket.CLOSED) client.terminate(); });
        client.once('close', () => { conversation?.close(); release(); });
        // Invalid handshakes never reserve a slot: ws can reject them without
        // throwing or invoking this callback.
        try {
          const address = app.clientAddress?.(req) ?? req.socket.remoteAddress;
          conversation = app.realtime.reserve(deviceId, 'audio', address);
          const reservation = await app.admission.reserve(deviceId, address, 'ws');
          leaseId = reservation.leaseId ?? null;
          if (client.readyState !== WebSocket.OPEN || conversation.closed) { conversation.close(); release(); return; }
        }
        catch (error) { conversation?.close(); release(); if (client.readyState === WebSocket.OPEN) { client.send(JSON.stringify(safeEvent(error))); client.close(1008, 'Conversación no disponible'); } return; }
        clients.add(client);
        let stopped = false; let windowStart = Date.now(); let messageCount = 0; let leaseCheck = null; let checkingLease = false;
        const send = event => {
          if (stopped || client.readyState !== WebSocket.OPEN) return;
          if (client.bufferedAmount > 512_000) { stop(1013, 'La reproducción no sigue el ritmo de la conexión.'); return; }
          client.send(JSON.stringify(event));
        };
        const stop = (code = 1000, reason = 'Conversación cerrada') => {
          if (stopped) return; stopped = true;
          clearInterval(leaseCheck); release();
          conversation.close(); clients.delete(client);
          if (client.readyState === WebSocket.OPEN) client.close(code, reason);
          else if (client.readyState !== WebSocket.CLOSED) client.terminate();
        };
        conversation.on('event', event => { send(event); if (event.type === 'error' && event.fatal) stop(1011, 'Conexión de voz interrumpida'); });
        conversation.once('closed', () => stop());
        if (leaseId) {
          leaseCheck = setInterval(async () => {
            if (stopped || checkingLease) return; checkingLease = true;
            try { await app.admission.validateLease(leaseId); }
            catch (error) { send(safeEvent(error)); stop(1008, 'Acceso de voz desactivado'); }
            finally { checkingLease = false; }
          }, 5_000);
          leaseCheck.unref?.();
        }
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
