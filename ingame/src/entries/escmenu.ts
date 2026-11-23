// Runs in the HUD, from the news layout the menus pack already shadows for the renderer. The overlay's script can't
// reach the HUD's panels, and the Esc menu is one of them, so this context adds Hideout's entry to it with its own Link.
// The entry is a stock button in the stock list, so it takes the menu's own look, hover and sounds.

import { viewEscEntry } from '../escmenu.ts'
import { Link } from '../link.ts'
import type { Config, Game } from '../wire.ts'

declare const HideoutConfig: Config

const ENTRY = 'HideoutEscEntry'

function start(): void {
  let root = $.GetContextPanel()
  while (root.GetParent()) root = root.GetParent()!
  if (root.FindChildTraverse('HideoutEscRoot')) return
  const host = $.CreatePanel('Panel', root, 'HideoutEscRoot', { hittest: 'false' })
  const link = new Link(host, HideoutConfig, draw)
  let pressed: { in: Game | null } | null = null
  let entry: { button: Panel; label: Panel } | null = null

  function draw(): void {
    if (pressed && pressed.in !== link.snapshot.game) pressed = null
    const view = viewEscEntry(link.snapshot, link.status, pressed !== null)
    if (entry && !entry.button.IsValid()) entry = null
    if (!view) {
      if (entry) entry.button.GetParent()!.visible = false
      return
    }
    entry ??= build()
    if (!entry) return
    entry.button.GetParent()!.visible = true
    entry.label.text = view.label
    entry.button.enabled = !view.busy
  }

  /** Between Player Feedback and Settings, where other mods' entries sit too. Null until the HUD has built the menu. */
  function build(): { button: Panel; label: Panel } | null {
    const list = root.FindChildTraverse('EscapeMenu')?.FindChildTraverse('SubOptions')
    if (!list) return null
    const row = $.CreatePanel('Panel', list, ENTRY)
    const button = $.CreatePanel('Button', row, '', { class: 'nav_menu_item minor' })
    const label = $.CreatePanel('Label', button, '', { class: 'menuButtonLabel' })
    button.SetPanelEvent('onactivate', stop)
    const rows = Array.from({ length: list.GetChildCount() }, (_, i) => list.GetChild(i))
    const settings = rows.find((p) => p?.BHasClass('SettingsRow'))
    const feedback = rows.find((p) => p?.BHasClass('FeedbackRow'))
    if (settings) list.MoveChildBefore(row, settings)
    else if (feedback) list.MoveChildAfter(row, feedback)
    return { button, label }
  }

  function stop(): void {
    pressed = { in: link.snapshot.game }
    link.send({ k: 'act', action: 'stop' }, (result) => {
      if (result === 'accepted') return
      pressed = null
      draw()
    })
    draw()
  }

  // The HUD can rebuild the menu, and a match's HUD can come up after the frame that started the session.
  function tick(): void {
    if (!host.IsValid()) return
    draw()
    $.Schedule(1, tick)
  }
  tick()
  $.Msg('[hideout] Esc menu entry started')
}

start()
