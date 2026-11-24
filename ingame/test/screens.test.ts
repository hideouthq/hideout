import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { viewEscEntry } from '../src/escmenu.ts'
import { type Friends, friendsOf } from '../src/friends.ts'
import { type GamesEvent, type GamesState, initialGames, reduceGames, viewGames, viewServers } from '../src/games.ts'
import { viewJoining } from '../src/joining.ts'
import { viewStrip } from '../src/strip.ts'
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
  return runWith(friends, snapshot, ...events)
}

function runWith(list: Friends, snapshot: Snapshot, ...events: GamesEvent[]) {
  let state: GamesState = initialGames('games')
  const effects = []
  for (const event of events) {
    const [next, out] = reduceGames(state, event, snapshot, list)
    state = next
    effects.push(...out)
  }
  return { state, effects, view: viewGames(state, snapshot, 'online', list, 'Mugi') }
}

const NORI = '76561198000000003' as SteamId
const playing = (status: string) => friendsOf([{ steamid: NORI, name: 'nori', status, presence: 'playing', currentUser: false }]).online[0]!

test('the friend list shows online friends, counts offline ones and leaves the player out', () => {
  assert.deepEqual(friends.online.map((f) => f.name), ['Kestrel', 'Mugi'])
  assert.equal(friends.offline, 1)
  assert.equal(friends.names['76561198000000009'], undefined)
})

test('a friend’s status reads as Deadlock’s menus say it, and says when they’re in a match', () => {
  const seen = (f: { status: string; inMatch: boolean }) => [f.status, f.inMatch]
  assert.deepEqual(seen(playing('Main Menu')), ['In the main menu', false])
  assert.deepEqual(seen(playing('Finding Match')), ['Finding a match', false])
  assert.deepEqual(seen(playing('Picking Hero')), ['In a match', true])
  assert.deepEqual(seen(playing('Playing: Haze 4.2k')), ['In a match', true])
  assert.deepEqual(seen(playing('Playing: Haze 12:34')), ['In a match · 12 min', true])
  assert.deepEqual(seen(playing('Playing: Haze 1:02:09')), ['In a match · 62 min', true])
  assert.deepEqual(seen(playing('Playing: Haze 7 min')), ['In a match · 7 min', true])
  assert.deepEqual(seen(playing('In the lobby')), ['In the lobby', false])
  assert.deepEqual(seen(playing('')), ['Online', false])
  const online = friendsOf([
    { steamid: NORI, name: 'nori', status: '', presence: 'online', currentUser: false },
    { steamid: MUGI, name: 'Mugi', status: 'Playing: Haze', presence: 'online', currentUser: false },
  ]).online
  assert.deepEqual(online.map(seen), [['Online', false], ['Playing: Haze', false]], 'outside Deadlock the text is shown as Steam gives it')
})

test('a friend in a match gets a disabled In match, and pressing it does nothing', () => {
  const list = friendsOf([
    { steamid: KESTREL, name: 'Kestrel', status: 'Playing: Haze 12:34', presence: 'playing', currentUser: false },
    { steamid: MUGI, name: 'Mugi', status: 'Main Menu', presence: 'playing', currentUser: false },
  ])
  const before = runWith(list, snapshotOf('catalog.json', 'game-idle.json'), { kind: 'friend', id: KESTREL }, { kind: 'friend', id: MUGI })
  assert.deepEqual(before.view.friends.map((f) => f.button), ['in-match', 'invited'])
  assert.deepEqual(before.state.draft.invitees, [MUGI])

  const hosting = snapshotOf('hello-on.json', 'catalog.json', 'game-invites.json')
  const mugiPlaying = friendsOf([{ steamid: MUGI, name: 'Mugi', status: 'Picking Hero', presence: 'playing', currentUser: false }])
  const invites = runWith(mugiPlaying, hosting, { kind: 'friend', id: MUGI })
  assert.deepEqual(invites.effects, [])
  assert.equal(invites.view.friends[0]?.button, 'in-match')
  const notReached = '76561198000000008' as SteamId
  const unreached = friendsOf([{ steamid: notReached, name: 'Nori', status: 'Picking Hero', presence: 'playing', currentUser: false }])
  assert.equal(runWith(unreached, hosting, { kind: 'friend', id: notReached }).effects.length, 0, 'not Copy link either')

  const invited = runWith(list, snapshotOf('catalog.json', 'game-starting.json'))
  assert.equal(invited.view.friends[0]?.button, 'invited', 'an invited friend stays Invited')
})

test('the list shows the catalog, the first mode picked, with its own words', () => {
  const { view } = run(snapshotOf('hello.json', 'catalog.json', 'game-idle.json'))
  assert.deepEqual(view.rows.slice(0, 2).map((r) => [r.mode.name, r.selected]), [['Arena', true], ['Bebop Wars', false]])
  assert.equal(view.play.kind, 'play')
  assert.equal(view.play.label, 'Play Arena')
  assert.equal(view.counts.games, 2)
})

test('picking a mode shows its banner line, chips and settings with defaults', () => {
  const { view } = run(snapshotOf('catalog.json', 'game-idle.json'), { kind: 'mode', mode: 'bebop-wars' as never })
  assert.equal(view.selected?.name, 'Bebop Wars')
  assert.equal(view.byline, 'by Hideout team · 12 MB')
  assert.deepEqual(view.chips, [
    { icon: 'kind', text: 'Teams' },
    { icon: 'players', text: '2–6 players' },
    { icon: 'time', text: '8–10 min' },
    { icon: 'goal', text: 'First to 25 kills' },
  ])
  assert.deepEqual(view.settings.map((s) => [s.setting.label, s.value]), [['Teams', '2v2']])
  assert.equal(view.rows[1]?.meta, 'Teams · 2–6 players')
})

test('the goal chip says the picked target, and a fixed length reads as about that long', () => {
  const catalog = snapshotOf('catalog.json').catalog!
  const bebop = catalog.modes[1]!
  const kills = { key: 'kills', label: 'Kills to win', hint: null, default: '25', options: ['10', '25', '50'].map((v) => ({ value: v, label: v, players: null })) }
  const snapshot = withFrame(snapshotOf('game-idle.json'), {
    ...catalog,
    modes: [{ ...bebop, minutes: [10, 10], goal: 'First to {kills} kills', settings: [...bebop.settings, kills] }],
  })
  assert.deepEqual(run(snapshot).view.chips.slice(2).map((c) => c.text), ['~10 min', 'First to 25 kills'])
  assert.equal(run(snapshot, { kind: 'pick', key: 'kills', value: '50' }).view.chips.at(-1)?.text, 'First to 50 kills')
})

test('modes not out yet are listed after the playable ones as Soon, and the Games count leaves them out', () => {
  const { view } = run(snapshotOf('catalog.json', 'game-idle.json'))
  assert.deepEqual(
    view.rows.map((r) => [r.mode.name, r.soon]),
    [['Arena', false], ['Bebop Wars', false], ['Hero Roulette', true], ['Dodgeball', true]],
  )
  assert.equal(view.counts.games, 2)
})

test('a Soon mode shows its detail with no settings, and Play says it isn’t out yet', () => {
  const snapshot = snapshotOf('catalog.json', 'game-idle.json')
  const { view, effects } = run(snapshot, { kind: 'mode', mode: 'hero-roulette' as never }, { kind: 'play' })
  assert.equal(view.selected?.name, 'Hero Roulette')
  assert.equal(view.soon, true)
  assert.equal(view.byline, 'by Hideout team')
  assert.deepEqual(view.chips.map((c) => c.text), ['Free-for-all', '2–12 players', 'First to 20 kills'])
  assert.deepEqual(view.settings, [])
  assert.deepEqual(view.play, { kind: 'blocked', label: 'Coming soon', hint: 'Hero Roulette isn’t out yet. It comes to Hideout in an update.' })
  assert.deepEqual(effects, [])
  assert.equal(run(snapshot, { kind: 'mode', mode: 'bebop-wars' as never }).view.soon, false)
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
  const restartless = withFrame(snapshot, { t: 'game', host: null, join: null, session: null, restartAhead: snapshot.game!.restartAhead })
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
  const [, effects] = reduceGames(initialGames('games'), { kind: 'friend', id: notReached }, snapshot, extra)
  assert.deepEqual(effects, [{ kind: 'copy-and-chat', link: 'https://hideouthq.gg/join#P95-MAN-26D', friend: notReached }])
})

test('on a PC the site refused, friends get Copy link and the panel gives only the player’s own id for support', () => {
  const hosting = run(snapshotOf('hello-blocked.json', 'catalog.json', 'game-invites.json'), { kind: 'friend', id: KESTREL })
  assert.deepEqual(hosting.view.friends.map((f) => f.button), ['copy-link', 'copy-link'], 'not Invited, even for a friend the site said it reached')
  assert.deepEqual(hosting.effects, [{ kind: 'copy-and-chat', link: 'https://hideouthq.gg/join#P95-MAN-26D', friend: KESTREL }])
  assert.deepEqual(hosting.view.blocked, { text: 'Invites are blocked on this PC. Send support this ID to reset it: 76561198000000001', id: '76561198000000001' })

  const before = run(snapshotOf('hello-blocked.json', 'catalog.json', 'game-idle.json'), { kind: 'friend', id: KESTREL }, { kind: 'play' })
  assert.deepEqual(before.view.friends.map((f) => f.button), ['off', 'off'])
  assert.deepEqual(before.effects[0]?.kind === 'send' && before.effects[0].command.k === 'host' && before.effects[0].command.invite, [])
  assert.equal(run(snapshotOf('hello-on.json', 'catalog.json', 'game-invites.json')).view.blocked, null)
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

test('with Hideout closed before any list came, the panel says to open it, and lists the games once it is back', () => {
  const closed = viewGames(initialGames('games'), EMPTY, 'offline', friends, '')
  assert.deepEqual(closed.empty, { kind: 'note', title: 'Open Hideout to play', body: 'Hideout games run from the Hideout app on this PC.' })
  assert.deepEqual(viewGames(initialGames('games'), EMPTY, 'connecting', friends, '').empty, { kind: 'loading' })
  const back = viewGames(initialGames('games'), snapshotOf('hello.json', 'catalog.json', 'game-idle.json'), 'online', friends, '')
  assert.equal(back.empty, null)
  assert.equal(back.rows.length, 4)
})

test('servers read as rows with places, fill, ping tone and joinability', () => {
  const view = viewServers(snapshotOf('servers.json'))
  assert.deepEqual(
    view.lines.map((l) => [l.name, l.sub.split(' · ').at(-1), l.region, l.fill, l.ping, l.status, l.joinable]),
    [
      ['Mugi’s Bebop Wars', 'Hosted by Mugi', 'EU Central', 0.5, { text: '31 ms', tone: 'good' }, 'In the lobby', true],
      ['Bebop Wars', 'Hideout server', 'NA West', 1, null, 'Full', false],
    ],
  )
  assert.equal(view.lines[0]?.sub, 'Bebop Wars · 3v3 · Hosted by Mugi')
  assert.equal(viewServers(snapshotOf('servers-unavailable.json')).note, 'This Hideout can’t list servers.')
  assert.equal(viewServers(snapshotOf('servers-loading.json')).note, null)
  assert.equal(run(EMPTY, { kind: 'servers-answered', result: 'unavailable' }).view.servers.note, 'This Hideout can’t list servers.')
  const { effects } = run(snapshotOf('servers.json'), { kind: 'tab', tab: 'servers' }, { kind: 'join', server: '3c41a9e07d5b2f18' })
  assert.deepEqual(effects, [{ kind: 'send', what: 'join', command: { k: 'join', server: '3c41a9e07d5b2f18' } }])
})

test('a server names its region as the game does, and an unknown one by its code', () => {
  const servers = snapshotOf('servers.json').servers!
  const regions = ['ams', 'waw', 'tor', 'den', 'hnl', 'tok', 'xyz'].map((region) => ({ ...servers.rows[0]!, region }))
  const view = viewServers(withFrame(EMPTY, { ...servers, rows: regions }))
  assert.deepEqual(view.lines.map((l) => l.region), ['EU West', 'EU East', 'NA East', 'NA Central', 'Hawaii', 'Japan', 'XYZ'])
})

test('a join’s note goes once the joining layer shows the join, or the player changes tab', () => {
  const servers = snapshotOf('servers.json')
  const join = { kind: 'join', server: '3c41a9e07d5b2f18' } as const
  const { state } = run(servers, { kind: 'tab', tab: 'servers' }, join, { kind: 'settled', what: 'join', result: 'unreachable' })
  assert.deepEqual(state.request, { kind: 'refused', what: 'join', result: 'unreachable' })
  assert.deepEqual(reduceGames(state, { kind: 'seen' }, servers, friends)[0].request, state.request, 'a frame without the join keeps it')
  assert.deepEqual(reduceGames(state, { kind: 'seen' }, withFrame(servers, frame('game-review.json')), friends)[0].request, { kind: 'idle' })
  assert.deepEqual(reduceGames(state, { kind: 'tab', tab: 'games' }, servers, friends)[0].request, { kind: 'idle' })
})

test('toasts show friends’ unexpired invites, one full then slim, with the friend list’s name', () => {
  const snapshot = snapshotOf('inbox.json')
  const expires = snapshot.inbox!.invites[0]!.expires
  const toasts = wantedToasts(snapshot, friends, expires - 15000, { '9f2c41d07e6b4a58b3c1d2e3f4a5b6c7': expires - 30000 }, new Set(), true)
  assert.deepEqual(toasts.map((t) => [t.kind, t.kind === 'invite' && t.name, t.full]), [['invite', 'Mugi', true]])
  assert.equal(toasts[0]?.kind === 'invite' && toasts[0].left, 0.5)
  assert.equal(toasts[0]?.kind === 'invite' && toasts[0].line, '3v3 · 2 of 6')
  assert.deepEqual(wantedToasts(snapshot, friends, expires + 1, {}, new Set(), true), [])
  assert.deepEqual(wantedToasts(snapshot, friends, 0, {}, new Set(['9f2c41d07e6b4a58b3c1d2e3f4a5b6c7']), true), [])
})

test('a not-reached invite of the hosted game shows a Copy link toast', () => {
  const toasts = wantedToasts(snapshotOf('game-invites.json'), friends, 0, {}, new Set(), true)
  assert.deepEqual(toasts.map((t) => t.kind === 'unreached' && [t.friend, t.name, t.link, t.full]), [['76561198000000008', 'A friend', 'https://hideouthq.gg/join#P95-MAN-26D', true]])
})

test('closing a not-reached toast hides it for that game alone', () => {
  const first = snapshotOf('game-invites.json')
  const closed = new Set(wantedToasts(first, friends, 0, {}, new Set(), true).map((t) => t.key))
  assert.deepEqual(wantedToasts(first, friends, 0, {}, closed, true), [])
  const next = withFrame(first, { ...first.game!, t: 'game', host: { ...first.game!.host!, link: 'https://hideouthq.gg/join#T4N-GL3-ZZ9' } })
  assert.deepEqual(wantedToasts(next, friends, 0, {}, closed, true).map((t) => t.kind === 'unreached' && t.friend), ['76561198000000008'], 'a later game that misses the same friend says so again')
})

test('the joining dialog is the join card: review shows the restart and Join echoes it', () => {
  const view = viewJoining(snapshotOf('game-review.json'), null, 'online')!
  assert.equal(view.title, 'Join Mugi’s game')
  assert.equal(view.line, '3v3 · 6 players')
  assert.equal(view.box?.tone, 'note')
  assert.deepEqual(view.buttons, [
    { kind: 'act', action: 'stop', label: 'Cancel', primary: false },
    { kind: 'confirm', restart: 'game-updated', label: 'Join', primary: true },
  ])
  assert.equal(viewJoining(snapshotOf('game-review.json'), 'review', 'online'), null)
  assert.equal(viewJoining(snapshotOf('game-idle.json'), null, 'online'), null)
})

test('the joining dialog leaves once Hideout is gone, since nothing there works without it', () => {
  assert.equal(viewJoining(snapshotOf('game-review.json'), null, 'offline'), null)
  assert.notEqual(viewJoining(snapshotOf('game-review.json'), null, 'online'), null, 'and comes back with it')
})

test('the hosting strip names the hosted game and its players, with Stop', () => {
  assert.deepEqual(viewStrip(snapshotOf('catalog.json', 'game-ready.json'), 'online', false, true), {
    kind: 'hosting',
    art: null,
    mode: 'Bebop Wars',
    players: '1 of 6',
    button: 'stop',
  })
})

test('a starting or ending game shows the host card’s words, and Stop only while the card offers it', () => {
  const starting = viewStrip(snapshotOf('catalog.json', 'game-starting.json'), 'online', false, true)
  assert.deepEqual(starting, { kind: 'status', art: null, mode: 'Bebop Wars', words: 'Starting your game', button: 'stop' })
  const ending = viewStrip(snapshotOf('catalog.json', 'game-ending.json'), 'online', false, true)
  assert.deepEqual(ending, { kind: 'status', art: null, mode: 'Bebop Wars', words: 'Ending your game', button: 'spinner' })
})

test('a pressed Stop spins until the game frame changes', () => {
  assert.equal(viewStrip(snapshotOf('catalog.json', 'game-ready.json'), 'online', true, true)?.button, 'spinner')
})

test('while the game holds the mouse, the strip and toasts say Esc frees it instead of offering buttons it can’t reach', () => {
  assert.equal(viewStrip(snapshotOf('catalog.json', 'game-ready.json'), 'online', false, false)?.button, 'esc')
  assert.equal(viewStrip(snapshotOf('catalog.json', 'game-ending.json'), 'online', false, false)?.button, 'spinner', 'a spinner needs no pointer')
  const invites = wantedToasts(snapshotOf('inbox.json'), friends, 0, {}, new Set(), false)
  const unreached = wantedToasts(snapshotOf('game-invites.json'), friends, 0, {}, new Set(), false)
  assert.deepEqual([...invites, ...unreached].map((t) => [t.kind, t.pointer]), [['invite', false], ['unreached', false]])
  assert.equal(wantedToasts(snapshotOf('inbox.json'), friends, 0, {}, new Set(), true)[0]?.pointer, true)
})

test('no strip without a running hosted game, or once Hideout is gone', () => {
  assert.equal(viewStrip(snapshotOf('catalog.json', 'game-idle.json'), 'online', false, true), null)
  assert.equal(viewStrip(snapshotOf('catalog.json', 'game-failed.json'), 'online', false, true), null)
  assert.equal(viewStrip(snapshotOf('catalog.json', 'game-review.json'), 'online', false, true), null, 'a guest joining has the joining dialog')
  assert.equal(viewStrip(snapshotOf('catalog.json', 'game-ready.json'), 'offline', false, true), null)
})

test('Deadlock’s Esc menu offers the host Stop and a guest Leave, in the match and while joining', () => {
  assert.deepEqual(viewEscEntry(snapshotOf('game-ready.json'), 'online', false), { label: 'STOP HIDEOUT GAME', busy: false })
  assert.deepEqual(viewEscEntry(snapshotOf('game-guest.json'), 'online', false), { label: 'LEAVE HIDEOUT GAME', busy: false })
  assert.equal(viewEscEntry(snapshotOf('game-review.json'), 'online', false)?.label, 'LEAVE HIDEOUT GAME')
})

test('a pressed Esc menu entry stays greyed until the game frame changes', () => {
  assert.equal(viewEscEntry(snapshotOf('game-ready.json'), 'online', true)?.busy, true)
})

test('no Esc menu entry while the session takes no stop, or once Hideout is gone', () => {
  assert.equal(viewEscEntry(snapshotOf('game-idle.json'), 'online', false), null)
  assert.equal(viewEscEntry(snapshotOf('game-ending.json'), 'online', false), null)
  assert.equal(viewEscEntry(snapshotOf('game-failed.json'), 'online', false), null)
  assert.equal(viewEscEntry(snapshotOf('game-ready.json'), 'offline', false), null)
  assert.equal(viewEscEntry(snapshotOf('game-guest.json'), 'outdated', false), null)
})
