import type { Friends } from './friends.ts'
import type { LinkStatus } from './link.ts'
import type { Action, JoinCard, ModeCard, RestartReason, Snapshot, Step, SteamId } from './wire.ts'

export type JoiningButton =
  | { kind: 'act'; action: Action; label: string; primary: boolean }
  | { kind: 'confirm'; restart: RestartReason | null; label: string; primary: true }

export interface JoiningView {
  card: JoinCard
  title: string
  line: string
  art: string | null
  host: { id: SteamId | null; text: string } | null
  steps: Step[]
  busy: string | null
  box: { tone: 'failed' | 'note'; title: string; body: string } | null
  buttons: JoiningButton[]
}

const LABELS: Record<Action, { label: string; primary: boolean }> = {
  stop: { label: 'Cancel', primary: false },
  dismiss: { label: 'Close', primary: false },
  again: { label: 'Try again', primary: true },
  restart: { label: 'Restart Deadlock', primary: true },
  play: { label: 'Play', primary: true },
}

function artFor(name: string, modes: ModeCard[]): string | null {
  const mode = modes.find((m) => m.name === name)
  return mode ? (mode.art.backdrop ?? mode.art.card ?? mode.art.tile) : null
}

/** Like the toasts, a host who is a friend goes by the friend list's name rather than the one the offer claims. */
function hostOf(card: JoinCard, friends: Friends): JoiningView['host'] {
  const name = (card.hostId && friends.names[card.hostId]) || card.hostName
  return name ? { id: card.hostId, text: `${name}’s game` } : null
}

/** Offline, the snapshot's join is stale and every button needs the app, so the layer would trap the player. */
export function viewJoining(snapshot: Snapshot, putAway: JoinCard['phase'] | null, status: LinkStatus, friends: Friends): JoiningView | null {
  const card = snapshot.game?.join
  if (!card || card.phase === putAway || status === 'offline') return null
  const words = card.words
  const failed = card.phase === 'failed'
  const buttons: JoiningButton[] = card.actions.map((action) => ({ kind: 'act', action, ...LABELS[action] }))
  if (card.phase === 'review') buttons.push({ kind: 'confirm', restart: card.restart?.reason ?? null, label: 'Join', primary: true })
  buttons.sort((a, b) => Number(a.primary) - Number(b.primary))
  let box: JoiningView['box'] = null
  if (failed && words) box = { tone: 'failed', title: words.title, body: words.body }
  else if (card.restart) box = { tone: 'note', title: card.title, body: card.restart.text }
  else if (words && !card.steps.length) box = { tone: 'note', title: words.title, body: words.body }
  return {
    card,
    title: failed || !words ? card.title : words.title,
    line: [...card.picks, card.capacity ? `${card.capacity} players` : null].filter(Boolean).join(' · '),
    art: artFor(card.mode, snapshot.catalog?.modes ?? []),
    host: hostOf(card, friends),
    steps: card.steps,
    busy: card.steps.length ? null : card.busy,
    box,
    buttons,
  }
}
