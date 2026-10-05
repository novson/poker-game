import { beforeEach, describe, expect, it, vi } from 'vitest'
import { watchTable } from './socket'

const transport = vi.hoisted(() => ({ clients: [] }))
vi.mock('@stomp/stompjs', () => ({ Client: class {
  constructor(options) { this.options = options; transport.clients.push(this) }
  activate() {}
  deactivate = vi.fn()
  subscribe(topic, callback) { this.receive = body => callback({ body: JSON.stringify(body) }) }
} }))

beforeEach(() => {
  transport.clients.length = 0
  vi.stubGlobal('location', { protocol: 'http:', host: 'localhost:5173' })
})

describe('table events', () => {
  it('closes a deleted room without another REST refresh or reconnect', () => {
    const change = vi.fn(), status = vi.fn(), event = vi.fn(), closed = vi.fn()
    watchTable('a', change, status, event, closed)
    const client = transport.clients[0]
    client.options.onConnect()
    change.mockClear()
    client.receive({ tableId: 'a', version: -1 })
    expect(closed).toHaveBeenCalledTimes(1)
    expect(change).not.toHaveBeenCalled()
    expect(client.deactivate).toHaveBeenCalledTimes(1)
    client.options.onConnect()
    expect(change).not.toHaveBeenCalled()
  })

  it('keeps emotes separate and ignores callbacks after unsubscribe', () => {
    const change = vi.fn(), event = vi.fn(), status = vi.fn()
    const stop = watchTable('a', change, status, event)
    const client = transport.clients[0]
    client.options.onConnect()
    client.receive({ type: 'EMOTE', emoteId: 'nice-hand' })
    expect(event).toHaveBeenCalledTimes(1)
    expect(change).toHaveBeenCalledTimes(1)
    stop()
    client.receive({ version: 2 })
    expect(change).toHaveBeenCalledTimes(1)
  })
})
