import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanChat, cleanName, normalizeCode, parseClientMessage } from '../src/protocol.js';
import { RoomRegistry, TokenBucket, type Peer } from '../src/rooms.js';

const peer = (id: string, closed: string[] = []): Peer => ({ id, name: id, voice: false, send: () => {}, close: () => { closed.push(id); } });

test('codes accept hand-typed variations and nothing outside the alphabet', () => {
  assert.equal(normalizeCode('abc-234'), 'ABC234');
  assert.equal(normalizeCode(' ab c234 '), 'ABC234');
  for (const bad of ['ABC23', 'ABC2345', 'ABCO23', 'ABC123', 'ABC 23!', '', 5, null, undefined]) assert.equal(normalizeCode(bad), undefined);
});

test('names and chat lose control characters and stay within their limits', () => {
  assert.equal(cleanName('  Ana‮  Maria\n'), 'Ana Maria');
  assert.equal(cleanName('x'.repeat(50)).length, 20);
  assert.equal(cleanName(''), 'Guest');
  assert.equal(cleanName(42), 'Guest');
  assert.equal(cleanChat('  hello\u0000 there '), 'hello there');
  assert.equal(cleanChat('   '), undefined);
  assert.equal(cleanChat(7), undefined);
  assert.equal(cleanChat('é'.repeat(900))?.length, 500);
});

test('messages are rebuilt from known fields and malformed ones are refused', () => {
  const touch = parseClientMessage(JSON.stringify({ type: 'touch', texture: 'brush', phase: 'move', x: 1.7, y: -2, t: 12, admin: true }));
  assert.deepEqual(touch, { type: 'touch', texture: 'brush', phase: 'move', x: 1, y: 0, t: 12 });
  assert.deepEqual(parseClientMessage('{"type":"join","code":"abc234","name":"Bia"}'), { type: 'join', code: 'ABC234', name: 'Bia' });
  assert.deepEqual(parseClientMessage('{"type":"release","extra":1}'), { type: 'release' });
  const refused = ['not json', '[]', 'null', '{"type":"nope"}', '{"type":"join","code":"bad"}', '{"type":"chat","text":"  "}',
    '{"type":"touch","texture":"Brush","phase":"move","x":0,"y":0,"t":1}',
    '{"type":"touch","texture":"brush","phase":"drag","x":0,"y":0,"t":1}',
    '{"type":"touch","texture":"brush","phase":"move","x":"0","y":0,"t":1}',
    '{"type":"touch","texture":"brush","phase":"move","x":0,"y":0,"t":-1}',
    '{"type":"touch","texture":"brush","phase":"move","x":0,"y":0}'];
  for (const text of refused) assert.equal(parseClientMessage(text), undefined, text);
});

test('a room holds two friends, refuses a third and forgets an unknown code', () => {
  const registry = new RoomRegistry();
  const room = registry.create(peer('a'));
  assert.ok(typeof room !== 'string' && /^[A-Z2-9]{6}$/.test(room.code));
  const code = (room as { code: string }).code;
  assert.equal(typeof registry.join(code, peer('b')), 'object');
  assert.equal(registry.join(code, peer('c')), 'room-full');
  assert.equal(registry.join('ZZZZZZ', peer('d')), 'room-not-found');
});

test('an empty room can be rejoined during the grace period and is removed after it', () => {
  let now = 1_000;
  const registry = new RoomRegistry({ now: () => now, emptyGraceMs: 60_000 });
  const code = (registry.create(peer('a')) as { code: string }).code;
  registry.leave(code, 'a');
  now += 59_000; registry.sweep();
  assert.equal(typeof registry.join(code, peer('a2')), 'object');
  registry.leave(code, 'a2');
  now += 60_000; registry.sweep();
  assert.equal(registry.join(code, peer('a3')), 'room-not-found');
});

test('rooms expire at their maximum age and close whoever is still inside', () => {
  let now = 0;
  const closed: string[] = [];
  const registry = new RoomRegistry({ now: () => now, maxAgeMs: 10_000 });
  const code = (registry.create(peer('a', closed)) as { code: string }).code;
  now = 9_999; registry.sweep();
  assert.deepEqual(closed, []);
  now = 10_000; registry.sweep();
  assert.deepEqual(closed, ['a']);
  assert.equal(registry.rooms.has(code), false);
});

test('the server refuses new rooms once it is full', () => {
  const registry = new RoomRegistry({ maxRooms: 2 });
  registry.create(peer('a')); registry.create(peer('b'));
  assert.equal(registry.create(peer('c')), 'server-busy');
});

test('a token bucket allows a burst, then only the refill rate', () => {
  let now = 0;
  const bucket = new TokenBucket(3, 2, () => now);
  assert.deepEqual([bucket.take(), bucket.take(), bucket.take(), bucket.take()], [true, true, true, false]);
  now = 500; assert.equal(bucket.take(), true);
  assert.equal(bucket.take(), false);
  now = 60_000; assert.deepEqual([bucket.take(), bucket.take(), bucket.take(), bucket.take()], [true, true, true, false]);
});
