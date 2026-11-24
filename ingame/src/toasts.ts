// The overlay's toast stack is reconciled to this on every change, so a toast leaves when the app drops its invite
// (joined, forgotten in any context, expired) without a message saying so.

import type { Friends } from './friends.ts'
import { LEAVE_FIRST, takesInvite } from './games.ts'
import type { InviteCard, Snapshot, SteamId } from './wire.ts'

export type ToastView =
  | {
      kind: 'invite'
      key: string
      /** The friend list's name for the sender. The invite's own claim is never shown. */
      name: string
      invite: InviteCard
      line: string
      full: boolean
      left: number
      pointer: boolean
      /** Join is off while the session takes no join, and this says why. */
      joinable: boolean
      blocked: string | null
    }
  | { kind: 'unreached'; key: string; friend: SteamId; name: string; link: string; full: boolean; pointer: boolean }

/** Playing, in the room or a match, the game holds the mouse. The Esc menu and dashboard screens free it. */
export type Screen = 'playing' | 'escape' | 'dashboard'

/**
 * [hidden] holds keys closed here before the app's state catches up. While playing the toasts' buttons can't be pressed,
 * so they say Esc frees the mouse. Dashboard screens, the games panel among them, sit in another root that can cover the
 * overlay and that it can't see into, so the toasts wait for the player to leave them.
 */
export function wantedToasts(snapshot: Snapshot, friends: Friends, now: number, arrived: Record<string, number>, hidden: ReadonlySet<string>, screen: Screen): ToastView[] {
  const views: ToastView[] = []
  if (screen === 'dashboard') return views
  const pointer = screen === 'escape'
  const joinable = takesInvite(snapshot)
  for (const invite of snapshot.inbox?.invites ?? []) {
    const name = friends.names[invite.from]
    if (name === undefined || invite.expires <= now || hidden.has(invite.id)) continue
    const start = Math.min(arrived[invite.id] ?? now, now)
    const left = invite.expires > start ? (invite.expires - now) / (invite.expires - start) : 0
    const line = [...invite.picks, `${invite.players[0]} of ${invite.players[1]}`].join(' · ')
    views.push({ kind: 'invite', key: invite.id, name, invite, line, full: views.length === 0, left: Math.max(0, Math.min(1, left)), pointer, joinable, blocked: joinable ? null : LEAVE_FIRST })
  }
  const host = snapshot.game?.host
  if (host?.link) {
    const game = tagOf(host.link)
    for (const [id, delivery] of Object.entries(host.deliveries)) {
      const key = `unreached:${game}:${id}`
      if (delivery !== 'not-reached' || hidden.has(key)) continue
      views.push({ kind: 'unreached', key, friend: id as SteamId, name: friends.names[id] ?? 'A friend', link: host.link, full: views.length === 0, pointer })
    }
  }
  return views
}

/** The key closes a toast for one game, and is a panel id, so the game's link goes in as a short FNV-1a tag. */
function tagOf(link: string): string {
  let hash = 0x811c9dc5
  for (let i = 0; i < link.length; i++) hash = Math.imul(hash ^ link.charCodeAt(i), 0x01000193)
  return (hash >>> 0).toString(36)
}
