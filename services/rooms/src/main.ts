import { createRoomsServer } from './server.js';

const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? '127.0.0.1';
const maxPeers = Number(process.env.ROOM_MAX_PEERS ?? 2);
if (!Number.isInteger(port) || port < 0 || port > 65535) throw new Error('Invalid PORT.');
if (!Number.isInteger(maxPeers) || maxPeers < 2 || maxPeers > 8) throw new Error('ROOM_MAX_PEERS must be 2 to 8.');
if (process.env.TRUST_PROXY && !['true', 'false'].includes(process.env.TRUST_PROXY)) throw new Error('Invalid TRUST_PROXY.');

const server = createRoomsServer({ maxPeers, trustProxy: process.env.TRUST_PROXY === 'true' });
const bound = await server.listen(port, host);
// Rooms hold friends' chats and touches, so logs record that the service ran, never what was said.
console.log(JSON.stringify({ event: 'listening', host, port: bound, maxPeers }));

for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.once(signal, () => { void server.close().then(() => process.exit(0)); });
}
