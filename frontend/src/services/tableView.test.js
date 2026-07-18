import { describe, expect, it } from 'vitest'
import { seatsFromViewer } from './tableView'

describe('seatsFromViewer', () => {
  it('places the viewer at the bottom while preserving relative seat order', () => {
    const players = [
      { id: 'left', seat: 1 },
      { id: 'me', seat: 4 },
      { id: 'next', seat: 5 }
    ]

    const seats = seatsFromViewer(players, 6, 'me')

    expect(seats[0]).toMatchObject({ actualSeat: 4, position: 0, player: players[1] })
    expect(seats[1]).toMatchObject({ actualSeat: 5, position: 1, player: players[2] })
    expect(seats[3]).toMatchObject({ actualSeat: 1, position: 3, player: players[0] })
  })

  it('places the opponent opposite the viewer at a heads-up table', () => {
    const players = [{ id: 'other', seat: 0 }, { id: 'me', seat: 1 }]

    const seats = seatsFromViewer(players, 2, 'me')

    expect(seats.map(seat => seat.position)).toEqual([0, 3])
    expect(seats.map(seat => seat.player.id)).toEqual(['me', 'other'])
  })
})
