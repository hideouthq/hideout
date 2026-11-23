// Small helpers for building panels from script, shared by every screen.

export function panel(parent: Panel, cls: string, type = 'Panel', props: Record<string, string> = {}): Panel {
  return $.CreatePanel(type, parent, '', { class: cls, ...props })
}

export function label(parent: Panel, cls: string, text: string): Panel {
  return $.CreatePanel('Label', parent, '', { class: cls, text })
}

/** Mode art from the feed, or the plain backdrop when the mode has none. */
export function art(parent: Panel, cls: string, url: string | null, style = ''): Panel {
  if (!url) return panel(parent, `${cls} NoArt`, 'Panel', { style })
  return $.CreatePanel('Image', parent, '', { class: `${cls} Art`, src: url, scaling: 'stretch-to-cover-preserve-aspect', style })
}

export function button(parent: Panel, cls: string, text: string, press: (() => void) | null): Panel {
  const b = $.CreatePanel('Button', parent, '', { class: cls })
  label(b, '', text)
  if (press) b.SetPanelEvent('onactivate', press)
  else b.enabled = false
  return b
}

export function spinner(parent: Panel, cls = ''): Panel {
  return panel(parent, `HSpinner ${cls}`)
}

type Part = { x: number; y: number; w: number; h: number; color: string; round?: number; rotate?: number; origin?: string; smile?: boolean }

const BACK: Part = { x: 15.5, y: 12.5, w: 25, h: 37, round: 3.4, color: '#F1DFB8', rotate: -14, origin: '50% 115%' }
const FRONT: Part = { x: 21.3, y: 10.3, w: 29.4, h: 41.4, round: 5.4, color: '#141313', rotate: 9, origin: '50% 108%' }
/** Inside FRONT, in FRONT's own coordinates, so they turn with it. */
const FACE: Part[] = [
  { x: 2.2, y: 2.2, w: 25, h: 37, round: 3.4, color: '#F1DFB8' },
  { x: 6.5, y: 9.6, w: 16.4, h: 16.4, round: 8.2, color: '#2F8F5B' },
  { x: 8.6, y: 14.3, w: 12.1, h: 12.1, round: 6, color: 'transparent', smile: true },
  { x: 7.6, y: 30.5, w: 14.3, h: 2.3, round: 1.1, color: '#141313' },
  { x: 9.1, y: 27.5, w: 2.3, h: 4.2, round: 1.1, color: '#141313', rotate: 28 },
  { x: 18.3, y: 27.5, w: 2.3, h: 4.2, round: 1.1, color: '#141313', rotate: -28 },
]

function part(parent: Panel, p: Part, k: number): Panel {
  const px = (v: number) => `${(v * k).toFixed(2)}px`
  const style = [
    `x: ${px(p.x)}`,
    `y: ${px(p.y)}`,
    `width: ${px(p.w)}`,
    `height: ${px(p.h)}`,
    `background-color: ${p.color}`,
    p.round ? `border-radius: ${px(p.round)}` : '',
    p.smile ? `border-bottom: ${px(2.3)} solid #141313` : '',
    p.rotate ? `transform: rotateZ(${p.rotate}deg)` : '',
    p.origin ? `transform-origin: ${p.origin}` : '',
  ]
  return panel(parent, 'MarkPart', 'Panel', { style: style.filter(Boolean).join('; ') + ';' })
}

/** Hideout's mark, two cards with the smiling green face, drawn from panels at [size] px. */
export function mark(parent: Panel, size: number): Panel {
  const k = size / 64
  const box = panel(parent, 'Mark', 'Panel', { style: `width: ${size}px; height: ${size}px;` })
  part(box, BACK, k)
  const front = part(box, FRONT, k)
  for (const p of FACE) part(front, p, k)
  return box
}

/** Paper's slightly torn outline: a body inset by 2 px, and paper teeth up to 2 px deep along every edge. */
export function paper(into: Panel, width: number, height: number, seed: number): Panel {
  const edge = panel(into, 'PaperEdge')
  panel(edge, 'PaperBody', 'Panel', { style: `x: 2px; y: 2px; width: ${width - 4}px; height: ${height - 4}px;` })
  let s = seed
  const depth = () => {
    s = (s * 16807) % 2147483647
    return s % 3
  }
  for (let x = 2; x < width - 2; x += 26) {
    const w = Math.min(26, width - 2 - x)
    panel(edge, 'PaperTooth', 'Panel', { style: `x: ${x}px; y: ${2 - depth()}px; width: ${w}px; height: 2px;` })
    panel(edge, 'PaperTooth', 'Panel', { style: `x: ${x}px; y: ${height - 4 + depth()}px; width: ${w}px; height: 2px;` })
  }
  for (let y = 2; y < height - 2; y += 26) {
    const h = Math.min(26, height - 2 - y)
    panel(edge, 'PaperTooth', 'Panel', { style: `x: ${2 - depth()}px; y: ${y}px; width: 2px; height: ${h}px;` })
    panel(edge, 'PaperTooth', 'Panel', { style: `x: ${width - 4 + depth()}px; y: ${y}px; width: 2px; height: ${h}px;` })
  }
  into.MoveChildBefore(edge, into.GetChild(0)!)
  return edge
}

/** A 7 px torn edge along a banner's bottom, in the paper's colour. */
export function tear(parent: Panel, width: number, seed: number): Panel {
  const strip = panel(parent, 'Tear')
  let s = seed
  for (let x = 0; x < width; x += 11) {
    s = (s * 16807) % 2147483647
    panel(strip, 'Tooth', 'Panel', { style: `height: ${1 + (s % 7)}px;` })
  }
  return strip
}

/** Redraws [into] only when [key] changed since the last draw. */
export class Section {
  private last = '\u0000'
  draw(into: Panel, key: string, build: (into: Panel) => void): void {
    if (key === this.last) return
    this.last = key
    into.RemoveAndDeleteChildren()
    build(into)
  }
}
