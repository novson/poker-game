import { describe, expect, it } from 'vitest'
import { canTopUpAmount, suggestedTopUp, topUpBounds } from './chips'

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
