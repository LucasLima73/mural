import { randomBytes, randomInt } from 'node:crypto';
import { CODE_ALPHABET, LIMITS, type PeerInfo, type ServerMessage } from './protocol.js';

export interface Peer {
  id: string;
  name: string;
  /** Whether this person currently has voice chat on. */
  voice: boolean;
  send(message: ServerMessage): void;
  close(): void;
}

export function peerInfo(peer: Peer): PeerInfo {
  return peer.voice ? { id: peer.id, name: peer.name, voice: true } : { id: peer.id, name: peer.name };
}

export interface Room {
  code: string;
  createdAt: number;
  emptySince: number | undefined;
  peers: Map<string, Peer>;
}

export interface RegistryOptions {
  maxPeers: number;
  maxRooms: number;
  /** How long an empty room can be rejoined, so a dropped connection does not lose the room. */
  emptyGraceMs: number;
  maxAgeMs: number;
  now: () => number;
}

export const DEFAULT_REGISTRY: RegistryOptions = {
  maxPeers: 2, maxRooms: 1000, emptyGraceMs: 2 * 60_000, maxAgeMs: 12 * 3_600_000, now: Date.now,
};

export function newPeerId(): string { return randomBytes(4).toString('hex'); }

function newCode(): string {
  return Array.from({ length: LIMITS.code }, () => CODE_ALPHABET[randomInt(CODE_ALPHABET.length)]).join('');
}

/** Keeps every open room in memory. Nothing about a room outlives the process, by design. */
export class RoomRegistry {
  readonly rooms = new Map<string, Room>();
  readonly options: RegistryOptions;

  constructor(options: Partial<RegistryOptions> = {}) {
    this.options = { ...DEFAULT_REGISTRY, ...options };
  }

  create(peer: Peer): Room | 'server-busy' {
    if (this.rooms.size >= this.options.maxRooms) return 'server-busy';
    let code = newCode();
    while (this.rooms.has(code)) code = newCode();
    const room: Room = { code, createdAt: this.options.now(), emptySince: undefined, peers: new Map([[peer.id, peer]]) };
    this.rooms.set(code, room);
    return room;
  }

  /** Returns the room and the friends already in it. */
  join(code: string, peer: Peer): Room | 'room-not-found' | 'room-full' {
    const room = this.rooms.get(code);
    if (!room) return 'room-not-found';
    if (room.peers.size >= this.options.maxPeers) return 'room-full';
    room.peers.set(peer.id, peer);
    room.emptySince = undefined;
    return room;
  }

  leave(code: string, peerId: string): void {
    const room = this.rooms.get(code);
    if (!room?.peers.delete(peerId)) return;
    if (room.peers.size === 0) room.emptySince = this.options.now();
  }

  /** Removes rooms that were empty too long or are older than the limit. Peers in an expired room are closed. */
  sweep(): void {
    const now = this.options.now();
    for (const [code, room] of this.rooms) {
      const emptyTooLong = room.emptySince !== undefined && now - room.emptySince >= this.options.emptyGraceMs;
      const tooOld = now - room.createdAt >= this.options.maxAgeMs;
      if (!emptyTooLong && !tooOld) continue;
      this.rooms.delete(code);
      for (const peer of room.peers.values()) peer.close();
    }
  }
}

/** Refills continuously, so a short burst passes and a sustained flood does not. */
export class TokenBucket {
  private tokens: number;
  private last: number;

  constructor(private capacity: number, private perSecond: number, private now: () => number = Date.now) {
    this.tokens = capacity;
    this.last = now();
  }

  take(): boolean {
    const now = this.now();
    this.tokens = Math.min(this.capacity, this.tokens + ((now - this.last) / 1000) * this.perSecond);
    this.last = now;
    if (this.tokens < 1) return false;
    this.tokens -= 1;
    return true;
  }
}
