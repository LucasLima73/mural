# Mural rooms

A small WebSocket service that puts friends in a room. One person creates a room and gets a six-character
code, a friend joins with it, and from then on the server passes their touches, chat lines and presence to
each other. It keeps everything in memory: no accounts, no database, and nothing survives a restart.

This service is separate from `services/api`, which handles accounts and payments. Nothing here touches
that server or its database.

## Run it locally

```sh
cd services/rooms
npm install
npm test          # unit and WebSocket tests
npm run dev       # listens on 127.0.0.1:8787
```

The Android debug build reaches a server on your computer at `ws://10.0.2.2:8787` from the emulator. To try
it with a friend on another network, expose the port with a tunnel that provides TLS, for example
`cloudflared tunnel --url http://localhost:8787`, and build the app with the resulting `wss://` address (see
[docs/rooms.md](../../docs/rooms.md)). Release builds refuse plain `ws://`.

## Settings

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8787` | Port to listen on |
| `HOST` | `127.0.0.1` (`0.0.0.0` in the image) | Interface to listen on |
| `ROOM_MAX_PEERS` | `2` | People per room, 2 to 8 |
| `TRUST_PROXY` | `false` | Read the caller's address from `X-Forwarded-For`. Turn on only behind a proxy you control, or per-address limits can be bypassed |

## Protocol

One JSON object per WebSocket message, at `/ws`. `GET /health` reports that the service is up.

Client to server:

| Message | Meaning |
|---|---|
| `{"type":"create","name":"Ana"}` | Open a new room |
| `{"type":"join","code":"ABC234","name":"Bia"}` | Join a room by code |
| `{"type":"touch","texture":"brush","phase":"down\|move\|up","x":0.4,"y":0.7,"t":123}` | A finger event on a pad, `x` and `y` from 0 to 1 |
| `{"type":"release"}` | Silence everything I was holding |
| `{"type":"chat","text":"hi"}` | A text message |
| `{"type":"leave"}` | Leave the room |

Server to client: `joined` (with your id, the code and who is already in), `peer-joined`, `peer-left`, `touch`
and `release` (each with `from`), `chat` (with `from` and the server time `at`), and `error` with one of
`bad-request`, `room-not-found`, `room-full`, `server-busy`, `rate-limited`, `not-in-room`, `already-in-room`.

## Limits

- Messages over 2 KB, binary frames and connections that never join within 10 seconds are closed.
- Chat: 5 messages in a burst, then 2 per second. Touches: 120 in a burst, then 90 per second, and extra
  movement is dropped instead of queued. A slow receiver misses movement rather than falling behind.
- Each address may hold 20 connections and create or fail to join 10 rooms per minute.
- A room lasts 2 minutes after its last person leaves and 12 hours at most. The server holds 1000 rooms.
- Names are 20 characters and chat lines 500, with control and direction-override characters removed.

## Privacy

The server logs that it started and nothing else: no addresses, names, codes or messages. Codes come from a
31-character alphabet without look-alike characters, so there are about 887 million of them, and guessing is
rate limited. Anyone who has a code can join a room that has space, so treat a code like a private link.
