// Runs in the HUD, from the news layout the menus pack already shadows for the renderer. The overlay's script can't
// reach the HUD's panels, and the Esc menu is one of them, so this context adds Hideout's entries to it with its own Link.
// The entries are stock buttons in the menu's own lists, so they take its look, hover and sounds.

import { type EscEntry, viewEscEntries } from '../escmenu.ts'
import { Link } from '../link.ts'
import type { Action, Config, Game } from '../wire.ts'

declare const HideoutConfig: Config

type Row = { button: Panel; label: Panel }

function start(): void {
  let root = $.GetContextPanel()
  while (root.GetParent()) root = root.GetParent()!
  if (root.FindChildTraverse('HideoutEscRoot')) return
  const host = $.CreatePanel('Panel', root, 'HideoutEscRoot', { hittest: 'false' })
  const link = new Link(host, HideoutConfig, draw)
  let pressed: { in: Game | null } | null = null
  const rows = new Map<Action, Row>()

  function draw(): void {
    if (pressed && pressed.in !== link.snapshot.game) pressed = null
    const entries = viewEscEntries(link.snapshot, link.status, pressed !== null)
    for (const [action, row] of rows) if (!row.button.IsValid()) rows.delete(action)
    for (const [action, row] of rows) row.button.visible = entries.some((e) => e.action === action)
    let previous: Panel | null = null
    for (const entry of entries) {
      const row = rows.get(entry.action) ?? build(entry)
      if (!row) return
      rows.set(entry.action, row)
      if (previous) row.button.GetParent()!.MoveChildAfter(row.button, previous)
      previous = row.button
      row.button.visible = true
      row.label.text = entry.label
      row.button.enabled = !entry.busy
    }
  }

  /**
   * Under the match's own Leave and Rejoin, in the list that grows down from the top. The list at the bottom grows up
   * into Swap Hero, which other mods' rows already crowd. Null until the HUD has built the menu.
   */
  function build(entry: EscEntry): Row | null {
    const list = root.FindChildTraverse('EscapeMenu')?.FindChildTraverse('ContextualMenu')
    if (!list) return null
    const button = $.CreatePanel('Button', list, `HideoutEsc-${entry.action}`, { class: 'nav_menu_item minor' })
    const label = $.CreatePanel('Label', button, '', { class: 'menuButtonLabel' })
    button.SetPanelEvent('onactivate', () => act(entry.action))
    return { button, label }
  }

  function act(action: Action): void {
    pressed = { in: link.snapshot.game }
    link.send({ k: 'act', action }, (result) => {
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
  $.Msg('[hideout] Esc menu entries started')
}

start()
