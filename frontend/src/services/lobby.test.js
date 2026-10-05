import { expect, it } from 'vitest'
import { buyInState, tableAvailability } from './lobby'

const rules = { minBuyIn: 1000, maxBuyIn: 4000, totalChips: 10000 }

it('limits buy-in to the actual account balance', () => {
  expect(buyInState(rules, 2000, 1500)).toMatchObject({ maximum: 1500, valid: false })
  expect(buyInState(rules, 1500, 1500).valid).toBe(true)
  expect(buyInState(rules, 1000, 900).message).toContain('余额不足')
})

it('accepts integer chips including amounts not divisible by the blind', () => {
  expect(buyInState(rules, 1001).valid).toBe(true)
  for (const amount of ['', NaN, Infinity, 1000.5, 999, 4001]) {
    expect(buyInState(rules, amount).valid).toBe(false)
  }
})

it('explains table availability without hiding occupied tables', () => {
  expect(tableAvailability({ phase: 'WAITING', playerCount: 6, maxPlayers: 6 }).label).toBe('已满座')
  expect(tableAvailability({ phase: 'FLOP', playerCount: 2, maxPlayers: 6 }).label).toBe('牌局进行中')
  expect(tableAvailability({ phase: 'SHOWDOWN', playerCount: 2, maxPlayers: 6 }).available).toBe(true)
})
