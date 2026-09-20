/** Wire format between the phones and the room server. Everything is one small JSON object per WebSocket message. */

export const TOUCH_PHASES = ['down', 'move', 'up'] as const;
export type TouchPhase = (typeof TOUCH_PHASES)[number];

export interface Touch { texture: string; phase: TouchPhase; x: number; y: number; t: number }
/** `voice` is present only while that person has voice chat on, so the common case stays small. */
export interface PeerInfo { id: string; name: string; voice?: true }

export const SIGNAL_KINDS = ['offer', 'answer', 'candidate'] as const;
export type SignalKind = (typeof SIGNAL_KINDS)[number];

/** A step in setting up a voice connection between two phones. The server passes it along without reading it. */
export interface Signal {
  call: string;
  kind: SignalKind;
  sdp?: string;
  candidate?: string;
  sdpMid?: string;
  sdpMLineIndex?: number;
}

export type ClientMessage =
  | { type: 'create'; name: string }
  | { type: 'join'; code: string; name: string }
  | ({ type: 'touch' } & Touch)
  | { type: 'release' }
  | { type: 'chat'; text: string }
  | { type: 'voice'; on: boolean }
  | ({ type: 'signal'; to?: string } & Signal)
  | { type: 'leave' };

export type ErrorCode =
  | 'bad-request' | 'room-not-found' | 'room-full' | 'server-busy' | 'rate-limited' | 'not-in-room' | 'already-in-room';

export type ServerMessage =
  | { type: 'joined'; code: string; you: string; peers: PeerInfo[]; maxPeers: number }
  | { type: 'peer-joined'; peer: PeerInfo }
  | { type: 'peer-left'; id: string }
  | ({ type: 'touch'; from: string } & Touch)
  | { type: 'release'; from: string }
  | { type: 'chat'; from: string; text: string; at: number }
  | { type: 'voice'; from: string; on: boolean }
  | ({ type: 'signal'; from: string } & Signal)
  | { type: 'error'; code: ErrorCode };

export const LIMITS = { name: 20, chat: 500, texture: 16, code: 6, sdp: 6000, candidate: 500, call: 24, peerId: 16 } as const;
export const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

// Control characters and bidirectional overrides never belong in a name or a chat line.
const UNSAFE = /[\u0000-\u0008\u000b-\u001f\u007f-\u009f‎‏‪-‮⁦-⁩]/g;

export function cleanName(value: unknown): string {
  const text = typeof value === 'string' ? value.replace(UNSAFE, '').replace(/\s+/g, ' ').trim() : '';
  return Array.from(text).slice(0, LIMITS.name).join('') || 'Guest';
}

export function cleanChat(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const text = Array.from(value.replace(UNSAFE, '').trim()).slice(0, LIMITS.chat).join('');
  return text || undefined;
}

/** Codes are typed by hand, so accept lower case and stray spaces or dashes, but nothing outside the alphabet. */
export function normalizeCode(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const code = value.toUpperCase().replace(/[\s-]/g, '');
  return code.length === LIMITS.code && [...code].every(char => CODE_ALPHABET.includes(char)) ? code : undefined;
}

function unit(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : undefined;
}

export function parseTouch(raw: Record<string, unknown>): Touch | undefined {
  const { texture, phase, t } = raw;
  const x = unit(raw.x), y = unit(raw.y);
  if (typeof texture !== 'string' || !/^[a-z]{1,16}$/.test(texture)) return undefined;
  if (!TOUCH_PHASES.includes(phase as TouchPhase) || x === undefined || y === undefined) return undefined;
  if (typeof t !== 'number' || !Number.isFinite(t) || t < 0) return undefined;
  return { texture, phase: phase as TouchPhase, x, y, t };
}

export function parseSignal(raw: Record<string, unknown>): Signal | undefined {
  const { call, kind, sdp, candidate, sdpMid, sdpMLineIndex } = raw;
  if (typeof call !== 'string' || !/^[A-Za-z0-9]{1,24}$/.test(call)) return undefined;
  if (!SIGNAL_KINDS.includes(kind as SignalKind)) return undefined;
  if (kind === 'candidate') {
    if (typeof candidate !== 'string' || candidate.length > LIMITS.candidate) return undefined;
    if (sdpMid !== undefined && (typeof sdpMid !== 'string' || sdpMid.length > 32)) return undefined;
    if (typeof sdpMLineIndex !== 'number' || !Number.isInteger(sdpMLineIndex) || sdpMLineIndex < 0 || sdpMLineIndex > 8) return undefined;
    return { call, kind, candidate, ...(sdpMid === undefined ? {} : { sdpMid }), sdpMLineIndex };
  }
  return typeof sdp === 'string' && sdp.length > 0 && sdp.length <= LIMITS.sdp ? { call, kind: kind as SignalKind, sdp } : undefined;
}

/** Rebuilds every message from known fields, so nothing a client adds is ever passed on to a friend. */
export function parseClientMessage(text: string): ClientMessage | undefined {
  let raw: unknown;
  try { raw = JSON.parse(text); } catch { return undefined; }
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return undefined;
  const message = raw as Record<string, unknown>;
  switch (message.type) {
    case 'create': return { type: 'create', name: cleanName(message.name) };
    case 'join': {
      const code = normalizeCode(message.code);
      return code ? { type: 'join', code, name: cleanName(message.name) } : undefined;
    }
    case 'touch': {
      const touch = parseTouch(message);
      return touch ? { type: 'touch', ...touch } : undefined;
    }
    case 'release': return { type: 'release' };
    case 'chat': {
      const chat = cleanChat(message.text);
      return chat ? { type: 'chat', text: chat } : undefined;
    }
    case 'voice': return typeof message.on === 'boolean' ? { type: 'voice', on: message.on } : undefined;
    case 'signal': {
      const signal = parseSignal(message);
      if (!signal) return undefined;
      const to = message.to;
      if (to === undefined) return { type: 'signal', ...signal };
      return typeof to === 'string' && /^[0-9a-f]{1,16}$/.test(to) ? { type: 'signal', to, ...signal } : undefined;
    }
    case 'leave': return { type: 'leave' };
    default: return undefined;
  }
}
