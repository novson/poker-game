import { describe, expect, it } from 'vitest'
import { callAmount, canAllIn, canAutoStartNextHand, canStart, minimumRaiseTo, quickRaiseTo, validRaise } from './rules'

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
    expect(validRaise(table, { ...player, canRaise: false }, 60)).toBe(false)
  })

  it('allows a locked player to go all-in only as a call', () => {
    expect(canAllIn(table, { streetBet: 20, chips: 20, canRaise: false })).toBe(true)
    expect(canAllIn(table, { streetBet: 20, chips: 30, canRaise: false })).toBe(false)
  })

  it('starts only from a completed phase with two players', () => {
    expect(canStart(table)).toBe(false)
    expect(canStart({ ...table, phase: 'WAITING' })).toBe(true)
  })

  it('builds legal half-pot and pot-size raises', () => {
    const raiseTable = { ...table, pot: 100, bigBlind: 20 }
    expect(quickRaiseTo(raiseTable, player, 0.5)).toBe(100)
    expect(quickRaiseTo(raiseTable, player, 1)).toBe(160)
    expect(quickRaiseTo(raiseTable, { ...player, chips: 35 }, 1)).toBeNull()
  })

  it('auto-starts only a funded player in a completed private hand', () => {
    const privateTable = { ...table, privateTable: true, phase: 'SHOWDOWN', minBuyIn: 1000,
      players: [{ id: 'me', chips: 1000 }, { id: 'ai', chips: 0, reserveChips: 5000, ai: true }] }
    expect(canAutoStartNextHand(privateTable, privateTable.players[0])).toBe(true)
    expect(canAutoStartNextHand(privateTable, { ...privateTable.players[0], chips: 900 })).toBe(false)
    expect(canAutoStartNextHand({ ...privateTable, privateTable: false }, privateTable.players[0])).toBe(false)
  })
})

