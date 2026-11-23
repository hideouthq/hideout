// The Hideout games popup, opened from the Custom Match band with UIShowCustomLayoutPopupParameters.

import { FriendWatch } from '../friends.ts'
import { type GamesEffect, type GamesEvent, type GamesView, type ServersView, initialGames, reduceGames, refusalText, viewGames } from '../games.ts'
import { Link } from '../link.ts'
import { Section, art, button, label, mark, panel, spinner, tear } from '../ui.ts'
import type { Config, Listing, ModeId } from '../wire.ts'

declare const HideoutConfig: Config

const root = $.GetContextPanel()
const find = (id: string) => root.FindChildTraverse(id)!
const me = find('HGMe')
let state = initialGames(root.GetAttributeString('tab', 'games') === 'servers' ? 'servers' : 'games')

const link = new Link(root, HideoutConfig, () => dispatch({ kind: 'seen' }))
const friends = new FriendWatch(find('HGFriendMenu'), render)

for (const [id, size] of [['HGTitleRow', 40], ['HGMore', 16]] as const) {
  const row = find(id)
  row.MoveChildBefore(mark(row, size), row.GetChild(0)!)
}
find('HGTabGames').SetPanelEvent('onactivate', () => dispatch({ kind: 'tab', tab: 'games' }))
find('HGTabServers').SetPanelEvent('onactivate', () => dispatch({ kind: 'tab', tab: 'servers' }))

function dispatch(event: GamesEvent): void {
  const [next, effects] = reduceGames(state, event, link.snapshot)
  const wasServers = state.tab === 'servers'
  state = next
  effects.forEach(run)
  if (state.tab === 'servers' && !wasServers) link.send({ k: 'servers' })
  render()
}

function run(effect: GamesEffect): void {
  switch (effect.kind) {
    case 'send':
      link.send(effect.command, (result) => dispatch({ kind: 'settled', what: effect.what, result }))
      break
    case 'copy-and-chat':
      $.DispatchEvent('CopyStringToClipboard', effect.link, root)
      $.DispatchEvent('SteamChatWithFriend', effect.friend)
      break
  }
}

function nameOf(panel: Panel): string {
  if (panel.paneltype === 'Label' && panel.text) return panel.text
  for (let i = 0; i < panel.GetChildCount(); i++) {
    const text = nameOf(panel.GetChild(i)!)
    if (text) return text
  }
  return ''
}

const list = new Section()
const banner = new Section()
const chips = new Section()
const settings = new Section()
const friendRows = new Section()
const hint = new Section()
const play = new Section()
const servers = new Section()

function render(): void {
  const view = viewGames(state, link.snapshot, link.status, friends.current, nameOf(me))
  root.SetHasClass('Games', view.tab === 'games')
  root.SetHasClass('Servers', view.tab === 'servers')
  find('HGTabGames').SetHasClass('On', view.tab === 'games')
  find('HGTabServers').SetHasClass('On', view.tab === 'servers')
  find('HGGamesCount').text = view.counts.games ? String(view.counts.games) : ''
  find('HGServersCount').text = view.counts.servers === null ? '' : String(view.counts.servers)
  drawList(view)
  drawDetail(view)
  drawServers(view.servers, view.link === 'online' && link.snapshot.hello?.social !== undefined)
}

function drawList(view: GamesView): void {
  const key = JSON.stringify(view.rows.map((r) => [r.mode.id, r.mode.name, r.mode.tagline, r.meta, r.mode.art, r.selected]))
  list.draw(find('HGList'), key, (into) => {
    if (!view.rows.length) {
      const empty = panel(into, 'HGRow')
      spinner(empty, 'HGEmptySpinner')
      return
    }
    for (const row of view.rows) {
      const b = panel(into, `HGRow${row.selected ? ' On' : ''}`, 'Button')
      art(b, 'HGThumb', row.mode.art.card ?? row.mode.art.tile ?? row.mode.art.backdrop)
      const text = panel(b, 'HGRowText')
      label(text, 'Title HGRowName', row.mode.name)
      label(text, 'HGRowDesc', row.mode.tagline)
      label(text, 'HGRowMeta', row.meta)
      b.SetPanelEvent('onactivate', () => dispatch({ kind: 'mode', mode: row.mode.id as ModeId }))
    }
  })
}

function drawDetail(view: GamesView): void {
  const mode = view.selected
  find('HGDetail').visible = mode !== null
  if (!mode) return
  banner.draw(find('HGBanner'), JSON.stringify([mode.id, mode.name, mode.art, view.byline]), (into) => {
    art(into, 'HGBannerArt', mode.art.backdrop ?? mode.art.card ?? mode.art.tile)
    panel(into, 'HGBannerFade')
    tear(into, 830, mode.id.length * 7919 + 17)
    label(panel(into, 'Plate HGBannerPlate'), '', mode.name)
    label(into, 'HGBannerBy', view.byline)
  })
  find('HGDesc').text = mode.tagline
  chips.draw(find('HGChips'), JSON.stringify(view.chips), (into) => {
    for (const chip of view.chips) {
      const c = panel(into, 'HGChip')
      const icon = panel(c, `HGChipIcon ${chip.icon === 'kind' ? 'Kind' : chip.icon === 'players' ? 'Players' : 'Time'}`)
      if (chip.icon === 'kind') {
        panel(icon, 'FlagPole')
        panel(icon, 'FlagCloth')
      }
      label(c, '', chip.text)
    }
  })
  settings.draw(find('HGSettings'), JSON.stringify([view.settings, view.listing, view.listingLine]), (into) => {
    for (const { setting, value } of view.settings) {
      const options = setting.options.map((o) => ({ label: o.label, on: o.value === value, press: () => dispatch({ kind: 'pick', key: setting.key, value: o.value }) }))
      settingRow(into, setting.label, setting.hint ?? '', options)
    }
    const listings: [Listing, string][] = [
      ['invite-only', 'Invite only'],
      ['public', 'Public'],
    ]
    settingRow(
      into,
      'Who can join',
      view.listingLine,
      listings.map(([listing, text]) => ({ label: text, on: view.listing === listing, press: () => dispatch({ kind: 'listing', listing }) })),
    )
  })
  find('HGOnline').text = `${view.friends.length} online`
  friendRows.draw(find('HGFriends'), JSON.stringify(view.friends), (into) => {
    if (!view.friends.length) label(into, 'HGFriendsEmpty', 'No friends online right now.')
    for (const { friend, button: kind } of view.friends) {
      const row = panel(into, 'HGFriend')
      $.CreatePanel('CitadelAvatarImage', row, '', { class: 'HGAvatar', steamid: friend.id })
      const text = panel(row, 'HGFriendText')
      label(text, 'HGFriendName', friend.name)
      label(text, 'HGFriendStatus', friend.status || 'Online')
      const press = kind === 'off' ? null : () => dispatch({ kind: 'friend', id: friend.id })
      const look = kind === 'invited' ? 'Done' : kind === 'off' ? '' : 'Gold'
      const text2 = kind === 'invited' ? 'Invited' : kind === 'copy-link' ? 'Copy link' : 'Invite'
      button(row, `SecondaryButton Outline Small ${look}`, text2, press)
    }
  })
  find('HGOffline').text = view.offline ? `${view.offline} ${view.offline === 1 ? 'friend' : 'friends'} offline` : ''
  hint.draw(find('HGHint'), view.play.hint, (into) => {
    if (!view.play.hint) return
    mark(into, 20)
    label(into, 'HGHintText', view.play.hint)
  })
  play.draw(find('HGPlay'), JSON.stringify(view.play), (into) => {
    const inner = panel(into, 'HGPlayInner')
    if (view.play.kind === 'busy') spinner(inner, 'Dark')
    label(inner, '', view.play.label)
    const p = view.play
    into.enabled = p.kind === 'play' || p.kind === 'act'
    into.SetPanelEvent('onactivate', () => {
      if (p.kind === 'play') dispatch({ kind: 'play' })
      else if (p.kind === 'act') dispatch({ kind: 'act', action: p.action })
    })
  })
}

function settingRow(into: Panel, name: string, hintText: string, options: { label: string; on: boolean; press: () => void }[]): void {
  const row = panel(into, 'HGSettingRow')
  const text = panel(row, 'HGSettingText')
  label(text, 'HGSettingName', name)
  if (hintText) label(text, 'HGSettingHint', hintText)
  const seg = panel(row, 'HGSeg')
  for (const option of options) button(seg, `HGSegOption${option.on ? ' On' : ''}`, option.label, option.press)
}

function drawServers(view: ServersView, reachable: boolean): void {
  const refusal = state.request.kind === 'refused' && state.request.what === 'join' ? state.request.result : null
  servers.draw(find('HGServerRows'), JSON.stringify([view, reachable, refusal]), (into) => {
    if (view.state === 'idle' || view.state === 'loading') {
      if (!view.lines.length) spinner(panel(into, 'HGServersLoading'))
    }
    for (const line of view.lines) {
      const row = panel(into, `HGServer${line.joinable ? '' : ' Full'}`)
      const name = panel(row, 'HGColName HGServerName')
      art(name, 'HGServerThumb', line.row.tile)
      const text = panel(name, 'HGServerText')
      label(text, 'HGServerTitle', line.name)
      label(text, 'HGServerSub', line.sub)
      label(row, 'HGColRegion HGServerCell', line.region)
      const players = panel(row, 'HGColPlayers HGServerPlayers')
      panel(panel(players, 'HGBar'), 'HGBarFill', 'Panel', { style: `width: ${Math.round(line.fill * 100)}%;` })
      label(players, 'HGServerCell', `${line.row.players[0]} / ${line.row.players[1]}`)
      label(row, `HGColPing HGServerCell HGPing ${line.ping ? line.ping.tone[0]!.toUpperCase() + line.ping.tone.slice(1) : ''}`, line.ping?.text ?? '–')
      label(row, 'HGColStatus HGServerStatus', line.status)
      button(row, `SecondaryButton Outline Small ${line.joinable ? 'Gold' : ''} HGServerJoin`, line.joinable ? 'Join' : 'Full', line.joinable ? () => dispatch({ kind: 'join', server: line.row.id }) : null)
    }
    const note = find('HGServerNote')
    note.RemoveAndDeleteChildren()
    const text = refusal ? refusalText(refusal) : view.note ?? 'Hideout servers are always on. Player servers close when the host leaves.'
    mark(note, 16)
    label(note, 'HGMoreText', reachable || view.note ? text : 'Open Hideout to see servers.')
  })
}

// The Servers tab keeps the app's list fresh while it shows.
function refreshServers(): void {
  if (!root.IsValid()) return
  if (state.tab === 'servers' && link.status === 'online') link.send({ k: 'servers' })
  $.Schedule(15, refreshServers)
}
$.Schedule(15, refreshServers)
if (state.tab === 'servers') link.send({ k: 'servers' })
render()
