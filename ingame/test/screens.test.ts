import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { type Friends, friendsOf } from '../src/friends.ts'
import { type GamesEvent, type GamesState, initialGames, reduceGames, viewGames, viewServers } from '../src/games.ts'
import { viewJoining } from '../src/joining.ts'
import { wantedToasts } from '../src/toasts.ts'
import { EMPTY, type Frame, type Snapshot, type SteamId, decodeFrame, withFrame } from '../src/wire.ts'

const frame = (name: string) => decodeFrame(readFileSync(new URL(`../fixtures/${name}`, import.meta.url), 'utf8'))!
const snapshotOf = (...names: string[]) => names.map(frame).reduce<Snapshot>((s, f: Frame) => withFrame(s, f), EMPTY)

const KESTREL = '76561198000000007' as SteamId
const MUGI = '76561198000000002' as SteamId
const friends: Friends = friendsOf([
  { steamid: KESTREL, name: 'Kestrel', status: 'In the lobby', presence: 'playing', currentUser: false },
  { steamid: MUGI, name: 'Mugi', status: 'In the main menu', presence: 'online', currentUser: false },
  { steamid: '76561198000000004', name: 'Away', status: '', presence: 'offline', currentUser: false },
  { steamid: '76561198000000009', name: 'Me', status: '', presence: 'playing', currentUser: true },
  { steamid: '0', name: 'Broken', status: '', presence: 'online', currentUser: false },
])

function run(snapshot: Snapshot, ...events: GamesEvent[]) {
  let state: GamesState = initialGames('games')
  const effects = []
  for (const event of events) {
    const [next, out] = reduceGames(state, event, snapshot)
    state = next
    effects.push(...out)
  }
  return { state, effects, view: viewGames(state, snapshot, 'online', friends, 'Mugi') }
}

test('the friend list shows online friends, counts offline ones and leaves the player out', () => {
  assert.deepEqual(friends.online.map((f) => f.name), ['Kestrel', 'Mugi'])
  assert.equal(friends.offline, 1)
  assert.equal(friends.names['76561198000000009'], undefined)
})

test('the list shows the catalog, the first mode picked, with its own words', () => {
  const { view } = run(snapshotOf('hello.json', 'catalog.json', 'game-idle.json'))
  assert.deepEqual(view.rows.map((r) => [r.mode.name, r.selected]), [['Arena', true], ['Bebop Wars', false]])
  assert.equal(view.play.kind, 'play')
  assert.equal(view.play.label, 'Play Arena')
  assert.equal(view.counts.games, 2)
})

test('picking a mode shows its banner line, chips and settings with defaults', () => {
  const { view } = run(snapshotOf('catalog.json', 'game-idle.json'), { kind: 'mode', mode: 'bebop-wars' as never })
  assert.equal(view.selected?.name, 'Bebop Wars')
  assert.equal(view.byline, 'by Hideout team · 12 MB')
  assert.deepEqual(view.chips.map((c) => c.text), ['Teams', '2–6 players'])
  assert.deepEqual(view.settings.map((s) => [s.setting.label, s.value]), [['Teams', '2v2']])
  assert.equal(view.rows[1]?.meta, 'Teams · 2–6 players')
})

test('Play sends the picks, listing, invitees and the restart Play was shown, then shows Starting', () => {
  const snapshot = snapshotOf('catalog.json', 'game-idle.json')
  const { effects, view } = run(
    snapshot,
    { kind: 'mode', mode: 'bebop-wars' as never },
    { kind: 'pick', key: 'teams', value: '3v3' },
    { kind: 'listing', listing: 'public' },
    { kind: 'friend', id: KESTREL },
    { kind: 'play' },
  )
  assert.deepEqual(effects, [
    { kind: 'send', what: 'play', command: { k: 'host', mode: 'bebop-wars', picks: { teams: '3v3' }, listing: 'public', invite: [KESTREL], restart: null } },
  ])
  assert.deepEqual(view.play, { kind: 'busy', label: 'Starting…', hint: '' })
  assert.equal(view.listingLine, 'Listed in Servers as “Mugi’s Bebop Wars”')
})

test('a press is not sent twice while the first is in flight', () => {
  const { effects } = run(snapshotOf('catalog.json', 'game-idle.json'), { kind: 'play' }, { kind: 'play' })
  assert.equal(effects.length, 1)
})

test('before Play a friend toggles between Invite and Invited, and the hint counts them', () => {
  const snapshot = snapshotOf('catalog.json', 'game-idle.json')
  assert.equal(run(snapshot, { kind: 'friend', id: KESTREL }).view.play.hint, '1 friend gets an invite when your game is ready.')
  const twice = run(snapshot, { kind: 'friend', id: KESTREL }, { kind: 'friend', id: KESTREL }).view
  assert.deepEqual(twice.friends.map((f) => f.button), ['invite', 'invite'])
  assert.equal(twice.play.hint, 'Your PC hosts this game. Invite friends here or share the code.')
})

test('a restart ahead is said before Play and echoed in host', () => {
  const snapshot = snapshotOf('catalog.json', 'game-review.json')
  const restartless = withFrame(snapshot, { t: 'game', host: null, join: null, restartAhead: snapshot.game!.restartAhead })
  const { effects, view } = run(restartless, { kind: 'play' })
  assert.equal(effects[0]?.kind === 'send' && effects[0].command.k === 'host' && effects[0].command.restart, 'game-updated')
  assert.equal(run(restartless).view.play.hint, snapshot.game!.restartAhead!.text)
  assert.equal(view.play.kind, 'busy')
})

test('the game topic drives Starting with the running step, whatever the popup sent', () => {
  const { view } = run(snapshotOf('catalog.json', 'game-starting.json'))
  assert.deepEqual(view.play, { kind: 'busy', label: 'Starting…', hint: 'Getting Bebop Wars ready · 7.7 of 12 MB' })
  assert.deepEqual(view.friends.map((f) => f.button), ['invited', 'off'])
})

test('a failed game offers its first action with the app’s words', () => {
  const { view, effects } = run(snapshotOf('catalog.json', 'game-failed.json'), { kind: 'act', action: 'again' })
  assert.deepEqual(effects, [{ kind: 'send', what: 'act', command: { k: 'act', action: 'again' } }])
  assert.equal(run(snapshotOf('catalog.json', 'game-failed.json')).view.play.label, 'Try again')
  assert.equal(view.play.hint, 'Bebop Wars couldn’t load its arena')
})

test('a friend the invite didn’t reach gets Copy link, which copies the game’s link and opens their chat', () => {
  const snapshot = snapshotOf('hello.json', 'catalog.json', 'game-invites.json')
  const notReached = '76561198000000008' as SteamId
  const extra = friendsOf([{ steamid: notReached, name: 'Nori', status: '', presence: 'online', currentUser: false }])
  assert.equal(viewGames(initialGames('games'), snapshot, 'online', extra, '').friends[0]?.button, 'copy-link')
  const [, effects] = reduceGames(initialGames('games'), { kind: 'friend', id: notReached }, snapshot)
  assert.deepEqual(effects, [{ kind: 'copy-and-chat', link: 'https://hideouthq.gg/join#P95-MAN-26D', friend: notReached }])
})

test('a refused Play says why and lets the player press again', () => {
  const snapshot = snapshotOf('catalog.json', 'game-idle.json')
  const { view } = run(snapshot, { kind: 'play' }, { kind: 'settled', what: 'play', result: 'busy' })
  assert.equal(view.play.kind, 'play')
  assert.equal(view.play.hint, 'Finish the game that’s running first.')
})

test('with Hideout closed Play says to open it', () => {
  const view = viewGames(initialGames('games'), EMPTY, 'offline', friends, '')
  assert.deepEqual(view.play.kind, 'blocked')
  assert.equal(view.play.label, 'Open Hideout to play')
})

test('servers read as rows with places, fill, ping tone and joinability', () => {
  const view = viewServers(snapshotOf('servers.json'))
  assert.deepEqual(
    view.lines.map((l) => [l.name, l.sub.split(' · ').at(-1), l.region, l.fill, l.ping, l.status, l.joinable]),
    [
      ['Mugi’s Bebop Wars', 'Hosted by Mugi', 'Frankfurt', 0.5, { text: '31 ms', tone: 'good' }, 'In the lobby', true],
      ['Bebop Wars', 'Hideout server', 'Seattle', 1, null, 'Full', false],
    ],
  )
  assert.equal(view.lines[0]?.sub, 'Bebop Wars · 3v3 · Hosted by Mugi')
  assert.equal(viewServers(snapshotOf('servers-unavailable.json')).note, 'This Hideout can’t list servers.')
  assert.equal(viewServers(snapshotOf('servers-loading.json')).note, null)
  const { effects } = run(snapshotOf('servers.json'), { kind: 'tab', tab: 'servers' }, { kind: 'join', server: '3c41a9e07d5b2f18' })
  assert.deepEqual(effects, [{ kind: 'send', what: 'join', command: { k: 'join', server: '3c41a9e07d5b2f18' } }])
})

test('toasts show friends’ unexpired invites, one full then slim, with the friend list’s name', () => {
  const snapshot = snapshotOf('inbox.json')
  const expires = snapshot.inbox!.invites[0]!.expires
  const toasts = wantedToasts(snapshot, friends, expires - 15000, { '9f2c41d07e6b4a58b3c1d2e3f4a5b6c7': expires - 30000 }, new Set())
  assert.deepEqual(toasts.map((t) => [t.kind, t.kind === 'invite' && t.name, t.full]), [['invite', 'Mugi', true]])
  assert.equal(toasts[0]?.kind === 'invite' && toasts[0].left, 0.5)
  assert.equal(toasts[0]?.kind === 'invite' && toasts[0].line, '3v3 · 2 of 6')
  assert.deepEqual(wantedToasts(snapshot, friends, expires + 1, {}, new Set()), [])
  assert.deepEqual(wantedToasts(snapshot, friends, 0, {}, new Set(['9f2c41d07e6b4a58b3c1d2e3f4a5b6c7'])), [])
})

test('a not-reached invite of the hosted game shows a Copy link toast', () => {
  const toasts = wantedToasts(snapshotOf('game-invites.json'), friends, 0, {}, new Set())
  assert.deepEqual(toasts, [{ kind: 'unreached', key: 'unreached:76561198000000008', friend: '76561198000000008', name: 'A friend', link: 'https://hideouthq.gg/join#P95-MAN-26D', full: true }])
})

test('the joining dialog is the join card: review shows the restart and Join echoes it', () => {
  const view = viewJoining(snapshotOf('game-review.json'), null)!
  assert.equal(view.title, 'Join Mugi’s game')
  assert.equal(view.line, '3v3 · 6 players')
  assert.equal(view.box?.tone, 'note')
  assert.deepEqual(view.buttons, [
    { kind: 'act', action: 'stop', label: 'Cancel', primary: false },
    { kind: 'confirm', restart: 'game-updated', label: 'Join', primary: true },
  ])
  assert.equal(viewJoining(snapshotOf('game-review.json'), 'review'), null)
  assert.equal(viewJoining(snapshotOf('game-idle.json'), null), null)
})
