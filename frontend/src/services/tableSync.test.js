import { describe, expect, it, vi } from 'vitest'
import { createTableRefresh, shouldApplyTable, tableExitMessage } from './tableSync'

describe('table synchronization', () => {
  it('ignores an older version and a response from a previous room', () => {
    const current = { id: 'a', version: 5 }
    expect(shouldApplyTable(current, { id: 'a', version: 4 })).toBe(false)
    expect(shouldApplyTable(current, { id: 'b', version: 8 })).toBe(false)
    expect(shouldApplyTable(null, { id: 'a', version: 8 })).toBe(false)
    expect(shouldApplyTable(current, { id: 'a', version: 6 })).toBe(true)
  })

  it('keeps transient errors separate from a released seat', () => {
    expect(tableExitMessage({ status: 400, message: '还没轮到你行动' })).toBe('')
    expect(tableExitMessage({ status: 500 })).toBe('')
    expect(tableExitMessage({ code: 'SEAT_LEFT' })).toContain('结算')
    expect(tableExitMessage({ code: 'TABLE_CLOSED' })).toContain('关闭')
  })

  it('coalesces pushes while loading and drops a late result after leaving', async () => {
    const resolvers = []
    const load = vi.fn(() => new Promise(resolve => resolvers.push(resolve)))
    const onTable = vi.fn()
    const refresh = createTableRefresh({ load, onTable, onError: vi.fn() })
    const pending = refresh.request()
    refresh.request()
    refresh.request()
    expect(load).toHaveBeenCalledTimes(1)
    resolvers.shift()({ version: 1 })
    await Promise.resolve()
    expect(load).toHaveBeenCalledTimes(2)
    refresh.stop()
    resolvers.shift()({ version: 2 })
    await pending
    expect(onTable).toHaveBeenCalledTimes(1)
    refresh.request()
    expect(load).toHaveBeenCalledTimes(2)
  })
})
