// Drives the bridge page in a real browser the way the game's renderer does: it
// reads the page's title, joins the parts, and acknowledges each by navigating to
// the URL plus a flipped fragment. It prints each screen as one JSON line.
const [browserSocket, pageUrl] = process.argv.slice(2)
const prefix = 'modlock:frame:'
const socket = new WebSocket(browserSocket)
await new Promise((resolve, reject) => {
  socket.onopen = resolve
  socket.onerror = reject
})

let nextId = 1
const waiting = new Map()
socket.onmessage = ({ data }) => {
  const message = JSON.parse(data)
  const waiter = waiting.get(message.id)
  if (waiter) {
    waiting.delete(message.id)
    message.error ? waiter.reject(new Error(message.error.message)) : waiter.resolve(message.result)
  }
}
function send(method, params = {}, sessionId) {
  const id = nextId++
  socket.send(JSON.stringify({ id, method, params, sessionId }))
  return new Promise((resolve, reject) => waiting.set(id, { resolve, reject }))
}

const { targetId } = await send('Target.createTarget', { url: 'about:blank' })
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true })
await send('Page.enable', {}, sessionId)
await send('Page.navigate', { url: pageUrl }, sessionId)

let lastTitle = null
let parts = null
let flipped = false
for (;;) {
  const { result } = await send('Runtime.evaluate', { expression: 'document.title' }, sessionId)
  const title = result.value
  if (title !== lastTitle) {
    lastTitle = title
    if (!title.startsWith(prefix)) {
      parts = null
      if (title === '') console.log(JSON.stringify({ screen: null }))
    } else {
      const fields = title.slice(prefix.length).split(':', 4)
      const [id, offset, total] = fields.map(Number)
      const chunk = title.slice(prefix.length + fields.join(':').length + 1)
      if (offset === 0) parts = { id, total, text: '' }
      if (!parts || parts.id !== id || parts.text.length !== offset) throw new Error('a part of the screen is missing')
      parts.text += chunk
      flipped = !flipped
      await send('Page.navigate', { url: `${pageUrl}#${flipped ? 1 : 0}` }, sessionId)
      if (parts.text.length === parts.total) {
        console.log(JSON.stringify({ screen: parts.text, titleBytes: Buffer.byteLength(title) }))
        parts = null
      }
    }
  }
  await new Promise((resolve) => setTimeout(resolve, 5))
}
