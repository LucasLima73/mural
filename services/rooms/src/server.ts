import { createServer, type IncomingMessage, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { WebSocket, WebSocketServer } from 'ws';
import { parseClientMessage, type ErrorCode, type ServerMessage } from './protocol.js';
import { newPeerId, peerInfo, RoomRegistry, TokenBucket, type Peer, type RegistryOptions, type Room } from './rooms.js';

export interface ServerOptions extends RegistryOptions {
  /** Read the caller's address from X-Forwarded-For. Turn on only behind a proxy you control. */
  trustProxy: boolean;
  connectionsPerAddress: number;
  /** Rooms created plus failed joins a single address may try each minute. */
  attemptsPerMinute: number;
  helloTimeoutMs: number;
  pingMs: number;
  sweepMs: number;
}

export const DEFAULT_SERVER: ServerOptions = {
  maxPeers: 2, maxRooms: 1000, emptyGraceMs: 2 * 60_000, maxAgeMs: 12 * 3_600_000, now: Date.now,
  trustProxy: false, connectionsPerAddress: 20, attemptsPerMinute: 10,
  helloTimeoutMs: 10_000, pingMs: 20_000, sweepMs: 30_000,
};

// Large enough for one audio-only session description, which is the biggest thing a phone sends.
const MAX_MESSAGE_BYTES = 8192;
const MAX_BAD_MESSAGES = 20;
const MAX_BUFFERED_TOUCH_BYTES = 64 * 1024;
const CLOSE_TRY_AGAIN_LATER = 1013;
const CLOSE_POLICY = 1008;

export function clientAddress(request: IncomingMessage, trustProxy: boolean): string {
  const forwarded = trustProxy ? request.headers['x-forwarded-for'] : undefined;
  const first = (Array.isArray(forwarded) ? forwarded[0] : forwarded)?.split(',')[0]?.trim();
  return first || request.socket.remoteAddress || 'unknown';
}

export function createRoomsServer(overrides: Partial<ServerOptions> = {}) {
  const options: ServerOptions = { ...DEFAULT_SERVER, ...overrides };
  const registry = new RoomRegistry(options);
  const connections = new Map<string, number>();
  const attempts = new Map<string, number[]>();

  const http: Server = createServer((request, response) => {
    if (request.method === 'GET' && request.url === '/health') {
      response.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      response.end(JSON.stringify({ ok: true, rooms: registry.rooms.size }));
    } else {
      response.writeHead(404).end();
    }
  });
  const sockets = new WebSocketServer({ noServer: true, maxPayload: MAX_MESSAGE_BYTES });
  http.on('upgrade', (request, socket, head) => {
    if (new URL(request.url ?? '/', 'http://room').pathname !== '/ws') {
      socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }
    sockets.handleUpgrade(request, socket, head, ws => sockets.emit('connection', ws, request));
  });

  /** True when this address has used up its attempts; otherwise records one. */
  function tooManyAttempts(address: string): boolean {
    const now = options.now();
    const recent = (attempts.get(address) ?? []).filter(at => now - at < 60_000);
    if (recent.length >= options.attemptsPerMinute) { attempts.set(address, recent); return true; }
    attempts.set(address, [...recent, now]);
    return false;
  }

  sockets.on('connection', (ws: WebSocket, request: IncomingMessage) => {
    const address = clientAddress(request, options.trustProxy);
    const open = connections.get(address) ?? 0;
    if (open >= options.connectionsPerAddress) { ws.close(CLOSE_TRY_AGAIN_LATER); return; }
    connections.set(address, open + 1);

    let room: Room | undefined;
    let me: Peer | undefined;
    let bad = 0;
    let alive = true;
    const touches = new TokenBucket(120, 90, options.now);
    const chats = new TokenBucket(5, 2, options.now);
    const signals = new TokenBucket(30, 15, options.now);

    const send = (message: ServerMessage) => {
      if (ws.readyState !== WebSocket.OPEN) return;
      // A friend on a slow connection should miss some movement, never fall seconds behind.
      if (message.type === 'touch' && ws.bufferedAmount > MAX_BUFFERED_TOUCH_BYTES) return;
      ws.send(JSON.stringify(message));
    };
    const fail = (code: ErrorCode, close = false) => {
      send({ type: 'error', code });
      if (close) ws.close(CLOSE_POLICY);
    };
    const others = (message: ServerMessage) => {
      for (const peer of room?.peers.values() ?? []) if (peer.id !== me?.id) peer.send(message);
    };
    const enter = (name: string) => {
      me = { id: newPeerId(), name, voice: false, send, close: () => ws.close(1001) };
      return me;
    };
    const joined = (entered: Room, self: Peer) => {
      room = entered;
      clearTimeout(hello);
      const peers = [...entered.peers.values()].filter(peer => peer.id !== self.id).map(peerInfo);
      send({ type: 'joined', code: entered.code, you: self.id, peers, maxPeers: options.maxPeers });
      others({ type: 'peer-joined', peer: peerInfo(self) });
    };

    const hello = setTimeout(() => ws.close(CLOSE_POLICY), options.helloTimeoutMs);
    const ping = setInterval(() => {
      if (!alive) { ws.terminate(); return; }
      alive = false;
      ws.ping();
    }, options.pingMs);
    ws.on('pong', () => { alive = true; });

    ws.on('message', (data, isBinary) => {
      if (isBinary) { ws.close(1003); return; }
      const message = parseClientMessage(data.toString());
      if (!message) {
        if (++bad >= MAX_BAD_MESSAGES) ws.close(CLOSE_POLICY); else fail('bad-request');
        return;
      }
      switch (message.type) {
        case 'create':
        case 'join': {
          if (room) return fail('already-in-room');
          if (message.type === 'create') {
            if (tooManyAttempts(address)) return fail('rate-limited', true);
            const created = registry.create(enter(message.name));
            if (created === 'server-busy') return fail(created, true);
            return joined(created, me!);
          }
          const entered = registry.join(message.code, enter(message.name));
          if (typeof entered === 'string') {
            me = undefined;
            return fail(tooManyAttempts(address) ? 'rate-limited' : entered, true);
          }
          return joined(entered, me!);
        }
        case 'touch': {
          if (!room || !me) return fail('not-in-room');
          if (!touches.take()) return;
          const { type, ...touch } = message;
          return others({ type, from: me.id, ...touch });
        }
        case 'release':
          if (!room || !me) return fail('not-in-room');
          return others({ type: 'release', from: me.id });
        case 'chat': {
          if (!room || !me) return fail('not-in-room');
          if (!chats.take()) return fail('rate-limited');
          const chat: ServerMessage = { type: 'chat', from: me.id, text: message.text, at: options.now() };
          send(chat);
          return others(chat);
        }
        case 'voice': {
          if (!room || !me) return fail('not-in-room');
          if (me.voice === message.on) return;
          me.voice = message.on;
          return others({ type: 'voice', from: me.id, on: message.on });
        }
        case 'signal': {
          if (!room || !me) return fail('not-in-room');
          if (!signals.take()) return fail('rate-limited');
          const { type, to, ...signal } = message;
          const outgoing: ServerMessage = { type, from: me.id, ...signal };
          if (to === undefined) return others(outgoing);
          return void room.peers.get(to)?.send(outgoing);
        }
        case 'leave':
          ws.close(1000);
      }
    });

    ws.on('close', () => {
      clearTimeout(hello);
      clearInterval(ping);
      connections.set(address, (connections.get(address) ?? 1) - 1);
      if (connections.get(address) === 0) connections.delete(address);
      if (room && me) {
        registry.leave(room.code, me.id);
        others({ type: 'peer-left', id: me.id });
      }
    });
    ws.on('error', () => ws.terminate());
  });

  const sweeper = setInterval(() => {
    registry.sweep();
    const now = options.now();
    for (const [address, times] of attempts) if (times.every(at => now - at >= 60_000)) attempts.delete(address);
  }, options.sweepMs);
  sweeper.unref();

  return {
    registry,
    listen: (port: number, host: string) => new Promise<number>(resolve => {
      http.listen(port, host, () => resolve((http.address() as AddressInfo).port));
    }),
    close: () => new Promise<void>(resolve => {
      clearInterval(sweeper);
      for (const client of sockets.clients) client.terminate();
      sockets.close(() => http.close(() => resolve()));
      http.closeAllConnections();
    }),
  };
}
