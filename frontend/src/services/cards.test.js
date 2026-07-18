import { describe, expect, it } from 'vitest'
import { displayRank } from './cards'

describe('card display', () => {
  it('uses the familiar Chinese rank for ten', () => {
    expect(displayRank('T♠')).toBe('10')
    expect(displayRank('A♥')).toBe('A')
  })
})
