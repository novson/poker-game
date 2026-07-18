export function topUpBounds(table, player) {
  const chips = Number(player?.chips) || 0
  const reserve = Number(player?.reserveChips) || 0
  const minimumStack = Number(table?.minBuyIn) || 0
  const maximumStack = Number(table?.maxBuyIn) || 0
  return {
    minimum: chips < minimumStack ? minimumStack - chips : 1,
    maximum: Math.max(0, Math.min(reserve, maximumStack - chips))
  }
}

export function canTopUpAmount(table, player, amount) {
  const value = Number(amount) || 0
  const bounds = topUpBounds(table, player)
  return value >= bounds.minimum && value <= bounds.maximum
}

export function suggestedTopUp(table, player) {
  const bounds = topUpBounds(table, player)
  if (bounds.maximum < bounds.minimum) return 0
  const chips = Number(player?.chips) || 0
  const target = Math.max(Number(table?.minBuyIn) || 0,
    Math.min(Number(table?.defaultBuyIn) || 0, Number(table?.maxBuyIn) || 0))
  const desired = Math.max(bounds.minimum, target - chips,
    (Number(table?.bigBlind) || 1) * 10)
  return Math.min(bounds.maximum, desired)
}
