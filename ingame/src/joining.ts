import type { Action, JoinCard, ModeCard, RestartReason, Snapshot, Step } from './wire.ts'

export type JoiningButton =
  | { kind: 'act'; action: Action; label: string; primary: boolean }
  | { kind: 'confirm'; restart: RestartReason | null; label: string; primary: true }

export interface JoiningView {
  card: JoinCard
  title: string
  line: string
  art: string | null
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

export function viewJoining(snapshot: Snapshot, putAway: JoinCard['phase'] | null): JoiningView | null {
  const card = snapshot.game?.join
  if (!card || card.phase === putAway) return null
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
    steps: card.steps,
    busy: card.steps.length ? null : card.busy,
    box,
    buttons,
  }
}
