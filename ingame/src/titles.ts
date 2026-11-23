// The bridge page's title protocol, read without Panorama so node can test it. The page streams the endpoint's lines
// "~<seq>:<packed>" into titles "modlock:frame:<id>:<offset>:<total>:<seq>:<chunk>" and sends the next part when the
// fragment flips between #0 and #1. An empty title means it has no stream.

const PART = 'modlock:frame:'
/** Modlock's relay keepalive. Acknowledged like a part, then ignored. */
const LINE = 'modlock:snapshot:'

export type Read =
  /** A part to acknowledge. [line] is set once it completes one, as text. */
  | { kind: 'part'; line: string | null }
  /** A title already read, which navigating to a fragment repeats. */
  | { kind: 'repeat' }
  /** The page has no stream: the app is closed or refused it. */
  | { kind: 'offline' }
  /** The page loaded and is still reaching the app. */
  | { kind: 'waiting' }
  /** A part that doesn't follow the one before. The page times it out and sends the line again. */
  | { kind: 'broken' }

export class TitleReader {
  private parts: { id: number; total: number; text: string } | null = null
  private last = ''

  read(title: string): Read {
    if (title.startsWith(LINE)) {
      if (title === this.last) return { kind: 'repeat' }
      this.last = title
      return { kind: 'part', line: null }
    }
    if (!title.startsWith(PART)) {
      this.parts = null
      this.last = ''
      return title === '' ? { kind: 'offline' } : { kind: 'waiting' }
    }
    if (title === this.last) return { kind: 'repeat' }
    this.last = title
    const fields = title.slice(PART.length).split(':', 4)
    const [id, offset, total] = fields.map(Number)
    const chunk = title.slice(PART.length + fields.join(':').length + 1)
    if (fields.length !== 4 || !id || !total || offset === undefined) {
      this.parts = null
      return { kind: 'broken' }
    }
    if (offset === 0) this.parts = { id, total, text: '' }
    const parts = this.parts
    if (!parts || parts.id !== id || parts.text.length !== offset) {
      this.parts = null
      return { kind: 'broken' }
    }
    parts.text += chunk
    if (parts.text.length < parts.total) return { kind: 'part', line: null }
    this.parts = null
    const line = text(parts.text)
    return line === null ? { kind: 'broken' } : { kind: 'part', line }
  }
}

/** Modlock's packing: printable ASCII but the backslash stands for itself, any other byte is U+0100 plus it. */
function unpack(packed: string): Uint8Array | null {
  const bytes = new Uint8Array(packed.length)
  for (let i = 0; i < packed.length; i++) {
    const code = packed.charCodeAt(i)
    if (code >= 1 && code <= 127) bytes[i] = code
    else if (code >= 0x100 && code <= 0x1ff) bytes[i] = code - 0x100
    else return null
  }
  return bytes
}

/** UTF-8 without TextDecoder, which Panorama lacks. Null on malformed bytes. */
function utf8(bytes: Uint8Array): string | null {
  let latin = ''
  for (let at = 0; at < bytes.length; at += 4096) latin += String.fromCharCode.apply(null, bytes.subarray(at, at + 4096) as unknown as number[])
  try {
    return decodeURIComponent(escape(latin))
  } catch {
    return null
  }
}

function text(packed: string): string | null {
  const bytes = unpack(packed)
  return bytes && utf8(bytes)
}
