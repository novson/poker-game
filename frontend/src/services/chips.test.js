import { describe, expect, it } from 'vitest'
import { canTopUpAmount, suggestedTopUp, topUpBounds, topUpToMax } from './chips'

const table = { minBuyIn: 1000, defaultBuyIn: 2000, maxBuyIn: 4000, bigBlind: 20 }

describe('chip top-up suggestions', () => {
  it('suggests returning a busted player to the default buy-in', () => {
    const player = { chips: 0, reserveChips: 198000 }

    expect(topUpBounds(table, player)).toEqual({ minimum: 1000, maximum: 4000 })
    expect(suggestedTopUp(table, player)).toBe(2000)
    expect(canTopUpAmount(table, player, 200)).toBe(false)
    expect(canTopUpAmount(table, player, 2000)).toBe(true)
  })

  it('suggests enough chips to reach the default after a short stack loss', () => {
    expect(suggestedTopUp(table, { chips: 300, reserveChips: 5000 })).toBe(1700)
  })

  it('returns no suggestion when the reserve cannot reach the minimum buy-in', () => {
    expect(suggestedTopUp(table, { chips: 0, reserveChips: 500 })).toBe(0)
  })
})

describe('一键补到带入上限', () => {
  it('tops the stack up to the maximum buy-in', () => {
    // 桌上 2000、上限 4000 → 补 2000
    expect(topUpToMax(table, { chips: 2000, reserveChips: 8000 })).toBe(2000)
    // 桌上 0、上限 4000 → 补 4000
    expect(topUpToMax(table, { chips: 0, reserveChips: 8000 })).toBe(4000)
  })

  it('stops at the reserve when the reserve cannot reach the maximum', () => {
    // 备用只剩 1500 → 最多补 1500（补到 2500）
    expect(topUpToMax(table, { chips: 1000, reserveChips: 1500 })).toBe(1500)
  })

  it('does nothing when the stack is already at the maximum', () => {
    expect(topUpToMax(table, { chips: 4000, reserveChips: 6000 })).toBe(0)
  })

  it('does nothing when the reserve cannot even reach the minimum buy-in', () => {
    // 备用 500 < 最低带入 1000 → 补了也会被服务端拒绝，按钮该禁用
    expect(topUpToMax(table, { chips: 0, reserveChips: 500 })).toBe(0)
  })

  it('returns zero for a missing player', () => {
    expect(topUpToMax(table, null)).toBe(0)
  })
})
