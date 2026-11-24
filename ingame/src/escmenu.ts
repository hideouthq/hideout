// Deadlock's own Esc menu, in the hub and in every match, gets Hideout's entries while a session can take them: Rejoin
// while the player's Deadlock is out of a running game, then Stop for the host or Leave for a guest.

import type { LinkStatus } from './link.ts'
import type { Action, Snapshot } from './wire.ts'

export interface EscEntry {
  label: 'Rejoin Hideout Game' | 'Stop Hideout Game' | 'Leave Hideout Game'
  action: Action
  /** From a press until the game frame changes, so a second press can't send a second command. */
  busy: boolean
}

export function viewEscEntries(snapshot: Snapshot, status: LinkStatus, pressed: boolean): EscEntry[] {
  const session = snapshot.game?.session
  if (!session || status === 'offline' || status === 'outdated') return []
  const entries: EscEntry[] = []
  if (session.phase === 'notin' && session.actions.includes('play')) entries.push({ label: 'Rejoin Hideout Game', action: 'play', busy: pressed })
  if (session.actions.includes('stop')) entries.push({ label: session.role === 'host' ? 'Stop Hideout Game' : 'Leave Hideout Game', action: 'stop', busy: pressed })
  return entries
}
