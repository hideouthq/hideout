// Deadlock's own Esc menu, in the hub and in every match, gets one entry while a Hideout session can be stopped: Stop
// for the host, Leave for a guest. Both send the session's stop.

import type { LinkStatus } from './link.ts'
import type { Snapshot } from './wire.ts'

export interface EscEntry {
  label: 'STOP HIDEOUT GAME' | 'LEAVE HIDEOUT GAME'
  /** From a press until the game frame changes, so a second press can't send a second stop. */
  busy: boolean
}

export function viewEscEntry(snapshot: Snapshot, status: LinkStatus, pressed: boolean): EscEntry | null {
  const session = snapshot.game?.session
  if (!session || !session.actions.includes('stop') || status === 'offline' || status === 'outdated') return null
  return { label: session.role === 'host' ? 'STOP HIDEOUT GAME' : 'LEAVE HIDEOUT GAME', busy: pressed }
}
