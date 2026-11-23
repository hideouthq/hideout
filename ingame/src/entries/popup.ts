// Runs in the stock Custom Match popup. The Hideout games band lives here because only a popup can open our popup: from
// the overlay the same event does nothing.

import { Link } from '../link.ts'
import { Section, art, mark } from '../ui.ts'
import type { Config } from '../wire.ts'

declare const HideoutConfig: Config

const dialog = $.GetContextPanel()
// The band runs edge to edge along the bottom, so the dialog gives up its side and bottom padding. Valve's centred part
// doesn't move.
dialog.style.padding = '32px 0px 0px 0px'
const band = $.CreatePanel('Button', dialog, 'HideoutBand')
band.BLoadLayout('file://{resources}/layout/hideout/band.xml', false, false)
const cap = band.FindChildTraverse('HBandCap')!
cap.MoveChildBefore(mark(cap, 18), cap.GetChild(0)!)
band.SetPanelEvent('onactivate', () => {
  $.DispatchEvent('UIShowCustomLayoutPopupParameters', 'HideoutGames', 'file://{resources}/layout/hideout/games.xml', 'tab=games')
})

const ANGLES = [
  [0, -10],
  [30, 2],
  [62, 12],
]
const hand = new Section()
const link = new Link(band, HideoutConfig, draw)

function draw(): void {
  const modes = link.snapshot.catalog?.modes ?? []
  const shown = modes.slice(0, 3)
  hand.draw(band.FindChildTraverse('HBandHand')!, JSON.stringify(shown.map((m) => m.art)), (into) => {
    shown.forEach((mode, i) => {
      const [x, angle] = ANGLES[i]!
      art(into, 'HBandCard', mode.art.card ?? mode.art.tile ?? mode.art.backdrop, `x: ${x}px; transform: rotateZ(${angle}deg);`)
    })
  })
  const offline = link.status === 'offline'
  band.FindChildTraverse('HBandSub')!.text = offline ? 'Open Hideout to play' : 'Community modes to play with friends'
}

draw()
