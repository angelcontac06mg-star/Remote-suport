// WebSocket signaling server. Media travels directly between peers via WebRTC.
const crypto = require('crypto');
const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');

const positiveInt = (value, fallback) => {
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
};
const PORT = positiveInt(process.env.PORT, 8080);
const ROOM_TTL_MS = positiveInt(process.env.ROOM_TTL_MS, 10 * 60 * 1000);
const RECONNECT_GRACE_MS = positiveInt(process.env.RECONNECT_GRACE_MS, 3 * 60 * 1000);
const MAX_ROOMS = positiveInt(process.env.MAX_ROOMS, 5000);
const srv = http.createServer((_req, res) => {
  res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
  res.end('RemoteAssist signaling server is ready');
});
const wss = new WebSocketServer({ server: srv, path: '/ws', maxPayload: 64 * 1024 });
const rooms = new Map();
const joinAttempts = new Map();
const JOIN_WINDOW_MS = 10 * 60 * 1000;
const MAX_JOIN_ATTEMPTS = positiveInt(process.env.MAX_JOIN_ATTEMPTS, 60);

function send(ws, payload) {
  if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify(payload));
}

function removeRoom(code, reason, notify = true) {
  const room = rooms.get(code);
  if (!room) return;
  clearTimeout(room.expiry);
  clearTimeout(room.reconnectExpiry);
  rooms.delete(code);
  if (notify) {
    send(room.host, { type: 'peer-left', reason });
    send(room.viewer, { type: 'session-ended', reason });
  }
  room.host?.close(1000, 'Session ended');
  room.viewer?.close(1000, 'Session ended');
}

function makeCode() {
  let code;
  do { code = String(crypto.randomInt(100000, 1000000)); } while (rooms.has(code));
  return code;
}

function validIce(d) {
  return typeof d.candidate === 'string' && d.candidate.length <= 4096 &&
    (d.sdpMid == null || typeof d.sdpMid === 'string') &&
    Number.isInteger(d.sdpMLineIndex) && d.sdpMLineIndex >= 0 && d.sdpMLineIndex <= 32;
}

wss.on('connection', (ws, req) => {
  // Reverse proxies append their observed client address at the end of this header.
  const forwardedFor = req.headers['x-forwarded-for'];
  ws.clientAddress = typeof forwardedFor === 'string'
    ? forwardedFor.split(',').pop().trim()
    : (ws._socket.remoteAddress || 'unknown');
  ws.on('message', raw => {
    let d;
    try { d = JSON.parse(raw.toString()); } catch { return send(ws, { type: 'error', msg: 'Mensaje inválido' }); }
    if (!d || typeof d.type !== 'string') return send(ws, { type: 'error', msg: 'Mensaje inválido' });

    if (d.type === 'host') {
      if (ws.role) return send(ws, { type: 'error', msg: 'No se pudo iniciar la sesión' });
      if (typeof d.code === 'string' && typeof d.resumeToken === 'string') {
        const resumed = rooms.get(d.code);
        if (resumed && resumed.resumeToken === d.resumeToken && resumed.reconnectUntil > Date.now()) {
          clearTimeout(resumed.reconnectExpiry);
          resumed.reconnectExpiry = null;
          resumed.reconnectUntil = 0;
          resumed.host = ws;
          ws.code = d.code;
          ws.role = 'host';
          send(ws, { type: 'resumed', code: d.code });
          if (resumed.viewer) {
            send(resumed.viewer, { type: 'phone-reconnected' });
            send(ws, { type: 'joined' });
          } else {
            clearTimeout(resumed.expiry);
            resumed.expiresAt = Date.now() + ROOM_TTL_MS;
            resumed.expiry = setTimeout(() => removeRoom(d.code, 'expired'), ROOM_TTL_MS);
          }
          return;
        }
        send(ws, { type: 'resume-expired' });
        return;
      }
      if (rooms.size >= MAX_ROOMS) return send(ws, { type: 'error', msg: 'No se pudo iniciar la sesión' });
      const code = makeCode();
      const resumeToken = crypto.randomBytes(32).toString('base64url');
      const room = { host: ws, viewer: null, resumeToken, reconnectUntil: 0, expiresAt: Date.now() + ROOM_TTL_MS };
      room.expiry = setTimeout(() => removeRoom(code, 'expired'), ROOM_TTL_MS);
      rooms.set(code, room);
      ws.code = code;
      ws.role = 'host';
      send(ws, { type: 'code', code, resumeToken, expiresIn: ROOM_TTL_MS });
      return;
    }

    if (d.type === 'end-session' && ws.role === 'host') {
      removeRoom(ws.code, 'host-ended');
      return;
    }

    if (d.type === 'join') {
      const code = typeof d.code === 'string' ? d.code : '';
      const room = rooms.get(code);
      const now = Date.now();
      let attempts = joinAttempts.get(ws.clientAddress);
      if (!attempts || now - attempts.startedAt >= JOIN_WINDOW_MS) {
        attempts = { startedAt: now, count: 0 };
        joinAttempts.set(ws.clientAddress, attempts);
      }
      attempts.count++;
      if (attempts.count > MAX_JOIN_ATTEMPTS) return send(ws, { type: 'error', msg: 'Demasiados intentos; vuelve a probar más tarde' });
      if (ws.role || !/^\d{6}$/.test(code) || !room || room.expiresAt <= Date.now() || room.viewer || (!room.host && !room.reconnectUntil)) {
        return send(ws, { type: 'error', msg: 'Código inválido, vencido u ocupado' });
      }
      room.viewer = ws;
      clearTimeout(room.expiry);
      room.expiry = null;
      ws.code = code;
      ws.role = 'viewer';
      send(ws, { type: 'joined' });
      send(room.host, { type: 'joined' });
      return;
    }

    const room = rooms.get(ws.code);
    if (!room || (ws.role !== 'host' && ws.role !== 'viewer')) return;
    if (d.type === 'ice' && validIce(d)) {
      send(ws.role === 'host' ? room.viewer : room.host, d);
      return;
    }
    if (ws.role === 'host' && d.type === 'offer' && typeof d.sdp === 'string' && d.sdp.length <= 48 * 1024) {
      send(room.viewer, d);
      return;
    }
    if (ws.role === 'viewer' && d.type === 'answer' && typeof d.sdp === 'string' && d.sdp.length <= 48 * 1024) {
      send(room.host, d);
      return;
    }
    send(ws, { type: 'error', msg: 'Mensaje no permitido' });
  });

  ws.on('close', () => {
    const room = rooms.get(ws.code);
    if (!room) return;
    if (ws.role === 'host' && room.host === ws) {
      room.host = null;
      room.reconnectUntil = Date.now() + RECONNECT_GRACE_MS;
      send(room.viewer, { type: 'host-reconnecting', graceMs: RECONNECT_GRACE_MS });
      clearTimeout(room.reconnectExpiry);
      room.reconnectExpiry = setTimeout(() => removeRoom(ws.code, 'host-disconnected'), RECONNECT_GRACE_MS);
    }
    else if (room.viewer === ws) {
      room.viewer = null;
      send(room.host, { type: 'peer-left', reason: 'viewer-disconnected' });
      if (room.host) {
        room.expiresAt = Date.now() + ROOM_TTL_MS;
        room.expiry = setTimeout(() => removeRoom(ws.code, 'expired'), ROOM_TTL_MS);
      }
    }
  });
  ws.on('error', () => {});
});

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
  const now = Date.now();
  for (const [address, attempts] of joinAttempts) {
    if (now - attempts.startedAt >= JOIN_WINDOW_MS) joinAttempts.delete(address);
  }
}, 30000);
wss.on('connection', ws => {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
});

function shutdown() {
  clearInterval(heartbeat);
  for (const code of rooms.keys()) removeRoom(code, 'server-shutdown');
  wss.close(() => srv.close(() => process.exit(0)));
  setTimeout(() => process.exit(1), 5000).unref();
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
srv.listen(PORT, '0.0.0.0', () => console.log(`RemoteAssist signaling server listening on ${PORT}`));
