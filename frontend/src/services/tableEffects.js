export const URGENT_SECONDS = 5

export function boardMotion(index) {
  if (index < 3) return { name: 'flop', delay: index * 110 }
  if (index === 3) return { name: 'turn', delay: 80 }
  return { name: 'river', delay: 100 }
}

export function turnClock(deadline, durationSeconds, now = Date.now()) {
  const duration = Math.max(1, Number(durationSeconds) || 1) * 1000
  const remainingMs = Math.max(0, Number(deadline) - Number(now))
  const seconds = Math.min(Math.ceil(duration / 1000), Math.ceil(remainingMs / 1000))
  return {
    remainingMs,
    seconds,
    progress: Math.max(0, Math.min(1, remainingMs / duration)),
    urgent: seconds > 0 && seconds <= URGENT_SECONDS
  }
}

export function collectBetFlights(previousBets, players, reset = false) {
  const baseline = reset ? new Map() : previousBets
  const next = new Map()
  const flights = []
  for (const player of players || []) {
    const current = Number(player.handBet) || 0
    const previous = Number(baseline.get(player.id)) || 0
    if (current > previous) flights.push({ playerId: player.id, amount: current - previous })
    next.set(player.id, current)
  }
  return { flights, next }
}

export function winningCardState(table) {
  const bestByPlayer = new Map()
  const community = new Set()
  for (const player of table?.players || []) {
    if (!player.winner || !player.bestCards?.length) continue
    const best = new Set(player.bestCards)
    bestByPlayer.set(player.id, best)
    for (const card of table.communityCards || []) {
      if (best.has(card)) community.add(card)
    }
  }
  return { bestByPlayer, community, active: bestByPlayer.size > 0 }
}
