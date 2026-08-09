import { describe, expect, it } from 'vitest'
import { boardMotion, collectBetFlights, turnClock, winningCardState } from './tableEffects'

describe('table effects', () => {
  it('assigns staged motions to flop, turn, and river cards', () => {
    expect([0, 1, 2].map(index => boardMotion(index).name)).toEqual(['flop', 'flop', 'flop'])
    expect(boardMotion(3).name).toBe('turn')
    expect(boardMotion(4).name).toBe('river')
    expect(boardMotion(2).delay).toBeGreaterThan(boardMotion(1).delay)
  })

  it('reports only new chips as flights', () => {
    const previous = new Map([['a', 20], ['b', 10]])
    const result = collectBetFlights(previous, [
      { id: 'a', handBet: 60 },
      { id: 'b', handBet: 10 }
    ])
    expect(result.flights).toEqual([{ playerId: 'a', amount: 40 }])
    expect(result.next.get('a')).toBe(60)
  })

  it('calculates an urgent server-backed action clock', () => {
    expect(turnClock(25_000, 25, 0)).toMatchObject({ seconds: 25, progress: 1, urgent: false })
    expect(turnClock(25_800, 25, 0).seconds).toBe(25)
    expect(turnClock(4_200, 25, 0)).toMatchObject({ seconds: 5, urgent: true })
    expect(turnClock(1_000, 25, 2_000)).toMatchObject({ seconds: 0, progress: 0, urgent: false })
  })

  it('collects the shared cards used by winning hands', () => {
    const state = winningCardState({
      communityCards: ['A♠', 'K♠', 'Q♠', '2♦', '3♣'],
      players: [
        { id: 'winner', winner: true, bestCards: ['A♠', 'K♠', 'Q♠', 'J♠', '10♠'] },
        { id: 'other', winner: false, bestCards: [] }
      ]
    })
    expect([...state.community]).toEqual(['A♠', 'K♠', 'Q♠'])
    expect(state.bestByPlayer.get('winner').has('10♠')).toBe(true)
  })
})
