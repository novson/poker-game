import { describe, expect, it } from 'vitest'
import { callAmount, canAllIn, canAutoStartNextHand, canStart, minimumRaiseTo,
  playersNeedingTopUp, quickRaiseTo, topUpWaitPrompt, validRaise } from './rules'

describe('poker action rules', () => {
  const table = { phase: 'PRE_FLOP', currentBet: 40, minRaise: 20, players: [{ chips: 1000 }, { chips: 1000 }] }
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
    expect(canStart({ ...table, phase: 'SHOWDOWN', players: [{ chips: 100 }, { chips: 0 }] })).toBe(false)
    expect(canStart({ ...table, phase: 'SHOWDOWN', minBuyIn: 1000,
      players: [{ chips: 100 }, { chips: 0, ai: true, reserveChips: 900 }] })).toBe(false)
    expect(canStart({ ...table, phase: 'SHOWDOWN', minBuyIn: 1000,
      players: [{ chips: 100 }, { chips: 0, ai: true, reserveChips: 1000 }] })).toBe(true)
  })

  it('spots human players who are out of table chips but can still top up', () => {
    const base = { phase: 'SHOWDOWN', minBuyIn: 1000, players: [
      { id: 'me', nickname: 'Alice', chips: 2000, reserveChips: 0 },
      { id: 'bob', nickname: 'Bob', chips: 1500, reserveChips: 0 }
    ] }
    expect(playersNeedingTopUp(base)).toEqual([])
    expect(topUpWaitPrompt(base)).toBeNull()

    expect(playersNeedingTopUp({ ...base, players: [...base.players,
      { id: 'carl', nickname: 'Carl', chips: 0, reserveChips: 5000 }] })
      .map(player => player.id)).toEqual(['carl'])

    // 备用筹码不够最低买入 → 等也补不进来，不提示
    expect(playersNeedingTopUp({ ...base, players: [...base.players,
      { id: 'carl', nickname: 'Carl', chips: 0, reserveChips: 400 }] })).toEqual([])

    // AI 自己会补码；已预约离桌的人不再等
    expect(playersNeedingTopUp({ ...base, players: [...base.players,
      { id: 'ai', nickname: '机器人', chips: 0, reserveChips: 5000, ai: true }] })).toEqual([])
    expect(playersNeedingTopUp({ ...base, players: [...base.players,
      { id: 'carl', nickname: 'Carl', chips: 0, reserveChips: 5000, leaving: true }] })).toEqual([])

    expect(playersNeedingTopUp(null)).toEqual([])
    expect(playersNeedingTopUp({})).toEqual([])
  })

  it('builds the confirm copy for players who still need to top up', () => {
    const table = { phase: 'SHOWDOWN', minBuyIn: 1000, players: [
      { id: 'me', nickname: 'Alice', chips: 2000 },
      { id: 'carl', nickname: 'Carl', chips: 0, reserveChips: 5000 },
      { id: 'dora', nickname: 'Dora', chips: 0, reserveChips: 3000 }
    ] }
    const prompt = topUpWaitPrompt(table)
    expect(prompt).toContain('Carl、Dora')
    expect(prompt).toContain('确定')
    expect(prompt).toContain('取消')
    expect(topUpWaitPrompt({ ...table, players: table.players.slice(0, 1) })).toBeNull()
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
    expect(canAutoStartNextHand(privateTable, { ...privateTable.players[0], leaving: true })).toBe(false)
    expect(canAutoStartNextHand(privateTable, { ...privateTable.players[0], timedOut: true })).toBe(false)
  })
})

