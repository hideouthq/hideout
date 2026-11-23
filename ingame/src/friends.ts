// Steam friends, read from a CitadelFriendMenu the layout embeds, which fills its groups once its Hidden class is off.

import type { SteamId } from './wire.ts'

export type Presence = 'playing' | 'online' | 'offline'

export interface Friend {
  id: SteamId
  name: string
  status: string
  presence: Presence
}

export interface Friends {
  online: Friend[]
  offline: number
  /** Offline friends too. Only these may show an invite. */
  names: Record<string, string>
}

const NO_FRIENDS: Friends = { online: [], offline: 0, names: {} }

const GROUPS: { id: string; presence: Presence }[] = [
  { id: 'FriendsPlayingCitadel', presence: 'playing' },
  { id: 'FriendsOnline', presence: 'online' },
  { id: 'FriendsOffline', presence: 'offline' },
]

export interface RawFriend {
  steamid: string
  name: string
  status: string
  presence: Presence
  currentUser: boolean
}

export function friendsOf(raw: RawFriend[]): Friends {
  const seen = new Set<string>()
  const online: Friend[] = []
  const names: Record<string, string> = {}
  let offline = 0
  for (const f of raw) {
    if (f.currentUser || !/^\d{17}$/.test(f.steamid) || seen.has(f.steamid)) continue
    seen.add(f.steamid)
    names[f.steamid] = f.name
    if (f.presence === 'offline') offline++
    else online.push({ id: f.steamid as SteamId, name: f.name, status: f.status, presence: f.presence })
  }
  return { online, offline, names }
}

export function textOf(panel: Panel | null): string {
  if (!panel) return ''
  if (panel.paneltype === 'Label' && panel.text) return panel.text
  for (let i = 0; i < panel.GetChildCount(); i++) {
    const text = textOf(panel.GetChild(i))
    if (text) return text
  }
  return ''
}

function friendPanels(root: Panel, out: Panel[] = []): Panel[] {
  if (root.paneltype === 'CitadelFriend') out.push(root)
  for (let i = 0; i < root.GetChildCount(); i++) {
    const child = root.GetChild(i)
    if (child) friendPanels(child, out)
  }
  return out
}

function readMenu(menu: Panel): RawFriend[] {
  const raw: RawFriend[] = []
  for (const group of GROUPS) {
    const box = menu.FindChildTraverse(group.id)
    if (!box) continue
    for (const f of friendPanels(box)) {
      raw.push({
        steamid: String(f.steamid),
        name: textOf(f.FindChildTraverse('FriendName')),
        status: textOf(f.FindChildTraverse('FriendRichPresence')),
        presence: group.presence,
        currentUser: f.BHasClass('CurrentUser'),
      })
    }
  }
  return raw
}

export class FriendWatch {
  current: Friends = NO_FRIENDS
  private key = ''

  constructor(menu: Panel, changed: () => void) {
    menu.RemoveClass('Hidden')
    const read = () => {
      const now = friendsOf(readMenu(menu))
      const key = JSON.stringify(now)
      if (key === this.key) return false
      this.key = key
      this.current = now
      return true
    }
    const tick = () => {
      if (!menu.IsValid()) return
      if (read()) changed()
      $.Schedule(2, tick)
    }
    read()
    $.Schedule(2, tick)
  }
}
