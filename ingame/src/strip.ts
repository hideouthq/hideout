// The strip shows the player their Hideout game in the main-menu room and acts on it from the room's own screens: the
// host's game while it runs, and for host and guest alike a running game their Deadlock has left, with Rejoin. The Esc
// menu, in the room and in a match, has its own entries for that (escmenu.ts).

import type { Friends } from './friends.ts'
import type { LinkStatus } from './link.ts'
import type { Action, HostCard, SessionCard, Snapshot } from './wire.ts'

export interface StripAct {
  action: Action
  label: 'Rejoin' | 'Stop' | 'Leave'
  primary: boolean
}

/** 'esc' stands in for the buttons while the game holds the mouse, as it does in the room and in a match until Esc frees it. */
export type StripButtons = { kind: 'acts'; acts: StripAct[] } | { kind: 'spinner' } | { kind: 'esc'; words: string }

/** [line] is runs of text, bold or not. [sub] is a second, quieter line. */
export interface StripView {
  art: string | null
  line: [string, boolean][]
  sub: string | null
  buttons: StripButtons
}

const HIDDEN: HostCard['phase'][] = ['failed', 'ended']

/**
 * [pressed] is true from a press until the game frame changes. [pointer] is whether the player has a mouse pointer. A
 * guest's strip is only for a game they left: while they join, the joining dialog speaks for the session.
 */
export function viewStrip(snapshot: Snapshot, status: LinkStatus, pressed: boolean, pointer: boolean, friends: Friends): StripView | null {
  if (status === 'offline' || status === 'outdated') return null
  const session = snapshot.game?.session
  const host = snapshot.game?.host
  if (host && !HIDDEN.includes(host.phase)) return hostStrip(snapshot, host, pressed, pointer)
  if (session?.role === 'guest' && session.phase === 'notin') return guestStrip(snapshot, session, pressed, pointer, friends)
  return null
}

function hostStrip(snapshot: Snapshot, host: HostCard, pressed: boolean, pointer: boolean): StripView {
  const mode = snapshot.catalog?.modes.find((m) => m.id === host.mode)
  const art = mode ? (mode.art.tile ?? mode.art.card) : null
  const name = mode?.name ?? 'your game'
  if (host.phase === 'notin') {
    return { art, line: [[mode?.name ?? 'Your game', true], ['is still running', false]], sub: inIt(host), buttons: outButtons(host.actions, 'Stop', pressed, pointer) }
  }
  const buttons: StripButtons = !host.actions.includes('stop') || pressed ? { kind: 'spinner' } : pointer ? { kind: 'acts', acts: [{ action: 'stop', label: 'Stop', primary: false }] } : { kind: 'esc', words: 'to stop' }
  if (host.phase === 'ready') {
    const players = host.players ? ([[`· ${host.players[0]} of ${host.players[1]}`, false]] as [string, boolean][]) : []
    return { art, line: [['Hosting', false], [name, true], ...players], sub: null, buttons }
  }
  return { art, line: [[name, true], [`· ${host.words?.title ?? ''}`, false]], sub: null, buttons }
}

/** The host card counts the host, who is out of the match here. */
function inIt(host: HostCard): string | null {
  if (!host.players) return null
  const others = Math.max(0, host.players[0] - 1)
  return others ? `${others} ${others === 1 ? 'player' : 'players'} in it` : 'Nobody in it right now'
}

/** Like the joining banner, a host who is a friend goes by the friend list's name. */
function guestStrip(snapshot: Snapshot, session: SessionCard, pressed: boolean, pointer: boolean, friends: Friends): StripView {
  const mode = snapshot.catalog?.modes.find((m) => m.name === session.mode)
  const art = mode ? (mode.art.tile ?? mode.art.card) : null
  const hostName = (session.hostId && friends.names[session.hostId]) || session.hostName
  const game = hostName ? `${hostName}’s ${session.mode}` : session.mode
  return { art, line: [['You left', false], [game, true]], sub: null, buttons: outButtons(session.actions, 'Leave', pressed, pointer) }
}

function outButtons(actions: Action[], end: 'Stop' | 'Leave', pressed: boolean, pointer: boolean): StripButtons {
  if (pressed) return { kind: 'spinner' }
  const rejoin = actions.includes('play')
  if (!pointer) return { kind: 'esc', words: rejoin ? 'to rejoin' : `to ${end.toLowerCase()}` }
  const out: StripAct[] = []
  if (actions.includes('stop')) out.push({ action: 'stop', label: end, primary: false })
  if (rejoin) out.push({ action: 'play', label: 'Rejoin', primary: true })
  return { kind: 'acts', acts: out }
}
