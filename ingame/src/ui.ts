// Small helpers for building panels from script, shared by every screen.

export function panel(parent: Panel, cls: string, type = 'Panel', props: Record<string, string> = {}): Panel {
  return $.CreatePanel(type, parent, '', { class: cls, ...props })
}

export function label(parent: Panel, cls: string, text: string): Panel {
  return $.CreatePanel('Label', parent, '', { class: cls, text })
}

/** A label whose text holds <b> runs. Every value in it must come through esc. */
export function rich(parent: Panel, cls: string, html: string): Panel {
  return $.CreatePanel('Label', parent, '', { class: cls, html: 'true', text: html })
}

export function esc(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
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

/** Hideout's mark, two cards with the smiling green face, drawn from panels at [size] px. */
export function mark(parent: Panel, size: number): Panel {
  const box = panel(parent, 'Mark', 'Panel', { style: `width: ${size}px; height: ${size}px;` })
  const art = panel(box, 'MarkArt', 'Panel', { style: `transform: scale3d(${size / 64}, ${size / 64}, 1);` })
  panel(art, 'MarkBack')
  const front = panel(panel(art, 'MarkFront'), 'MarkFace')
  panel(front, 'MarkHead')
  panel(front, 'MarkSmile')
  panel(front, 'MarkSeat')
  panel(front, 'MarkLeg Left')
  panel(front, 'MarkLeg Right')
  return box
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
