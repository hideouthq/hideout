// Runs in the stock Custom Match popup, which the pack shadows with one added include. It puts the Hideout games band
// under Valve's part of the dialog. The band opens our popup, which only a popup can do: from the overlay the same
// event does nothing.

import { Link } from '../link.ts'
import { Section, art, mark } from '../ui.ts'
import type { Config } from '../wire.ts'

declare const HideoutConfig: Config

const dialog = $.GetContextPanel()
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
