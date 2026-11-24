// The overlay, the Custom Match band and the games popup each run their own script context, so each makes its own Link.
// Down: a hidden HTML panel loads the bridge page and reads the app's frames from its titles. Up: each command is one
// Image panel loading /c, and the loaded width is the answer.

import { TitleReader } from './titles.ts'
import { type Command, type CommandResult, type Config, EMPTY, PROTOCOL, type Snapshot, decodeFrame, resultOfWidth, withFrame } from './wire.ts'

export type LinkStatus = 'connecting' | 'online' | 'offline' | 'outdated'

/** Must outlast the app's 10 s keepalive. */
const STALE = 25
const LOAD_DEADLINE = 10
/** The size an image reports right at ImageLoaded can be stale. */
const SIZE_DELAY = 0.1
const COMMAND_DEADLINE = 8

export class Link {
  snapshot: Snapshot = EMPTY
  status: LinkStatus = 'connecting'
  private readonly titles = new TitleReader()
  private readonly html: Panel
  private readonly boot = String(Math.floor(Math.random() * 1e9))
  private sequence = 0
  private acked = false
  private watchdog: number | undefined
  private readonly queue: { command: Command; done: (result: CommandResult) => void }[] = []
  private sending = false
  private readonly host: Panel
  private readonly config: Config
  private readonly changed: () => void

  constructor(host: Panel, config: Config, changed: () => void) {
    this.host = host
    this.config = config
    this.changed = changed
    this.html = $.CreatePanel('HTML', host, '', { hittest: 'false', style: 'width: 2px; height: 2px; opacity: 0.01;' })
    $.RegisterEventHandler('HTMLStartRequest', this.html, () => false)
    $.RegisterEventHandler('HTMLTitle', this.html, (_: never, title: string) => {
      this.read(title)
      return false
    })
    this.load(LOAD_DEADLINE)
  }

  send(command: Command, done: (result: CommandResult) => void = () => {}): void {
    this.queue.push({ command, done })
    if (!this.sending) this.next()
  }

  private load(deadline: number): void {
    this.html.SetURL(this.config.page)
    this.arm(deadline)
  }

  private arm(seconds: number): void {
    if (this.watchdog !== undefined) $.CancelScheduled(this.watchdog)
    this.watchdog = $.Schedule(seconds, () => {
      this.watchdog = undefined
      this.load(LOAD_DEADLINE)
    })
  }

  private acknowledge(): void {
    this.acked = !this.acked
    this.html.SetURL(`${this.config.page}#${this.acked ? 1 : 0}`)
  }

  private read(title: string): void {
    const read = this.titles.read(title)
    switch (read.kind) {
      case 'repeat':
        return
      case 'waiting':
        this.arm(STALE)
        return
      case 'offline':
        this.arm(STALE)
        return this.become('offline')
      case 'broken':
        $.Msg('[hideout] a frame part came out of order')
        return
      case 'part':
        this.arm(STALE)
        this.acknowledge()
        if (read.line === null) return
        const frame = decodeFrame(read.line)
        if (!frame) return void $.Msg('[hideout] could not read a frame')
        this.snapshot = withFrame(this.snapshot, frame)
        if (frame.t === 'alive' && this.status !== 'connecting' && this.status !== 'offline') return
        this.status = this.snapshot.hello && this.snapshot.hello.protocol !== PROTOCOL ? 'outdated' : 'online'
        this.changed()
    }
  }

  private become(status: LinkStatus): void {
    if (this.status === status) return
    this.status = status
    this.changed()
  }

  private next(): void {
    const item = this.queue.shift()
    if (!item) return void (this.sending = false)
    this.sending = true
    const query = [
      `p=${PROTOCOL}`,
      `t=${this.config.token}`,
      `e=${this.snapshot.hello?.epoch ?? ''}`,
      `b=${this.boot}`,
      `n=${this.sequence++}`,
      `c=${encodeURIComponent(JSON.stringify(item.command))}`,
    ].join('&')
    // An image at opacity 0 never loads.
    const image = $.CreatePanel('Image', this.host, '', {
      src: `http://127.0.0.1:${this.config.port}/c?${query}`,
      hittest: 'false',
      style: 'opacity: 0.01; width: 1px; height: 1px;',
    })
    let settled = false
    const finish = (result: CommandResult) => {
      if (settled) return
      settled = true
      image.DeleteAsync(0)
      try {
        item.done(result)
      } finally {
        this.next()
      }
    }
    $.RegisterEventHandler('ImageLoaded', image, () => {
      $.Schedule(SIZE_DELAY, () => finish(resultOfWidth(image.contentwidth, image.actualuiscale_x)))
    })
    $.RegisterEventHandler('ImageFailedLoad', image, () => finish('unreachable'))
    $.Schedule(COMMAND_DEADLINE, () => finish('unreachable'))
  }
}
