// Runs in the overlay, citadel_hud_and_db_overlay, which draws over the main-menu room, the Esc menu and the match HUD
// and lives as long as the game, so the invite toasts and the joining dialog hang here.

import { FriendWatch } from '../friends.ts'
import { type JoiningButton, viewJoining } from '../joining.ts'
import { Link } from '../link.ts'
import { type ToastView, wantedToasts } from '../toasts.ts'
import { Section, art, button, label, mark, panel, paper, spinner, tear } from '../ui.ts'
import type { Config, JoinCard, Step } from '../wire.ts'

declare const HideoutConfig: Config

function boot(): void {
  let top = $.GetContextPanel()
  while (top.GetParent()) top = top.GetParent()!
  if (top.FindChildTraverse('HideoutRoot')) return
  let overlay: Panel | null = $.GetContextPanel()
  while (overlay && overlay.id !== 'CitadelHudDBOverlay') overlay = overlay.GetParent()
  const host = $.CreatePanel('Panel', overlay ?? $.GetContextPanel(), 'HideoutRoot', { hittest: 'false' })
  host.BLoadLayout('file://{resources}/layout/hideout/root.xml', false, false)
  const find = (id: string) => host.FindChildTraverse(id)!
  paper(find('HODialog'), 700, 640, 11)

  let wasOnline = false
  const link = new Link(host, HideoutConfig, () => {
    const online = link.status === 'online'
    if (online && !wasOnline) link.send({ k: 'hello', self: null })
    wasOnline = online
    draw()
  })
  const friends = new FriendWatch(find('HOFriendMenu'), draw)

  const hidden = new Set<string>()
  const arrived: Record<string, number> = {}
  const toasts = new Map<string, { look: string; bar: Panel | null }>()
  let putAway: JoinCard['phase'] | null = null

  function drawToasts(views: ToastView[]): void {
    const stack = find('HOToasts')
    const wanted = new Set(views.map((v) => v.key))
    for (const key of toasts.keys()) {
      if (wanted.has(key)) continue
      stack.FindChildTraverse(`HOToast-${key}`)?.DeleteAsync(0)
      toasts.delete(key)
    }
    let previous: Panel | null = null
    for (const view of views) {
      const look = JSON.stringify(view.kind === 'invite' ? [view.full, view.name, view.invite] : view)
      let shown = toasts.get(view.key)
      let toastPanel = stack.FindChildTraverse(`HOToast-${view.key}`)
      if (!shown || shown.look !== look || !toastPanel) {
        toastPanel?.DeleteAsync(0)
        toastPanel = $.CreatePanel('Panel', stack, `HOToast-${view.key}`, { class: `HOToast${view.full ? '' : ' Slim'}` })
        paper(toastPanel, 580, view.full ? 130 : 70, view.key.length * 31 + 5)
        shown = { look, bar: buildToast(toastPanel, view) }
        toasts.set(view.key, shown)
      }
      if (previous) stack.MoveChildAfter(toastPanel, previous)
      previous = toastPanel
      if (view.kind === 'invite' && shown.bar) shown.bar.style.width = `${(view.left * 100).toFixed(2)}%`
    }
  }

  function buildToast(toast: Panel, view: ToastView): Panel | null {
    panel(toast, 'Grain', 'Panel', { hittest: 'false' })
    const row = panel(toast, 'HOToastRow')
    const close = () => {
      hidden.add(view.key)
      if (view.kind === 'invite') link.send({ k: 'forget', invite: view.invite.id })
      draw()
    }
    const pic = panel(row, 'HOToastArt')
    const friend = view.kind === 'invite' ? view.invite.from : view.friend
    art(pic, 'HOToastPic', view.kind === 'invite' ? view.invite.tile : null)
    $.CreatePanel('CitadelAvatarImage', pic, '', { class: 'HOToastAvatar', steamid: friend })
    if (view.kind === 'unreached') {
      line(row, [[view.name, true], ['couldn’t get your invite.', false]])
      button(row, 'SecondaryButton Outline Small Gold', 'Copy link', () => {
        $.DispatchEvent('CopyStringToClipboard', view.link, host)
        $.DispatchEvent('SteamChatWithFriend', view.friend)
        hidden.add(view.key)
        draw()
      })
    } else {
      const join = () => {
        hidden.add(view.key)
        link.send({ k: 'join', invite: view.invite.id }, (result) => {
          if (result !== 'accepted') hidden.delete(view.key)
        })
        draw()
      }
      if (view.full) {
        const text = panel(row, 'HOToastText')
        const cap = panel(text, 'CapRow')
        mark(cap, 14)
        label(cap, 'Cap', 'Hideout invite')
        line(text, [[view.name, true], ['invited you to', false]])
        label(text, 'Title HOToastMode', view.invite.mode)
        label(text, 'HOToastLine', view.line)
        button(row, 'Cream HOToastJoin', 'Join', join)
      } else {
        line(row, [[view.name, true], ['invited you to', false], [view.invite.mode, true]])
        button(row, 'SecondaryButton Outline Small Gold', 'Join', join)
      }
    }
    const x = panel(row, 'HOToastClose', 'Button')
    panel(x, 'HOCross A')
    panel(x, 'HOCross B')
    x.SetPanelEvent('onactivate', close)
    return view.kind === 'invite' && view.full ? panel(panel(toast, 'HOToastBar'), 'HOToastBarFill') : null
  }

  function line(parent: Panel, runs: [string, boolean][]): void {
    const row = panel(parent, 'HOToastRuns')
    for (const [text, bold] of runs) label(row, `HOToastFrom${bold ? ' Bold' : ''}`, text)
  }

  const banner = new Section()
  const steps = new Section()
  const box = new Section()
  const acts = new Section()

  function drawJoining(): boolean {
    const view = viewJoining(link.snapshot, putAway)
    const layer = find('HOJoining')
    layer.SetHasClass('Shown', view !== null)
    if (!view) return false
    banner.draw(find('HOJoinBanner'), JSON.stringify([view.art, view.card.mode]), (into) => {
      art(into, 'HOJoinArt', view.art)
      panel(into, 'HOJoinFade')
      tear(into, 700, view.card.mode.length * 7919 + 3)
      label(panel(into, 'Plate HOJoinPlate'), '', view.card.mode)
    })
    find('HOJoinTitle').text = view.title
    find('HOJoinLine').text = view.line
    steps.draw(find('HOSteps'), JSON.stringify([view.steps, view.busy, view.card.phase]), (into) => {
      const rows: Step[] = view.busy ? [...view.steps, { label: view.busy, note: null, progress: null, status: 'running' }] : view.steps
      for (const step of rows) {
        const look = step.status === 'done' ? 'Done' : step.status === 'running' ? 'Running' : 'Waiting'
        const row = panel(into, `HOStep ${look}`)
        const icon = panel(row, 'HOStepIcon')
        if (step.status === 'done') panel(icon, 'HOCheck')
        if (step.status === 'running') spinner(icon)
        const text = panel(row, 'HOStepText')
        label(text, 'HOStepLabel', step.label)
        if (step.note) label(text, 'HOStepNote', step.note)
        if (step.status === 'running' && step.progress !== null) {
          panel(panel(text, 'HOProgress'), 'HOProgressFill', 'Panel', { style: `width: ${(step.progress * 100).toFixed(1)}%;` })
        }
      }
    })
    box.draw(find('HOBox'), JSON.stringify(view.box), (into) => {
      if (!view.box) return
      const b = panel(into, `HOBox${view.box.tone === 'note' ? ' Note' : ''}`)
      label(b, 'HOBoxTitle', view.box.title)
      label(b, 'HOBoxBody', view.box.body)
    })
    acts.draw(find('HOActs'), JSON.stringify(view.buttons), (into) => {
      for (const b of view.buttons) button(into, b.primary ? 'Cream HORetry' : 'SecondaryButton Outline', b.label, () => press(b, view.card.phase))
    })
    return true
  }

  function press(b: JoiningButton, phase: JoinCard['phase']): void {
    if (b.kind === 'confirm') return link.send({ k: 'confirm', restart: b.restart })
    if (b.action === 'dismiss') {
      putAway = phase
      draw()
    }
    link.send({ k: 'act', action: b.action })
  }

  function draw(): void {
    const now = Date.now()
    for (const invite of link.snapshot.inbox?.invites ?? []) arrived[invite.id] ??= now
    if (link.snapshot.game?.join?.phase !== putAway) putAway = null
    const joining = drawJoining()
    const games = top.FindChildTraverse('HideoutGames')
    find('HOToasts').SetHasClass('Away', joining || (games !== null && games.visible))
    drawToasts(wantedToasts(link.snapshot, friends.current, now, arrived, hidden))
  }

  // The countdown bars move on their own clock.
  function tick(): void {
    if (!host.IsValid()) return
    draw()
    $.Schedule(0.25, tick)
  }
  tick()
  $.Msg('[hideout] in-game UI started')
}

boot()
