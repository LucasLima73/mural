import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { createRoomsServer, clientAddress } from '../src/server.js';
import type { ServerMessage } from '../src/protocol.js';

type Message = ServerMessage;
const servers: Array<{ close(): Promise<void> }> = [];
after(async () => { for (const server of servers) await server.close(); });

async function start(options: Parameters<typeof createRoomsServer>[0] = {}) {
  const server = createRoomsServer({ pingMs: 60_000, ...options });
  servers.push(server);
  const port = await server.listen(0, '127.0.0.1');
  return { server, port };
}

class Client {
  readonly inbox: Message[] = [];
  closed: Promise<number>;
  private waiting: Array<() => void> = [];
  private constructor(readonly ws: WebSocket) {
    ws.on('message', data => { this.inbox.push(JSON.parse(data.toString())); this.waiting.splice(0).forEach(wake => wake()); });
    this.closed = new Promise(resolve => ws.on('close', code => resolve(code)));
  }
  static async open(port: number, path = '/ws'): Promise<Client> {
    const ws = new WebSocket(`ws://127.0.0.1:${port}${path}`);
    await new Promise<void>((resolve, reject) => { ws.once('open', resolve); ws.once('error', reject); });
    return new Client(ws);
  }
  send(value: unknown) { this.ws.send(typeof value === 'string' ? value : JSON.stringify(value)); }
  async next<T extends Message['type']>(type: T, timeoutMs = 2000): Promise<Extract<Message, { type: T }>> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const index = this.inbox.findIndex(message => message.type === type);
      if (index >= 0) return this.inbox.splice(index, 1)[0] as Extract<Message, { type: T }>;
      const left = deadline - Date.now();
      if (left <= 0) throw new Error(`No ${type} message; got ${JSON.stringify(this.inbox)}`);
      await new Promise<void>(resolve => { this.waiting.push(resolve); setTimeout(resolve, left); });
    }
  }
  async quiet(ms = 150) { await new Promise(resolve => setTimeout(resolve, ms)); }
  close() { this.ws.close(); return this.closed; }
}

async function pair(port: number) {
  const ana = await Client.open(port);
  ana.send({ type: 'create', name: 'Ana' });
  const created = await ana.next('joined');
  const bia = await Client.open(port);
  bia.send({ type: 'join', code: created.code.toLowerCase(), name: 'Bia' });
  const joined = await bia.next('joined');
  return { ana, bia, code: created.code, anaId: created.you, biaId: joined.you };
}

test('a friend joins by code and both sides learn who is there', async () => {
  const { port } = await start();
  const { ana, bia, anaId, biaId, code } = await pair(port);
  assert.match(code, /^[A-Z2-9]{6}$/);
  const arrival = await ana.next('peer-joined');
  assert.deepEqual(arrival.peer, { id: biaId, name: 'Bia' });
  assert.notEqual(anaId, biaId);
  assert.equal(bia.inbox.length, 0);
});

test('the joining friend is told who was already in the room', async () => {
  const { port } = await start();
  const ana = await Client.open(port);
  ana.send({ type: 'create', name: 'Ana' });
  const created = await ana.next('joined');
  assert.deepEqual(created.peers, []);
  assert.equal(created.maxPeers, 2);
  const bia = await Client.open(port);
  bia.send({ type: 'join', code: created.code, name: 'Bia' });
  const joined = await bia.next('joined');
  assert.deepEqual(joined.peers, [{ id: created.you, name: 'Ana' }]);
});

test('touches reach the friend, rebuilt and clamped, and never echo back to the sender', async () => {
  const { port } = await start();
  const { ana, bia, anaId } = await pair(port);
  ana.send({ type: 'touch', texture: 'brush', phase: 'move', x: 9, y: 0.25, t: 1234, injected: 'nope' });
  const touch = await bia.next('touch');
  assert.deepEqual(touch, { type: 'touch', from: anaId, texture: 'brush', phase: 'move', x: 1, y: 0.25, t: 1234 });
  await ana.quiet();
  assert.equal(ana.inbox.filter(message => message.type === 'touch').length, 0);
});

test('release tells the friend to silence everything held', async () => {
  const { port } = await start();
  const { ana, bia, anaId } = await pair(port);
  ana.send({ type: 'release' });
  assert.deepEqual(await bia.next('release'), { type: 'release', from: anaId });
});

test('chat reaches both people with the sender and the server time', async () => {
  const { port } = await start({ now: () => 5_000 });
  const { ana, bia, biaId } = await pair(port);
  bia.send({ type: 'chat', text: '  oi  ' });
  const own = await bia.next('chat');
  const theirs = await ana.next('chat');
  assert.deepEqual(theirs, { type: 'chat', from: biaId, text: 'oi', at: 5_000 });
  assert.deepEqual(own, theirs);
});

test('a third person is turned away and a wrong code is refused, each closing that connection', async () => {
  const { port } = await start();
  const { code } = await pair(port);
  const late = await Client.open(port);
  late.send({ type: 'join', code, name: 'Cris' });
  assert.equal((await late.next('error')).code, 'room-full');
  assert.equal(await late.closed, 1008);
  const lost = await Client.open(port);
  lost.send({ type: 'join', code: 'ZZZZZZ', name: 'Dan' });
  assert.equal((await lost.next('error')).code, 'room-not-found');
  assert.equal(await lost.closed, 1008);
});

test('leaving tells the friend, and the room can be rejoined while it waits', async () => {
  const { port } = await start();
  const { ana, bia, code, biaId } = await pair(port);
  await ana.next('peer-joined');
  await bia.close();
  assert.deepEqual(await ana.next('peer-left'), { type: 'peer-left', id: biaId });
  const back = await Client.open(port);
  back.send({ type: 'join', code, name: 'Bia' });
  const joined = await back.next('joined');
  assert.equal(joined.peers.length, 1);
  assert.notEqual(joined.you, biaId);
});

test('an empty room is removed once its grace period has passed', async () => {
  let now = 0;
  const { port, server } = await start({ now: () => now, emptyGraceMs: 1_000 });
  const ana = await Client.open(port);
  ana.send({ type: 'create', name: 'Ana' });
  const { code } = await ana.next('joined');
  await ana.close();
  await new Promise(resolve => setTimeout(resolve, 50));
  now = 2_000; server.registry.sweep();
  const late = await Client.open(port);
  late.send({ type: 'join', code, name: 'Ana' });
  assert.equal((await late.next('error')).code, 'room-not-found');
});

test('messages before joining are refused and a second create is refused', async () => {
  const { port } = await start();
  const early = await Client.open(port);
  early.send({ type: 'chat', text: 'hi' });
  assert.equal((await early.next('error')).code, 'not-in-room');
  early.send({ type: 'create', name: 'Ana' });
  await early.next('joined');
  early.send({ type: 'create', name: 'Ana' });
  assert.equal((await early.next('error')).code, 'already-in-room');
});

test('a chat flood is refused without closing, and a touch flood is dropped quietly', async () => {
  const { port } = await start();
  const { ana, bia } = await pair(port);
  for (let i = 0; i < 12; i++) ana.send({ type: 'chat', text: `m${i}` });
  await ana.next('error');
  assert.ok(bia.inbox.filter(message => message.type === 'chat').length <= 6);
  for (let i = 0; i < 400; i++) ana.send({ type: 'touch', texture: 'brush', phase: 'move', x: 0.5, y: 0.5, t: i });
  await bia.quiet(400);
  const received = bia.inbox.filter(message => message.type === 'touch').length;
  assert.ok(received > 0 && received < 400, `received ${received}`);
  assert.equal(ana.ws.readyState, WebSocket.OPEN);
});

test('malformed input is refused, repeated abuse closes the connection, and oversize or binary frames are rejected', async () => {
  const { port } = await start();
  const messy = await Client.open(port);
  messy.send('not json');
  assert.equal((await messy.next('error')).code, 'bad-request');
  for (let i = 0; i < 25; i++) messy.send('{"type":"nope"}');
  assert.equal(await messy.closed, 1008);
  const big = await Client.open(port);
  big.send(JSON.stringify({ type: 'chat', text: 'x'.repeat(9000) }));
  assert.equal(await big.closed, 1009);
  const binary = await Client.open(port);
  binary.ws.send(Buffer.from([1, 2, 3]));
  assert.equal(await binary.closed, 1003);
});

test('one address can only create so many rooms a minute', async () => {
  const { port } = await start({ attemptsPerMinute: 3 });
  for (let i = 0; i < 3; i++) {
    const client = await Client.open(port);
    client.send({ type: 'create', name: 'Ana' });
    await client.next('joined');
  }
  const blocked = await Client.open(port);
  blocked.send({ type: 'create', name: 'Ana' });
  assert.equal((await blocked.next('error')).code, 'rate-limited');
  assert.equal(await blocked.closed, 1008);
});

test('guessing codes is limited per address too', async () => {
  const { port } = await start({ attemptsPerMinute: 2 });
  const codes: string[] = [];
  for (const code of ['AAAAAA', 'BBBBBB', 'CCCCCC']) {
    const client = await Client.open(port);
    client.send({ type: 'join', code, name: 'Eve' });
    codes.push((await client.next('error')).code);
  }
  assert.deepEqual(codes, ['room-not-found', 'room-not-found', 'rate-limited']);
});

test('too many connections from one address are turned away', async () => {
  const { port } = await start({ connectionsPerAddress: 2 });
  await Client.open(port); await Client.open(port);
  const extra = await Client.open(port);
  assert.equal(await extra.closed, 1013);
});

test('a connection that never says hello is closed', async () => {
  const { port } = await start({ helloTimeoutMs: 100 });
  const idle = await Client.open(port);
  assert.equal(await idle.closed, 1008);
});

test('the health check answers, and only /ws accepts sockets', async () => {
  const { port } = await start();
  const health = await fetch(`http://127.0.0.1:${port}/health`);
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), { ok: true, rooms: 0 });
  assert.equal((await fetch(`http://127.0.0.1:${port}/other`)).status, 404);
  await assert.rejects(Client.open(port, '/other'));
});

test('the caller address comes from the proxy header only when told to trust it', () => {
  const request = (forwarded?: string) => ({ headers: forwarded ? { 'x-forwarded-for': forwarded } : {}, socket: { remoteAddress: '10.0.0.9' } }) as never;
  assert.equal(clientAddress(request('203.0.113.7, 10.0.0.1'), true), '203.0.113.7');
  assert.equal(clientAddress(request('203.0.113.7'), false), '10.0.0.9');
  assert.equal(clientAddress(request(), true), '10.0.0.9');
});
