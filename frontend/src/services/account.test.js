import { describe, expect, it } from 'vitest'
import { clearPokerAccount, normalizeLoginCodeInput, readPokerAccount, savePokerAccount } from './account'

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

describe('normalizeLoginCodeInput', () => {
  it('accepts four digit codes', () => {
    expect(normalizeLoginCodeInput('1234')).toEqual({ code: '1234', valid: true })
    expect(normalizeLoginCodeInput(' 0000 ')).toEqual({ code: '0000', valid: true })
  })

  it('still accepts twelve character codes with or without dashes', () => {
    expect(normalizeLoginCodeInput('abcd-efgh-ijkl')).toEqual({ code: 'ABCDEFGHIJKL', valid: true })
    expect(normalizeLoginCodeInput('ABCD1234EFGH')).toEqual({ code: 'ABCD1234EFGH', valid: true })
  })

  it('rejects other lengths and non digit short codes', () => {
    expect(normalizeLoginCodeInput('123').valid).toBe(false)
    expect(normalizeLoginCodeInput('12345').valid).toBe(false)
    expect(normalizeLoginCodeInput('12a4').valid).toBe(false)
    expect(normalizeLoginCodeInput('abcd-efgh-ijk!').valid).toBe(false)
  })

  it('treats empty input as blank rather than invalid', () => {
    expect(normalizeLoginCodeInput('')).toEqual({ code: '', valid: false })
    expect(normalizeLoginCodeInput(null)).toEqual({ code: '', valid: false })
  })
})
