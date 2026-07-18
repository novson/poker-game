const positionSlots = {
  2: [0, 3],
  3: [0, 2, 4],
  4: [0, 1, 3, 5],
  5: [0, 1, 2, 4, 5],
  6: [0, 1, 2, 3, 4, 5]
}

export function seatsFromViewer(players, maxPlayers, viewerId) {
  const seatCount = Number(maxPlayers)
  if (!Number.isInteger(seatCount) || seatCount < 1) return []

  const viewer = players.find(player => player.id === viewerId)
  const viewerSeat = Number.isInteger(viewer?.seat) ? viewer.seat : 0
  const positions = positionSlots[seatCount] || Array.from({ length: seatCount }, (_, index) => index)

  return Array.from({ length: seatCount }, (_, visualSeat) => {
    const actualSeat = (viewerSeat + visualSeat) % seatCount
    return {
      actualSeat,
      position: positions[visualSeat],
      player: players.find(value => value.seat === actualSeat) || null
    }
  })
}
