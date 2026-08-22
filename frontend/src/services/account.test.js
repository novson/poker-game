import { describe, expect, it } from 'vitest'
import { clearPokerAccount, readPokerAccount, savePokerAccount } from './account'

function memoryStorage() {
  const values = new Map()
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key)
  }
}

describe('poker account storage', () => {
  it('keeps only the persistent identity fields', () => {
    const storage = memoryStorage()
    savePokerAccount({ accountId: 'account', accountToken: 'secret', loginCode: 'ABCD-EFGH-JKLM',
      profile: { nickname: 'River' }, ignored: true }, storage)

    expect(readPokerAccount(storage)).toEqual({
      accountId: 'account', accountToken: 'secret', loginCode: 'ABCD-EFGH-JKLM', nickname: 'River'
    })
  })

  it('rejects malformed and incomplete data', () => {
    const storage = memoryStorage()
    storage.setItem('poker.account', '{broken')
    expect(readPokerAccount(storage)).toBeNull()
    storage.setItem('poker.account', JSON.stringify({ accountId: 'account' }))
    expect(readPokerAccount(storage)).toBeNull()
  })

  it('can clear the saved identity', () => {
    const storage = memoryStorage()
    savePokerAccount({ accountId: 'a', accountToken: 't', loginCode: 'ABCD-EFGH-JKLM', nickname: 'N' }, storage)
    clearPokerAccount(storage)
    expect(readPokerAccount(storage)).toBeNull()
  })
})
