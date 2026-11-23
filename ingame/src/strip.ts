// The hosting strip lets the host stop their game from any menu, the in-match Esc menu included, where the games popup
// can't open.

import type { LinkStatus } from './link.ts'
import type { HostCard, Snapshot } from './wire.ts'

/** 'esc' stands in for Stop while the game holds the mouse, as it does in the room and in a match until Esc frees it. */
export type StripButton = 'stop' | 'spinner' | 'esc'

export type StripView =
  | { kind: 'hosting'; art: string | null; mode: string; players: string | null; button: StripButton }
  /** Starting, restarting, out of the game or ending: the host card's own words say which. */
  | { kind: 'status'; art: string | null; mode: string; words: string; button: StripButton }

const HIDDEN: HostCard['phase'][] = ['failed', 'ended']

/** [pressed] is true from a Stop press until the game frame changes. [pointer] is whether the player has a mouse pointer. */
export function viewStrip(snapshot: Snapshot, status: LinkStatus, pressed: boolean, pointer: boolean): StripView | null {
  const host = snapshot.game?.host
  if (!host || HIDDEN.includes(host.phase) || status === 'offline' || status === 'outdated') return null
  const mode = snapshot.catalog?.modes.find((m) => m.id === host.mode)
  const art = mode ? (mode.art.tile ?? mode.art.card) : null
  const name = mode?.name ?? 'your game'
  const button: StripButton = !host.actions.includes('stop') || pressed ? 'spinner' : pointer ? 'stop' : 'esc'
  if (host.phase === 'ready') {
    return { kind: 'hosting', art, mode: name, players: host.players ? `${host.players[0]} of ${host.players[1]}` : null, button }
  }
  return { kind: 'status', art, mode: name, words: host.words?.title ?? '', button }
}
