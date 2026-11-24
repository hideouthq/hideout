// The popup holds only what the player chooses before Play. Everything after Play is the game topic's.

import type { Friend, Friends } from './friends.ts'
import type { LinkStatus } from './link.ts'
import type { Action, Command, CommandResult, HostCard, Listing, ModeCard, ModeId, ServerRow, ServersState, Setting, Snapshot, SteamId } from './wire.ts'

export type Tab = 'games' | 'servers'

export interface Draft {
  mode: ModeId | null
  picks: Record<string, string>
  listing: Listing
  invitees: SteamId[]
}

export type Press = 'play' | 'invite' | 'listing' | 'act' | 'join'

export type Request =
  | { kind: 'idle' }
  | { kind: 'sending'; what: Press }
  | { kind: 'refused'; what: Press; result: CommandResult }

export interface GamesState {
  tab: Tab
  draft: Draft
  request: Request
  serversAnswer: CommandResult | null
}

export type GamesEvent =
  | { kind: 'tab'; tab: Tab }
  | { kind: 'mode'; mode: ModeId }
  | { kind: 'pick'; key: string; value: string }
  | { kind: 'listing'; listing: Listing }
  | { kind: 'friend'; id: SteamId }
  | { kind: 'play' }
  | { kind: 'act'; action: Action }
  | { kind: 'join'; server: string }
  | { kind: 'settled'; what: Press; result: CommandResult }
  | { kind: 'servers-answered'; result: CommandResult }
  | { kind: 'seen' }

export type GamesEffect =
  | { kind: 'send'; what: Press; command: Command }
  | { kind: 'copy-and-chat'; link: string; friend: SteamId }

export function initialGames(tab: Tab): GamesState {
  return { tab, draft: { mode: null, picks: {}, listing: 'invite-only', invitees: [] }, request: { kind: 'idle' }, serversAnswer: null }
}

function runningHost(snapshot: Snapshot): HostCard | null {
  const host = snapshot.game?.host ?? null
  return host && host.phase !== 'ended' ? host : null
}

type Listed = { mode: ModeCard; soon: boolean }

/** Playable modes first, then the ones not out yet. */
function listed(snapshot: Snapshot): Listed[] {
  const catalog = snapshot.catalog
  if (!catalog) return []
  return [...catalog.modes.map((mode) => ({ mode, soon: false })), ...catalog.soon.map((mode) => ({ mode, soon: true }))]
}

function selectedMode(state: GamesState, snapshot: Snapshot): Listed | null {
  const modes = listed(snapshot)
  return modes.find((m) => m.mode.id === state.draft.mode) ?? modes[0] ?? null
}

function pickOf(setting: Setting, picks: Record<string, string>): string {
  const picked = picks[setting.key]
  return picked !== undefined && setting.options.some((o) => o.value === picked) ? picked : setting.default
}

export function reduceGames(state: GamesState, event: GamesEvent, snapshot: Snapshot, friends: Friends): [GamesState, GamesEffect[]] {
  const host = runningHost(snapshot)
  const draft = state.draft
  const sending = (what: Press, command: Command): [GamesState, GamesEffect[]] => [
    { ...state, request: { kind: 'sending', what } },
    [{ kind: 'send', what, command }],
  ]
  switch (event.kind) {
    case 'tab':
      return [{ ...state, tab: event.tab, request: state.request.kind === 'refused' ? { kind: 'idle' } : state.request }, []]
    case 'mode':
      return [{ ...state, draft: { ...draft, mode: event.mode, picks: {} }, request: { kind: 'idle' } }, []]
    case 'pick':
      return [{ ...state, draft: { ...draft, picks: { ...draft.picks, [event.key]: event.value } } }, []]
    case 'listing':
      if (host) return host.listing === event.listing ? [state, []] : sending('listing', { k: 'listing', listing: event.listing })
      return [{ ...state, draft: { ...draft, listing: event.listing } }, []]
    case 'friend': {
      const inMatch = friends.online.some((f) => f.id === event.id && f.inMatch)
      const button = friendButton(event.id, inMatch, state, snapshot)
      if (button === 'in-match') return [state, []]
      if (host) {
        if (button === 'invite') return sending('invite', { k: 'invite', to: [event.id] })
        return button === 'copy-link' && host.link ? [state, [{ kind: 'copy-and-chat', link: host.link, friend: event.id }]] : [state, []]
      }
      const invitees = draft.invitees.includes(event.id) ? draft.invitees.filter((id) => id !== event.id) : [...draft.invitees, event.id]
      return [{ ...state, draft: { ...draft, invitees } }, []]
    }
    case 'play': {
      const selected = selectedMode(state, snapshot)
      if (!selected || selected.soon || host || state.request.kind === 'sending') return [state, []]
      const mode = selected.mode
      const picks: Record<string, string> = {}
      for (const setting of mode.settings) picks[setting.key] = pickOf(setting, draft.picks)
      return sending('play', {
        k: 'host',
        mode: mode.id,
        picks,
        listing: draft.listing,
        invite: draftInvitees(state, snapshot),
        restart: snapshot.game?.restartAhead?.reason ?? null,
      })
    }
    case 'act':
      return sending('act', { k: 'act', action: event.action })
    case 'join':
      return state.request.kind === 'sending' ? [state, []] : sending('join', { k: 'join', server: event.server })
    case 'settled':
      if (event.result !== 'accepted') return [{ ...state, request: { kind: 'refused', what: event.what, result: event.result } }, []]
      // Play stays Starting until the game topic shows the hosted game, so the button never flashes back.
      if (event.what === 'play' && !host) return [state, []]
      return [{ ...state, request: { kind: 'idle' } }, []]
    case 'servers-answered':
      return [{ ...state, serversAnswer: event.result }, []]
    case 'seen': {
      const request = state.request
      if (request.kind === 'sending' && request.what === 'play' && host) return [{ ...state, request: { kind: 'idle' } }, []]
      // The answer to a join can come after the deadline, so the joining layer showing it says the press went through.
      if (request.kind === 'refused' && request.what === 'join' && snapshot.game?.join) return [{ ...state, request: { kind: 'idle' } }, []]
      return [state, []]
    }
  }
}

export type FriendButton = 'invite' | 'invited' | 'copy-link' | 'in-match' | 'off'

export type PlayButton =
  | { kind: 'play'; label: string; hint: string }
  | { kind: 'busy'; label: string; hint: string }
  | { kind: 'act'; label: string; action: Action; hint: string }
  | { kind: 'blocked'; label: string; hint: string }

export interface ModeRow {
  mode: ModeCard
  selected: boolean
  meta: string
  soon: boolean
}

export interface SettingRow {
  setting: Setting
  value: string
}

export interface ServerLine {
  row: ServerRow
  name: string
  sub: string
  region: string
  fill: number
  ping: { text: string; tone: 'good' | 'fair' | 'poor' } | null
  status: string
  joinable: boolean
}

export interface ServersView {
  state: ServersState
  lines: ServerLine[]
  note: string | null
}

/** What the list shows while it has no games: a spinner while Hideout answers, or why it can't. */
export type EmptyList = { kind: 'loading' } | { kind: 'note'; title: string; body: string }

export interface GamesView {
  tab: Tab
  counts: { games: number; servers: number | null }
  servers: ServersView
  link: LinkStatus
  rows: ModeRow[]
  empty: EmptyList | null
  selected: ModeCard | null
  /** The selected mode isn't out yet. */
  soon: boolean
  byline: string
  chips: { icon: 'kind' | 'players' | 'time' | 'goal'; text: string }[]
  settings: SettingRow[]
  listing: Listing
  listingLine: string
  friends: { friend: Friend; button: FriendButton }[]
  blocked: { text: string; id: SteamId } | null
  offline: number
  play: PlayButton
}

const KINDS: Record<string, string> = { duel: 'Duel', teams: 'Teams', 'free-for-all': 'Free-for-all', 'co-op': 'Co-op' }

function playersText([least, most]: [number, number]): string {
  return least === most ? `${most} players` : `${least}–${most} players`
}

function sizeText(bytes: number): string {
  if (bytes < 1e6) return `${Math.max(1, Math.round(bytes / 1e3))} KB`
  const mb = bytes / 1e6
  return `${mb < 10 ? Math.round(mb * 10) / 10 : Math.round(mb)} MB`
}

function goalText(goal: string, settings: Setting[], picks: Record<string, string>): string {
  return goal.replace(/\{([^{}]+)\}/g, (raw, key: string) => {
    const setting = settings.find((s) => s.key === key)
    const value = setting && pickOf(setting, picks)
    return setting?.options.find((o) => o.value === value)?.label ?? raw
  })
}

function chipsOf(mode: ModeCard, picks: Record<string, string>): GamesView['chips'] {
  const chips: GamesView['chips'] = []
  const kind = mode.kind && KINDS[mode.kind]
  if (kind) chips.push({ icon: 'kind', text: kind })
  chips.push({ icon: 'players', text: playersText(mode.players) })
  const minutes = mode.minutes
  if (minutes) chips.push({ icon: 'time', text: minutes[0] === minutes[1] ? `~${minutes[0]} min` : `${minutes[0]}–${minutes[1]} min` })
  if (mode.goal) chips.push({ icon: 'goal', text: goalText(mode.goal, mode.settings, picks) })
  return chips
}

function metaLine(mode: ModeCard): string {
  const kind = mode.kind && KINDS[mode.kind]
  return [kind, playersText(mode.players)].filter(Boolean).join(' · ')
}

const ACTION_LABELS: Record<Action, string> = { stop: 'Stop game', restart: 'Restart Deadlock', play: 'Play', again: 'Try again', dismiss: 'Close' }

const REFUSALS: Record<CommandResult, string> = {
  accepted: '',
  busy: 'Finish the game that’s running first.',
  gone: 'That’s no longer there. Pick another.',
  invalid: 'Hideout couldn’t read that. Try again.',
  unavailable: 'This Hideout can’t do that yet.',
  outdated: 'Restart Deadlock to update Hideout games.',
  unreachable: 'Hideout didn’t answer. Is it still open?',
}

export function refusalText(result: CommandResult): string {
  return REFUSALS[result]
}

function stepLine(host: HostCard): string | null {
  const step = host.steps.find((s) => s.status === 'running')
  return step ? [step.label, step.note].filter(Boolean).join(' · ') : null
}

/** A PC the site refused sends no invites, so nothing drafted before Play goes out. */
function draftInvitees(state: GamesState, snapshot: Snapshot): SteamId[] {
  return snapshot.hello?.social === 'blocked' ? [] : state.draft.invitees
}

function blockedLine(snapshot: Snapshot): GamesView['blocked'] {
  const hello = snapshot.hello
  if (hello?.social !== 'blocked' || !hello.supportId) return null
  return { text: `Invites are blocked on this PC. Send support this ID to reset it: ${hello.supportId}`, id: hello.supportId }
}

function inviteHint(state: GamesState, snapshot: Snapshot): string {
  if (state.draft.invitees.length && snapshot.hello?.social === 'other-account') return 'Open Hideout with the Steam account playing Deadlock to send invites.'
  const n = draftInvitees(state, snapshot).length
  if (n) return `${n} ${n === 1 ? 'friend gets' : 'friends get'} an invite when your game is ready.`
  return `Your PC hosts this game. ${state.draft.listing === 'public' ? 'Anyone in Servers can join.' : 'Invite friends here or share the code.'}`
}

const OFFLINE = { title: 'Open Hideout to play', body: 'Hideout games run from the Hideout app on this PC.' }
const OUTDATED = { title: 'Restart Deadlock', body: REFUSALS.outdated }

function emptyList(link: LinkStatus): EmptyList {
  if (link === 'offline') return { kind: 'note', ...OFFLINE }
  if (link === 'outdated') return { kind: 'note', ...OUTDATED }
  return { kind: 'loading' }
}

function playButton(state: GamesState, snapshot: Snapshot, link: LinkStatus, selected: Listed | null): PlayButton {
  if (link === 'offline') return { kind: 'blocked', label: OFFLINE.title, hint: OFFLINE.body }
  if (link === 'outdated') return { kind: 'blocked', label: OUTDATED.title, hint: OUTDATED.body }
  if (link === 'connecting' && !snapshot.catalog) return { kind: 'busy', label: 'Reaching Hideout…', hint: '' }
  const host = runningHost(snapshot)
  if (host) {
    const words = host.words
    switch (host.phase) {
      case 'starting':
      case 'restarting':
      case 'entering':
        return { kind: 'busy', label: 'Starting…', hint: stepLine(host) ?? words?.body ?? '' }
      case 'ending':
        if (!host.actions.length) return { kind: 'busy', label: 'Stopping…', hint: words?.body ?? '' }
    }
    const action = host.actions[0]
    if (!action) return { kind: 'busy', label: 'Starting…', hint: words?.body ?? '' }
    return { kind: 'act', label: ACTION_LABELS[action], action, hint: (host.phase === 'failed' ? words?.title : words?.body) ?? '' }
  }
  const join = snapshot.game?.join
  const mode = selected?.mode ?? null
  const label = mode ? `Play ${mode.name}` : 'Play'
  if (join) return { kind: 'blocked', label, hint: join.title }
  if (selected?.soon) return { kind: 'blocked', label: 'Coming soon', hint: `${selected.mode.name} isn’t out yet. It comes to Hideout in an update.` }
  if (state.request.kind === 'sending' && state.request.what === 'play') return { kind: 'busy', label: 'Starting…', hint: '' }
  if (!mode) return { kind: 'blocked', label, hint: '' }
  if (state.request.kind === 'refused' && state.request.what === 'play') return { kind: 'play', label, hint: REFUSALS[state.request.result] }
  return { kind: 'play', label, hint: snapshot.game?.restartAhead?.text ?? inviteHint(state, snapshot) }
}

function friendButton(id: SteamId, inMatch: boolean, state: GamesState, snapshot: Snapshot): FriendButton {
  const button = inviteButton(id, state, snapshot)
  return inMatch && button !== 'invited' ? 'in-match' : button
}

function inviteButton(id: SteamId, state: GamesState, snapshot: Snapshot): FriendButton {
  const host = runningHost(snapshot)
  const blocked = snapshot.hello?.social === 'blocked'
  if (!host) return blocked ? 'off' : state.draft.invitees.includes(id) ? 'invited' : 'invite'
  if (blocked || host.deliveries[id] === 'not-reached') return host.link ? 'copy-link' : 'off'
  if (host.invitees.includes(id)) return 'invited'
  if (snapshot.hello?.social === 'on') return 'invite'
  return host.link ? 'copy-link' : 'off'
}

const REGIONS: Record<string, string> = {
  ams: 'Amsterdam', blr: 'Bangalore', dbi: 'Dubai', den: 'Denver', dfw: 'Dallas', fra: 'Frankfurt', hkg: 'Hong Kong',
  hnl: 'Honolulu', jnb: 'Johannesburg', lax: 'Los Angeles', lhr: 'London', mad: 'Madrid', mia: 'Miami', nai: 'Nairobi',
  nyc: 'New York', ord: 'Chicago', par: 'Paris', sao: 'São Paulo', sea: 'Seattle', sfo: 'San Francisco', sin: 'Singapore',
  syd: 'Sydney', tok: 'Tokyo', tor: 'Toronto', waw: 'Warsaw',
}

const SERVER_STATUS: Record<ServerRow['status'], string> = { lobby: 'In the lobby', match: 'In a match', full: 'Full' }

const SERVER_NOTES: Record<ServersState, string | null> = {
  idle: null,
  loading: null,
  ready: 'No public games right now. Choose Public before you play to list yours here.',
  unreachable: 'Hideout couldn’t reach the server list. It tries again in a moment.',
  unavailable: 'This Hideout can’t list servers.',
}

function serverLine(row: ServerRow): ServerLine {
  const [now, most] = row.players
  return {
    row,
    name: row.host ? `${row.host}’s ${row.mode}` : row.mode,
    sub: [row.mode, ...row.picks, row.host ? `Hosted by ${row.host}` : 'Hideout server'].join(' · '),
    region: REGIONS[row.region] ?? row.region.toUpperCase(),
    fill: most > 0 ? Math.min(1, now / most) : 0,
    ping: row.ping === null ? null : { text: `${row.ping} ms`, tone: row.ping < 60 ? 'good' : row.ping < 100 ? 'fair' : 'poor' },
    status: SERVER_STATUS[row.status],
    joinable: row.status !== 'full',
  }
}

export function viewServers(snapshot: Snapshot, answer: CommandResult | null = null): ServersView {
  const servers = snapshot.servers
  if (!servers) {
    if (answer === 'unavailable') return { state: 'unavailable', lines: [], note: SERVER_NOTES.unavailable }
    return answer && answer !== 'accepted' ? { state: 'unreachable', lines: [], note: REFUSALS[answer] } : { state: 'idle', lines: [], note: null }
  }
  const lines = servers.rows.map(serverLine)
  return { state: servers.state, lines, note: lines.length ? null : SERVER_NOTES[servers.state] }
}

export function viewGames(state: GamesState, snapshot: Snapshot, link: LinkStatus, friends: Friends, me: string): GamesView {
  const modes = listed(snapshot)
  const choice = selectedMode(state, snapshot)
  const selected = choice?.mode ?? null
  const host = runningHost(snapshot)
  const listing = host ? host.listing : state.draft.listing
  const servers = viewServers(snapshot, state.serversAnswer)
  return {
    tab: state.tab,
    counts: { games: snapshot.catalog?.modes.length ?? 0, servers: snapshot.servers?.state === 'ready' ? servers.lines.length : null },
    servers,
    link,
    rows: modes.map(({ mode, soon }) => ({ mode, selected: mode === selected, meta: metaLine(mode), soon })),
    empty: modes.length ? null : emptyList(link),
    selected,
    soon: choice?.soon ?? false,
    byline: selected ? [`by ${selected.by.join(', ')}`, selected.bytes === null ? null : sizeText(selected.bytes)].filter(Boolean).join(' · ') : '',
    chips: selected ? chipsOf(selected, state.draft.picks) : [],
    settings: selected ? selected.settings.map((setting) => ({ setting, value: pickOf(setting, state.draft.picks) })) : [],
    listing,
    listingLine:
      listing === 'public'
        ? me && selected
          ? `Listed in Servers as “${me}’s ${selected.name}”`
          : 'Listed in Servers for anyone to join'
        : 'Only friends you invite, or anyone with the code',
    friends: friends.online.map((friend) => ({ friend, button: friendButton(friend.id, friend.inMatch, state, snapshot) })),
    blocked: blockedLine(snapshot),
    offline: friends.offline,
    play: playButton(state, snapshot, link, choice),
  }
}
