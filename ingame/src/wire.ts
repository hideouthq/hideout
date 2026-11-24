// The app's InGameWire.kt is the other half of this wire, and both are checked against the golden files in
// ingame/fixtures.

export const PROTOCOL = 1

export type SteamId = string & { readonly __brand: 'SteamId' }
export type ModeId = string & { readonly __brand: 'ModeId' }

/** Written per pack by hideout-link menus into scripts/hideout/config.js. */
export interface Config {
  readonly protocol: number
  readonly port: number
  readonly page: string
  readonly token: string
}

export type Listing = 'invite-only' | 'public'
export type Action = 'stop' | 'restart' | 'play' | 'dismiss' | 'again'
export type StepState = 'done' | 'running' | 'waiting'
export type RestartReason = 'not-set-up' | 'game-updated' | 'menus-did-not-start'
export type Social = 'on' | 'off' | 'other-account' | 'blocked'
export type Delivery = 'sending' | 'delivered' | 'not-reached'
export type ServersState = 'idle' | 'loading' | 'ready' | 'unreachable' | 'unavailable'
export type ServerStatus = 'lobby' | 'match' | 'full'

export interface Words {
  title: string
  body: string
}

export interface Restart {
  reason: RestartReason
  text: string
}

export interface Step {
  label: string
  status: StepState
  note: string | null
  progress: number | null
}

export interface Option {
  value: string
  label: string
  players: number | null
}

export interface Setting {
  key: string
  label: string
  hint: string | null
  default: string
  options: Option[]
}

export interface ModeArt {
  card: string | null
  backdrop: string | null
  render: string | null
  overlay: string | null
  tile: string | null
}

export interface ModeCard {
  id: ModeId
  name: string
  tagline: string
  kind: string | null
  players: [number, number]
  minutes: [number, number] | null
  /** Like "First to {kills} kills": each {key} is the picked option's label of the setting with that key. */
  goal: string | null
  by: string[]
  bytes: number | null
  art: ModeArt
  settings: Setting[]
}

export type HostPhase = 'starting' | 'restart' | 'restarting' | 'entering' | 'ready' | 'notin' | 'ending' | 'failed' | 'ended'

export interface HostCard {
  mode: ModeId
  phase: HostPhase
  steps: Step[]
  words: Words | null
  link: string | null
  players: [number, number] | null
  listing: Listing
  invitees: SteamId[]
  deliveries: Record<string, Delivery>
  actions: Action[]
}

/** 'notin' is a running game the player's Deadlock has left, which Play sends it back into. */
export type SessionPhase = 'starting' | 'in' | 'notin' | 'ending' | 'over'

/** Who the player is in the running session, where they stand in it and what it accepts now. The host is a guest's. */
export interface SessionCard {
  role: 'host' | 'guest'
  mode: string
  phase: SessionPhase
  hostId: SteamId | null
  hostName: string | null
  actions: Action[]
}

export type JoinPhase = 'finding' | 'review' | 'cleanup' | 'progress' | 'stopping' | 'restart' | 'restarting' | 'notin' | 'failed'

export interface JoinCard {
  title: string
  mode: string
  picks: string[]
  capacity: number | null
  hostId: SteamId | null
  hostName: string | null
  phase: JoinPhase
  steps: Step[]
  words: Words | null
  busy: string | null
  restart: Restart | null
  actions: Action[]
}

export interface InviteCard {
  id: string
  /** The sender's claim, never shown: a toast shows only a Steam friend, under the friend list's name. */
  from: SteamId
  mode: string
  picks: string[]
  tile: string | null
  players: [number, number]
  expires: number
}

export interface ServerRow {
  id: string
  host: string | null
  mode: string
  picks: string[]
  tile: string | null
  region: string
  players: [number, number]
  ping: number | null
  status: ServerStatus
}

/**
 * [epoch] is drawn once per Hideout start, and every command carries the newest heard, so one sent to an earlier start
 * never runs. [supportId] is set only while social is blocked: the player's own SteamID64, for support to reset this PC.
 */
export type Hello = { t: 'hello'; protocol: number; epoch: string; social: Social; supportId: SteamId | null }
export type Catalog = { t: 'catalog'; modes: ModeCard[] }
export type Game = { t: 'game'; host: HostCard | null; join: JoinCard | null; session: SessionCard | null; restartAhead: Restart | null }
export type Inbox = { t: 'inbox'; invites: InviteCard[] }
export type Servers = { t: 'servers'; state: ServersState; rows: ServerRow[] }
export type Frame = Hello | Catalog | Game | Inbox | Servers | { t: 'alive' }

export type Command =
  | { k: 'host'; mode: ModeId; picks: Record<string, string>; listing: Listing; invite: SteamId[]; restart: RestartReason | null }
  | { k: 'act'; action: Action }
  | { k: 'confirm'; restart: RestartReason | null }
  | { k: 'invite'; to: SteamId[] }
  | { k: 'listing'; listing: Listing }
  | { k: 'join'; invite: string }
  | { k: 'join'; server: string }
  | { k: 'forget'; invite: string }
  | { k: 'servers' }
  | { k: 'hello'; self: SteamId | null }

/** 'stale' is a command Hideout refused because it restarted since this script heard its hello. */
export type CommandResult = 'accepted' | 'busy' | 'gone' | 'invalid' | 'unavailable' | 'outdated' | 'stale' | 'unreachable'

const RESULTS: CommandResult[] = ['accepted', 'busy', 'gone', 'invalid', 'unavailable', 'outdated', 'stale']

/** An image's content width is in screen pixels, which is [scale] times the PNG's width when the screen isn't 1080 lines tall. */
export function resultOfWidth(width: number, scale = 1): CommandResult {
  return RESULTS[Math.round(width / scale) - 1] ?? 'unreachable'
}

export interface Snapshot {
  hello: Hello | null
  catalog: Catalog | null
  game: Game | null
  inbox: Inbox | null
  servers: Servers | null
}

export const EMPTY: Snapshot = { hello: null, catalog: null, game: null, inbox: null, servers: null }

export function withFrame(snapshot: Snapshot, frame: Frame): Snapshot {
  switch (frame.t) {
    case 'hello':
      return { ...snapshot, hello: frame }
    case 'catalog':
      return { ...snapshot, catalog: frame }
    case 'game':
      return { ...snapshot, game: frame }
    case 'inbox':
      return { ...snapshot, inbox: frame }
    case 'servers':
      return { ...snapshot, servers: frame }
    case 'alive':
      return snapshot
  }
}

class Malformed extends Error {}

type Json = unknown

function obj(v: Json): Record<string, Json> {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) throw new Malformed()
  return v as Record<string, Json>
}
function str(v: Json): string {
  if (typeof v !== 'string') throw new Malformed()
  return v
}
function num(v: Json): number {
  if (typeof v !== 'number' || !isFinite(v)) throw new Malformed()
  return v
}
function list<T>(v: Json, item: (v: Json) => T): T[] {
  if (!Array.isArray(v)) throw new Malformed()
  return v.map(item)
}
function opt<T>(v: Json, item: (v: Json) => T): T | null {
  return v === null || v === undefined ? null : item(v)
}
function oneOf<T extends string>(values: readonly T[]) {
  return (v: Json): T => {
    if (!values.includes(v as T)) throw new Malformed()
    return v as T
  }
}
function record<T>(v: Json, item: (v: Json) => T): Record<string, T> {
  const out: Record<string, T> = {}
  for (const [key, value] of Object.entries(obj(v))) out[key] = item(value)
  return out
}
function pair(v: Json): [number, number] {
  const [a, b] = list(v, num)
  if (a === undefined || b === undefined) throw new Malformed()
  return [a, b]
}

const action = oneOf<Action>(['stop', 'restart', 'play', 'dismiss', 'again'])
const listing = oneOf<Listing>(['invite-only', 'public'])

function words(v: Json): Words {
  const o = obj(v)
  return { title: str(o.title), body: str(o.body) }
}

function restart(v: Json): Restart {
  const o = obj(v)
  return { reason: oneOf<RestartReason>(['not-set-up', 'game-updated', 'menus-did-not-start'])(o.reason), text: str(o.text) }
}

function step(v: Json): Step {
  const o = obj(v)
  return { label: str(o.label), status: oneOf<StepState>(['done', 'running', 'waiting'])(o.status), note: opt(o.note, str), progress: opt(o.progress, num) }
}

function mode(v: Json): ModeCard {
  const o = obj(v)
  const art = obj(o.art)
  return {
    id: str(o.id) as ModeId,
    name: str(o.name),
    tagline: str(o.tagline),
    kind: opt(o.kind, str),
    players: pair(o.players),
    minutes: opt(o.minutes, pair),
    goal: opt(o.goal, str),
    by: list(o.by, str),
    bytes: opt(o.bytes, num),
    art: { card: opt(art.card, str), backdrop: opt(art.backdrop, str), render: opt(art.render, str), overlay: opt(art.overlay, str), tile: opt(art.tile, str) },
    settings: list(o.settings, (s) => {
      const so = obj(s)
      return {
        key: str(so.key),
        label: str(so.label),
        hint: opt(so.hint, str),
        default: str(so.default),
        options: list(so.options, (p) => {
          const po = obj(p)
          return { value: str(po.value), label: str(po.label), players: opt(po.players, num) }
        }),
      }
    }),
  }
}

function host(v: Json): HostCard {
  const o = obj(v)
  return {
    mode: str(o.mode) as ModeId,
    phase: oneOf<HostPhase>(['starting', 'restart', 'restarting', 'entering', 'ready', 'notin', 'ending', 'failed', 'ended'])(o.phase),
    steps: list(o.steps, step),
    words: opt(o.words, words),
    link: opt(o.link, str),
    players: opt(o.players, pair),
    listing: listing(o.listing),
    invitees: list(o.invitees, (i) => str(i) as SteamId),
    deliveries: record(o.deliveries, oneOf<Delivery>(['sending', 'delivered', 'not-reached'])),
    actions: list(o.actions, action),
  }
}

function join(v: Json): JoinCard {
  const o = obj(v)
  return {
    title: str(o.title),
    mode: str(o.mode),
    picks: list(o.picks, str),
    capacity: opt(o.capacity, num),
    hostId: opt(o.hostId, (i) => str(i) as SteamId),
    hostName: opt(o.hostName, str),
    phase: oneOf<JoinPhase>(['finding', 'review', 'cleanup', 'progress', 'stopping', 'restart', 'restarting', 'notin', 'failed'])(o.phase),
    steps: list(o.steps, step),
    words: opt(o.words, words),
    busy: opt(o.busy, str),
    restart: opt(o.restart, restart),
    actions: list(o.actions, action),
  }
}

function session(v: Json): SessionCard {
  const o = obj(v)
  return {
    role: oneOf<SessionCard['role']>(['host', 'guest'])(o.role),
    mode: str(o.mode),
    phase: oneOf<SessionPhase>(['starting', 'in', 'notin', 'ending', 'over'])(o.phase),
    hostId: opt(o.hostId, (i) => str(i) as SteamId),
    hostName: opt(o.hostName, str),
    actions: list(o.actions, action),
  }
}

function invite(v: Json): InviteCard {
  const o = obj(v)
  return { id: str(o.id), from: str(o.from) as SteamId, mode: str(o.mode), picks: list(o.picks, str), tile: opt(o.tile, str), players: pair(o.players), expires: num(o.expires) }
}

function server(v: Json): ServerRow {
  const o = obj(v)
  return {
    id: str(o.id),
    host: opt(o.host, str),
    mode: str(o.mode),
    picks: list(o.picks, str),
    tile: opt(o.tile, str),
    region: str(o.region),
    players: pair(o.players),
    ping: opt(o.ping, num),
    status: oneOf<ServerStatus>(['lobby', 'match', 'full'])(o.status),
  }
}

export function decodeFrame(text: string): Frame | null {
  try {
    const o = obj(JSON.parse(text))
    switch (o.t) {
      case 'hello':
        return {
          t: 'hello',
          protocol: num(o.protocol),
          epoch: str(o.epoch),
          social: oneOf<Social>(['on', 'off', 'other-account', 'blocked'])(o.social),
          supportId: opt(o.supportId, (v) => str(v) as SteamId),
        }
      case 'catalog':
        return { t: 'catalog', modes: list(o.modes, mode) }
      case 'game':
        return { t: 'game', host: opt(o.host, host), join: opt(o.join, join), session: opt(o.session, session), restartAhead: opt(o.restartAhead, restart) }
      case 'inbox':
        return { t: 'inbox', invites: list(o.invites, invite) }
      case 'servers':
        return { t: 'servers', state: oneOf<ServersState>(['idle', 'loading', 'ready', 'unreachable', 'unavailable'])(o.state), rows: list(o.rows, server) }
      case 'alive':
        return { t: 'alive' }
      default:
        return null
    }
  } catch (e) {
    if (e instanceof Malformed || e instanceof SyntaxError) return null
    throw e
  }
}
