import { describe, expect, it } from 'vitest'
import { callAmount, canStart, minimumRaiseTo, validRaise } from './rules'

describe('poker action rules', () => {
  const table = { phase: 'PRE_FLOP', currentBet: 40, minRaise: 20, players: [{}, {}] }
  const player = { streetBet: 20, chips: 500 }

  it('calculates the outstanding call', () => {
    expect(callAmount(table, player)).toBe(20)
  })

  it('requires a full minimum raise and keeps one chip behind', () => {
    expect(minimumRaiseTo(table)).toBe(60)
    expect(validRaise(table, player, 59)).toBe(false)
    expect(validRaise(table, player, 60)).toBe(true)
    expect(validRaise(table, player, 520)).toBe(false)
  })

  it('starts only from a completed phase with two players', () => {
    expect(canStart(table)).toBe(false)
    expect(canStart({ ...table, phase: 'WAITING' })).toBe(true)
  })
})

