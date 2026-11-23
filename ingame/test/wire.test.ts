import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import { test } from 'node:test'
import { TitleReader } from '../src/titles.ts'
import { type Command, EMPTY, PROTOCOL, decodeFrame, resultOfWidth, withFrame } from '../src/wire.ts'

const fixtures = new URL('../fixtures/', import.meta.url)
const fixture = (name: string) => readFileSync(new URL(name, fixtures), 'utf8')

test('every frame fixture decodes, and re-encodes to the same JSON', () => {
  const frames = readdirSync(fixtures).filter((name) => !['commands.json', 'commands-invalid.json', 'lines.json', 'results.json'].includes(name))
  assert.ok(frames.length >= 8)
  for (const name of frames) {
    const text = fixture(name).trim()
    const frame = decodeFrame(text)
    assert.ok(frame, `${name} decodes`)
    assert.deepEqual(frame, JSON.parse(text), `${name} keeps every field`)
  }
})

test('the pack speaks the protocol the app says in hello', () => {
  const hello = decodeFrame(fixture('hello.json'))
  assert.equal(hello?.t === 'hello' && hello.protocol, PROTOCOL)
})

test('a frame missing a field, or of an unknown topic, is dropped', () => {
  assert.equal(decodeFrame('{"t":"hello","protocol":1}'), null)
  assert.equal(decodeFrame('{"t":"weather"}'), null)
  assert.equal(decodeFrame('{"t":"game","host":{"mode":"x"},"join":null,"restartAhead":null}'), null)
  assert.equal(decodeFrame('not json'), null)
})

test('a topic replaces the last of its kind and alive changes nothing', () => {
  const idle = decodeFrame(fixture('game-idle.json'))!
  const starting = decodeFrame(fixture('game-starting.json'))!
  const snapshot = withFrame(withFrame(EMPTY, idle), starting)
  assert.equal(snapshot.game, starting)
  assert.equal(withFrame(snapshot, { t: 'alive' }), snapshot)
})

test('every command the UI can build is one the app accepts', () => {
  const accepted = JSON.parse(fixture('commands.json')) as Command[]
  const built: Command[] = [
    { k: 'host', mode: 'bebop-wars' as never, picks: {}, listing: 'invite-only', invite: [], restart: null },
    { k: 'act', action: 'stop' },
    { k: 'confirm', restart: null },
    { k: 'invite', to: ['76561198000000007' as never] },
    { k: 'listing', listing: 'invite-only' },
    { k: 'join', invite: '9f2c41d07e6b4a58b3c1d2e3f4a5b6c7' },
    { k: 'join', server: '3c41a9e07d5b2f18' },
    { k: 'forget', invite: '9f2c41d07e6b4a58b3c1d2e3f4a5b6c7' },
    { k: 'servers' },
    { k: 'hello', self: null },
  ]
  const shape = (c: object) => Object.keys(c).sort().join(',')
  for (const command of built) {
    assert.ok(accepted.some((a) => shape(a) === shape(command) && a.k === command.k), `${command.k} has a shape the app reads`)
  }
})

test('result widths read as the app writes them', () => {
  const results = JSON.parse(fixture('results.json')) as Record<string, number>
  for (const [name, width] of Object.entries(results)) assert.equal(resultOfWidth(width), name)
  assert.equal(resultOfWidth(0), 'unreachable')
  assert.equal(resultOfWidth(40), 'unreachable')
})

test('result widths read the same at every UI scale', () => {
  const results = JSON.parse(fixture('results.json')) as Record<string, number>
  for (const scale of [1440 / 1080, 2160 / 1080, 1200 / 1080]) {
    for (const [name, width] of Object.entries(results)) {
      assert.equal(resultOfWidth(width * scale, scale), name, `${name} at scale ${scale.toFixed(2)}`)
      assert.equal(resultOfWidth(Math.round(width * scale), scale), name, `${name} at scale ${scale.toFixed(2)}, whole pixels`)
    }
  }
})

function titlesOf(line: string, id: number, size: number): string[] {
  const colon = line.indexOf(':')
  const sequence = line.slice(1, colon)
  const packed = line.slice(colon + 1).replace(/\n$/, '')
  const titles = []
  for (let start = 0; start < packed.length; start += size) {
    titles.push(`modlock:frame:${id}:${start}:${packed.length}:${sequence}:${packed.slice(start, start + size)}`)
  }
  return titles
}

test('a packed line read through titles gives back the frame', () => {
  const { frame, line } = JSON.parse(fixture('lines.json')) as { frame: string; line: string }
  for (const size of [1, 3, 1000]) {
    const reader = new TitleReader()
    const reads = titlesOf(line, 4, size).map((t) => reader.read(t))
    assert.ok(reads.every((r) => r.kind === 'part'))
    assert.deepEqual(reads.at(-1), { kind: 'part', line: frame })
  }
})

test('a repeated title is read once, and a part out of order is broken', () => {
  const reader = new TitleReader()
  const { line } = JSON.parse(fixture('lines.json')) as { line: string }
  const [first, second] = titlesOf(line, 9, 4)
  assert.equal(reader.read(first!).kind, 'part')
  assert.equal(reader.read(first!).kind, 'repeat')
  assert.equal(new TitleReader().read(second!).kind, 'broken')
})

test('an empty title is offline and the page title is still waiting', () => {
  const reader = new TitleReader()
  assert.equal(reader.read('hideout:bridge').kind, 'waiting')
  assert.equal(reader.read('').kind, 'offline')
})

test('UTF-8 in a frame survives the packing', () => {
  const bytes = new TextEncoder().encode('{"t":"x","s":"Mugi’s game"}')
  let packed = ''
  for (const b of bytes) packed += b > 0x20 && b < 0x7f && b !== 0x5c ? String.fromCharCode(b) : String.fromCharCode(0x100 + b)
  const read = new TitleReader().read(`modlock:frame:1:0:${packed.length}:1:${packed}`)
  assert.deepEqual(read, { kind: 'part', line: '{"t":"x","s":"Mugi’s game"}' })
})
