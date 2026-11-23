// The overlay's toast stack is reconciled to this on every change, so a toast leaves when the app drops its invite
// (joined, forgotten in any context, expired) without a message saying so.

import type { Friends } from './friends.ts'
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
    }
  | { kind: 'unreached'; key: string; friend: SteamId; name: string; link: string; full: boolean }

/** [hidden] holds keys closed here before the app's state catches up. */
export function wantedToasts(snapshot: Snapshot, friends: Friends, now: number, arrived: Record<string, number>, hidden: ReadonlySet<string>): ToastView[] {
  const views: ToastView[] = []
  for (const invite of snapshot.inbox?.invites ?? []) {
    const name = friends.names[invite.from]
    if (name === undefined || invite.expires <= now || hidden.has(invite.id)) continue
    const start = Math.min(arrived[invite.id] ?? now, now)
    const left = invite.expires > start ? (invite.expires - now) / (invite.expires - start) : 0
    const line = [...invite.picks, `${invite.players[0]} of ${invite.players[1]}`].join(' · ')
    views.push({ kind: 'invite', key: invite.id, name, invite, line, full: views.length === 0, left: Math.max(0, Math.min(1, left)) })
  }
  const host = snapshot.game?.host
  if (host?.link) {
    for (const [id, delivery] of Object.entries(host.deliveries)) {
      const key = `unreached:${id}`
      if (delivery !== 'not-reached' || hidden.has(key)) continue
      views.push({ kind: 'unreached', key, friend: id as SteamId, name: friends.names[id] ?? 'A friend', link: host.link, full: views.length === 0 })
    }
  }
  return views
}
