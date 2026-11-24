import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const fixture = (name: string) => readFileSync(new URL(`../fixtures/${name}`, import.meta.url), 'utf8').trim()

type Handler = (...args: unknown[]) => unknown
const sources: string[] = []
let titleHandler: Handler | undefined
let failHandler: Handler | undefined

function panel(props: Record<string, string> = {}) {
  if (props.src) sources.push(props.src)
  return { SetURL() {}, DeleteAsync() {}, contentwidth: 0, actualuiscale_x: 1 }
}

Object.assign(globalThis, {
  $: {
    Msg() {},
    CreatePanel: (_type: string, _parent: unknown, _id: string, props?: Record<string, string>) => panel(props),
    RegisterEventHandler: (event: string, _panel: unknown, handler: Handler) => {
      if (event === 'HTMLTitle') titleHandler = handler
      if (event === 'ImageFailedLoad') failHandler = handler
    },
    Schedule: () => 0,
    CancelScheduled() {},
  },
})
const { Link } = await import('../src/link.ts')

function hear(frame: string, id: number) {
  titleHandler!(undefined, `modlock:frame:${id}:0:${frame.length}:${id}:${frame}`)
}

test('a command carries the epoch of the newest hello it heard', () => {
  const link = new Link(panel() as never, { protocol: 1, port: 29701, page: 'https://hideouthq.gg/bridge/', token: 't' }, () => {})
  link.send({ k: 'servers' })
  assert.match(sources.at(-1)!, /[?&]e=&/, 'none heard yet, so Hideout refuses it as stale')
  failHandler!()

  const hello = fixture('hello.json')
  hear(hello, 1)
  link.send({ k: 'servers' })
  assert.match(sources.at(-1)!, new RegExp(`[?&]e=${JSON.parse(hello).epoch}&`))
})
