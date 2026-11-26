import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { test } from 'node:test'
import { viewEscEntries } from '../src/escmenu.ts'
import { type Friends, friendsOf } from '../src/friends.ts'
import { type GamesEvent, type GamesState, initialGames, reduceGames, viewGames, viewServers } from '../src/games.ts'
import { viewJoining } from '../src/joining.ts'
import { viewStrip } from '../src/strip.ts'
import { wantedToasts } from '../src/toasts.ts'
import { EMPTY, type Frame, type HostCard, type ModeId, type Snapshot, type SteamId, decodeFrame, withFrame } from '../src/wire.ts'

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
  assert.deepEqual(seen(playing('Lobby: Bebop (8 min)')), ['In a match · 8 min', true])
  assert.deepEqual(seen(playing('Bots: Haze (3 min)')), ['In a match · 3 min', true])
  assert.deepEqual(seen(playing('Ranked: Haze (21 min)')), ['In a match · 21 min', true])
  assert.deepEqual(seen(playing('Street Brawl: Haze')), ['In a match', true])
  assert.deepEqual(seen(playing('Sandbox: Haze (2 min)')), ['In a match · 2 min', true])
  assert.deepEqual(seen(playing('Testing Haze in Sandbox Mode')), ['In a match', true])
  assert.deepEqual(seen(playing('Ignoring Lash In The Hideout')), ['In the hideout', false])
  assert.deepEqual(seen(playing('Wishing the Hideout Was The Cube')), ['In the hideout', false])
  assert.deepEqual(seen(playing('In a Party Hideout')), ['In a party', false])
  assert.deepEqual(seen(playing('Online')), ['Online', false])
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

  const custom = friendsOf([
    { steamid: KESTREL, name: 'Kestrel', status: 'Lobby: Bebop (8 min)', presence: 'playing', currentUser: false },
    { steamid: MUGI, name: 'Mugi', status: 'Relaxing In The Hideout', presence: 'playing', currentUser: false },
  ])
  assert.deepEqual(runWith(custom, snapshotOf('catalog.json', 'game-idle.json')).view.friends.map((f) => [f.friend.status, f.button]), [
    ['In a match · 8 min', 'in-match'],
    ['In the hideout', 'invite'],
  ])

  const invited = runWith(list, snapshotOf('catalog.json', 'game-starting.json'))
  assert.equal(invited.view.friends[0]?.button, 'invited', 'an invited friend stays Invited')
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
  assert.equal(back.rows.length, 2)
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
  const toasts = wantedToasts(snapshot, friends, expires - 15000, { '9f2c41d07e6b4a58b3c1d2e3f4a5b6c7': expires - 30000 }, new Set(), 'escape')
  assert.deepEqual(toasts.map((t) => [t.kind, t.kind === 'invite' && t.name, t.full]), [['invite', 'Mugi', true]])
  assert.equal(toasts[0]?.kind === 'invite' && toasts[0].left, 0.5)
  assert.equal(toasts[0]?.kind === 'invite' && toasts[0].line, '3v3 · 2 of 6')
  assert.deepEqual(wantedToasts(snapshot, friends, expires + 1, {}, new Set(), 'escape'), [])
  assert.deepEqual(wantedToasts(snapshot, friends, 0, {}, new Set(['9f2c41d07e6b4a58b3c1d2e3f4a5b6c7']), 'escape'), [])
})

test('toasts wait while a dashboard screen is open, since it can cover them, and come back after', () => {
  const snapshot = snapshotOf('inbox.json', 'game-invites.json')
  assert.deepEqual(wantedToasts(snapshot, friends, 0, {}, new Set(), 'dashboard'), [])
  assert.deepEqual(wantedToasts(snapshot, friends, 0, {}, new Set(), 'playing').map((t) => t.kind), ['invite', 'unreached'])
})

test('a not-reached invite of the hosted game shows a Copy link toast', () => {
  const toasts = wantedToasts(snapshotOf('game-invites.json'), friends, 0, {}, new Set(), 'escape')
  assert.deepEqual(toasts.map((t) => t.kind === 'unreached' && [t.friend, t.name, t.link, t.full]), [['76561198000000008', 'A friend', 'https://hideouthq.gg/join#P95-MAN-26D', true]])
})

test('closing a not-reached toast hides it for that game alone', () => {
  const first = snapshotOf('game-invites.json')
  const closed = new Set(wantedToasts(first, friends, 0, {}, new Set(), 'escape').map((t) => t.key))
  assert.deepEqual(wantedToasts(first, friends, 0, {}, closed, 'escape'), [])
  const next = withFrame(first, { ...first.game!, t: 'game', host: { ...first.game!.host!, link: 'https://hideouthq.gg/join#T4N-GL3-ZZ9' } })
  assert.deepEqual(wantedToasts(next, friends, 0, {}, closed, 'escape').map((t) => t.kind === 'unreached' && t.friend), ['76561198000000008'], 'a later game that misses the same friend says so again')
})

test('the joining dialog is the join card: review shows the restart and Join echoes it', () => {
  const view = viewJoining(snapshotOf('game-review.json'), null, 'online', friends)!
  assert.equal(view.title, 'Join Mugi’s game')
  assert.equal(view.line, '3v3 · 6 players')
  assert.equal(view.box?.tone, 'note')
  assert.deepEqual(view.buttons, [
    { kind: 'act', action: 'stop', label: 'Cancel', primary: false },
    { kind: 'confirm', restart: 'game-updated', label: 'Join', primary: true },
  ])
  assert.equal(viewJoining(snapshotOf('game-review.json'), 'review', 'online', friends), null)
  assert.equal(viewJoining(snapshotOf('game-idle.json'), null, 'online', friends), null)
})

test('the joining banner names the host’s game, by the friend list’s name when the host is a friend', () => {
  const review = snapshotOf('game-review.json')
  const join = review.game!.join!
  const withHost = (hostId: SteamId | null, hostName: string | null) => withFrame(review, { ...review.game!, join: { ...join, hostId, hostName } })
  const renamed = friendsOf([{ steamid: MUGI, name: 'mugi', status: '', presence: 'offline', currentUser: false }])
  assert.deepEqual(viewJoining(review, null, 'online', renamed)?.host, { id: MUGI, text: 'mugi’s game' })
  assert.deepEqual(viewJoining(review, null, 'online', friendsOf([]))?.host, { id: MUGI, text: 'Mugi’s game' })
  assert.deepEqual(viewJoining(withHost(null, 'Mugi'), null, 'online', friends)?.host, { id: null, text: 'Mugi’s game' })
  assert.equal(viewJoining(withHost(null, null), null, 'online', friends)?.host, null)
  assert.equal(viewJoining(withHost('76561198000000005' as SteamId, null), null, 'online', friends)?.host, null, 'a stranger with no name shows nothing')
})

test('the joining dialog leaves once Hideout is gone, since nothing there works without it', () => {
  assert.equal(viewJoining(snapshotOf('game-review.json'), null, 'offline', friends), null)
  assert.notEqual(viewJoining(snapshotOf('game-review.json'), null, 'online', friends), null, 'and comes back with it')
})

const strip = (snapshot: Snapshot, pressed = false, pointer = true, list: Friends = friends) => viewStrip(snapshot, 'online', pressed, pointer, list)
const STOP = { action: 'stop', label: 'Stop', primary: false }

test('the hosting strip names the hosted game and its players, with Stop', () => {
  assert.deepEqual(strip(snapshotOf('catalog.json', 'game-ready.json')), {
    art: null,
    line: [['Hosting', false], ['Bebop Wars', true], ['· 1 of 6', false]],
    sub: null,
    buttons: { kind: 'acts', acts: [STOP] },
  })
})

test('a starting or ending game shows the host card’s words, and Stop only while the card offers it', () => {
  const starting = strip(snapshotOf('catalog.json', 'game-starting.json'))
  assert.deepEqual([starting?.line, starting?.buttons], [[['Bebop Wars', true], ['· Starting your game', false]], { kind: 'acts', acts: [STOP] }])
  const ending = strip(snapshotOf('catalog.json', 'game-ending.json'))
  assert.deepEqual([ending?.line, ending?.buttons], [[['Bebop Wars', true], ['· Ending your game', false]], { kind: 'spinner' }])
})

test('a host whose Deadlock left the game is told it still runs, with Rejoin and Stop', () => {
  const view = strip(snapshotOf('catalog.json', 'game-notin.json'))
  assert.deepEqual(view?.line, [['Bebop Wars', true], ['is still running', false]])
  assert.equal(view?.sub, '1 player in it', 'the host card counts the host, who is out')
  assert.deepEqual(view?.buttons, { kind: 'acts', acts: [STOP, { action: 'play', label: 'Rejoin', primary: true }] })
})

test('a guest who left the game gets the strip with Rejoin and Leave, and no joining dialog', () => {
  const left = snapshotOf('catalog.json', 'game-guest-notin.json')
  const view = strip(left)
  assert.deepEqual(view?.line, [['You left', false], ['Mugi’s Bebop Wars', true]])
  assert.deepEqual(view?.buttons, {
    kind: 'acts',
    acts: [
      { action: 'stop', label: 'Leave', primary: false },
      { action: 'play', label: 'Rejoin', primary: true },
    ],
  })
  const renamed = friendsOf([{ steamid: MUGI, name: 'mugi', status: '', presence: 'offline', currentUser: false }])
  assert.deepEqual(strip(left, false, true, renamed)?.line[1], ['mugi’s Bebop Wars', true], 'a friend host goes by the friend list’s name')
  assert.equal(strip(snapshotOf('catalog.json', 'game-guest.json')), null, 'a guest in the match has no strip')
  const review = snapshotOf('game-review.json')
  const joinLeft = withFrame(review, { ...review.game!, join: { ...review.game!.join!, phase: 'notin', actions: ['play', 'stop'] } })
  assert.equal(viewJoining(joinLeft, null, 'online', friends), null, 'a join the player’s Deadlock left is the strip’s, not a full-screen dialog')
})

test('a pressed strip button spins until the game frame changes', () => {
  assert.deepEqual(strip(snapshotOf('catalog.json', 'game-ready.json'), true)?.buttons, { kind: 'spinner' })
  assert.deepEqual(strip(snapshotOf('catalog.json', 'game-guest-notin.json'), true)?.buttons, { kind: 'spinner' })
})

test('while the game holds the mouse, the strip and toasts say Esc frees it instead of offering buttons it can’t reach', () => {
  assert.deepEqual(strip(snapshotOf('catalog.json', 'game-ready.json'), false, false)?.buttons, { kind: 'esc', words: 'to stop' })
  assert.deepEqual(strip(snapshotOf('catalog.json', 'game-notin.json'), false, false)?.buttons, { kind: 'esc', words: 'to rejoin' })
  assert.deepEqual(strip(snapshotOf('catalog.json', 'game-ending.json'), false, false)?.buttons, { kind: 'spinner' }, 'a spinner needs no pointer')
  const invites = wantedToasts(snapshotOf('inbox.json'), friends, 0, {}, new Set(), 'playing')
  const unreached = wantedToasts(snapshotOf('game-invites.json'), friends, 0, {}, new Set(), 'playing')
  assert.deepEqual([...invites, ...unreached].map((t) => [t.kind, t.pointer]), [['invite', false], ['unreached', false]])
  assert.equal(wantedToasts(snapshotOf('inbox.json'), friends, 0, {}, new Set(), 'escape')[0]?.pointer, true)
})

test('no strip without a running game, or once Hideout is gone', () => {
  assert.equal(strip(snapshotOf('catalog.json', 'game-idle.json')), null)
  assert.equal(strip(snapshotOf('catalog.json', 'game-failed.json')), null)
  assert.equal(strip(snapshotOf('catalog.json', 'game-review.json')), null, 'a guest joining has the joining dialog')
  assert.equal(viewStrip(snapshotOf('catalog.json', 'game-ready.json'), 'offline', false, true, friends), null)
  assert.equal(viewStrip(snapshotOf('catalog.json', 'game-guest-notin.json'), 'outdated', false, true, friends), null)
})

const labels = (snapshot: Snapshot) => viewEscEntries(snapshot, 'online', false).map((e) => [e.label, e.action])

test('Deadlock’s Esc menu offers the host Stop and a guest Leave, in the match and while joining', () => {
  assert.deepEqual(labels(snapshotOf('game-ready.json')), [['Stop Hideout Game', 'stop']])
  assert.deepEqual(labels(snapshotOf('game-guest.json')), [['Leave Hideout Game', 'stop']])
  assert.deepEqual(labels(snapshotOf('game-review.json')), [['Leave Hideout Game', 'stop']])
})

test('out of a running game, the Esc menu offers Rejoin above Stop or Leave', () => {
  assert.deepEqual(labels(snapshotOf('game-notin.json')), [['Rejoin Hideout Game', 'play'], ['Stop Hideout Game', 'stop']])
  assert.deepEqual(labels(snapshotOf('game-guest-notin.json')), [['Rejoin Hideout Game', 'play'], ['Leave Hideout Game', 'stop']])
})

test('a pressed Esc menu entry stays greyed until the game frame changes', () => {
  assert.deepEqual(viewEscEntries(snapshotOf('game-notin.json'), 'online', true).map((e) => e.busy), [true, true])
})

test('no Esc menu entry while the session takes no stop, or once Hideout is gone', () => {
  assert.deepEqual(viewEscEntries(snapshotOf('game-idle.json'), 'online', false), [])
  assert.deepEqual(viewEscEntries(snapshotOf('game-ending.json'), 'online', false), [])
  assert.deepEqual(viewEscEntries(snapshotOf('game-failed.json'), 'online', false), [])
  assert.deepEqual(viewEscEntries(snapshotOf('game-ready.json'), 'offline', false), [])
  assert.deepEqual(viewEscEntries(snapshotOf('game-guest-notin.json'), 'outdated', false), [])
})

test('out of a running game, the panel’s big button rejoins it, for host and guest', () => {
  for (const name of ['game-notin.json', 'game-guest-notin.json']) {
    const { view, effects } = run(snapshotOf('catalog.json', name), { kind: 'act', action: 'play' })
    assert.equal(view.play.kind, 'act')
    assert.equal(view.play.label, 'Rejoin Bebop Wars', name)
    assert.deepEqual(effects, [{ kind: 'send', what: 'act', command: { k: 'act', action: 'play' } }])
  }
})

test('every Play, server Join and toast Join the screens offer is one the session takes', () => {
  const games = readdirSync(new URL('../fixtures/', import.meta.url)).filter((name) => name.startsWith('game-'))
  for (const name of games) {
    const snapshot = snapshotOf('hello-on.json', 'catalog.json', 'servers.json', 'inbox.json', name)
    const game = snapshot.game!
    const { view, effects } = run(snapshot, { kind: 'play' }, { kind: 'tab', tab: 'servers' }, { kind: 'join', server: '3c41a9e07d5b2f18' })
    if (view.play.kind === 'play') assert.equal(game.startsHost, true, `${name}: Play, while the session starts no host`)
    for (const line of view.servers.lines) if (line.joinable) assert.equal(game.takesInvite, true, `${name}: a server's Join, while the session takes no join`)
    for (const toast of wantedToasts(snapshot, friends, 0, {}, new Set(), 'escape')) {
      if (toast.kind === 'invite' && toast.joinable) assert.equal(game.takesInvite, true, `${name}: a toast's Join, while the session takes no join`)
    }
    for (const effect of effects) {
      if (effect.kind === 'send' && effect.command.k === 'host') assert.equal(game.startsHost, true, `${name}: sent a host`)
      if (effect.kind === 'send' && effect.command.k === 'join') assert.equal(game.takesInvite, true, `${name}: sent a join`)
    }
  }
})

test('a guest in a match and a host whose game runs are told to leave it first', () => {
  const guest = run(snapshotOf('hello.json', 'catalog.json', 'game-guest.json')).view.play
  assert.deepEqual(guest, { kind: 'blocked', label: 'Play Arena', hint: 'Leave your current game first.' })
  const hosting = run(snapshotOf('hello-on.json', 'catalog.json', 'servers.json', 'inbox.json', 'game-ready.json'), { kind: 'tab', tab: 'servers' })
  assert.equal(hosting.view.servers.note, 'Leave your current game first.')
  const toast = wantedToasts(snapshotOf('inbox.json', 'game-ready.json'), friends, 0, {}, new Set(), 'escape')[0]
  assert.equal(toast?.kind === 'invite' && toast.blocked, 'Leave your current game first.')
})

const withSolo = (snapshot: Snapshot) => {
  const catalog = snapshotOf('catalog.json').catalog!
  const arena = catalog.modes[0]!
  const solo = { ...arena, id: 'parry-drill' as ModeId, name: 'Parry Drill', players: [1, 1] as [number, number] }
  const coop = { ...arena, id: 'keep' as ModeId, name: 'Keep', kind: 'co-op', players: [1, 12] as [number, number] }
  return withFrame(snapshot, { ...catalog, modes: [...catalog.modes, solo, coop] })
}

test('a solo mode reads as Solo and offers no invites or listing, and Play sends none whatever was drafted', () => {
  const snapshot = withSolo(snapshotOf('hello-on.json', 'game-idle.json'))
  const drafted: GamesEvent[] = [
    { kind: 'mode', mode: 'bebop-wars' as ModeId },
    { kind: 'friend', id: KESTREL },
    { kind: 'listing', listing: 'public' },
    { kind: 'mode', mode: 'parry-drill' as ModeId },
  ]
  const { view, state } = run(snapshot, ...drafted, { kind: 'friend', id: MUGI }, { kind: 'listing', listing: 'invite-only' })
  assert.equal(view.rows.find((r) => r.mode.id === 'parry-drill')?.meta, 'Solo')
  assert.deepEqual(view.chips.find((c) => c.icon === 'players'), { icon: 'players', text: 'Solo' })
  assert.deepEqual([view.solo, view.friends], [true, []])
  assert.equal(view.play.hint, 'A solo game on your PC. Nobody else can join.')
  assert.deepEqual([state.draft.invitees, state.draft.listing], [[KESTREL], 'public'], 'the presses a solo mode doesn’t offer change nothing')
  assert.equal(view.rows.find((r) => r.mode.id === 'keep')?.meta, 'Co-op · 1–12 players', 'a mode one player can start is not solo')

  const { effects } = run(snapshot, ...drafted, { kind: 'play' })
  assert.deepEqual(effects, [{ kind: 'send', what: 'play', command: { k: 'host', mode: 'parry-drill', picks: {}, listing: 'invite-only', invite: [], restart: null } }])
  const back = run(snapshot, ...drafted, { kind: 'mode', mode: 'bebop-wars' as ModeId }).view
  assert.deepEqual([back.solo, back.friends.map((f) => f.button)], [false, ['invited', 'invite']], 'a mode for more players offers them again')
})

test('a hosted solo game offers no invites while it runs, and the strip says it is played', () => {
  const ready = snapshotOf('game-ready.json').game!
  const host: HostCard = { ...ready.host!, mode: 'parry-drill' as ModeId, link: null, players: [1, 1], listing: 'invite-only', invitees: [] }
  const snapshot = withSolo(withFrame(snapshotOf('hello-on.json'), { ...ready, host }))
  const { view, effects } = run(snapshot, { kind: 'mode', mode: 'bebop-wars' as ModeId }, { kind: 'friend', id: KESTREL }, { kind: 'listing', listing: 'public' })
  assert.deepEqual([view.solo, view.friends, effects], [true, [], []], 'whichever mode the list shows')
  assert.deepEqual(strip(snapshot)?.line, [['Playing', false], ['Parry Drill', true]])
  const left = withFrame(snapshot, { ...ready, host: { ...host, phase: 'notin', actions: ['play', 'stop'] } })
  assert.deepEqual([strip(left)?.line, strip(left)?.sub], [[['Parry Drill', true], ['is still running', false]], null])
})
