import { describe, expect, it } from 'vitest'
import { clearPokerSession, readPokerSession, savePokerSession } from './session'

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial))
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key)
  }
}

describe('poker session persistence', () => {
  it('round-trips a resumable table identity', () => {
    const storage = memoryStorage()
    savePokerSession({ tableId: 'table-1', playerId: 'player-1', reconnectToken: 'secret-1',
      tableName: '周末牌局', nickname: 'Alice' }, storage)
    expect(readPokerSession(storage)).toEqual({
      tableId: 'table-1', playerId: 'player-1', reconnectToken: 'secret-1',
      tableName: '周末牌局', nickname: 'Alice', autoResume: true
    })
  })

  it('ignores malformed data and clears expired sessions', () => {
    const storage = memoryStorage({ 'poker.session': '{broken' })
    expect(readPokerSession(storage)).toBeNull()
    savePokerSession({ tableId: 'table-1', playerId: 'player-1', reconnectToken: 'secret-1',
      autoResume: false }, storage)
    expect(readPokerSession(storage).autoResume).toBe(false)
    clearPokerSession(storage)
    expect(readPokerSession(storage)).toBeNull()
  })
})
