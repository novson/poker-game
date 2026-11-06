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

/**
 * 「一键补到带入上限」要补多少筹码。
 *
 * 目标是补满到 maxBuyIn，但备用筹码不够时只能补到备用筹码那么多。
 * 补完还达不到最低带入（minBuyIn）的话返回 0 —— 那种情况补了也会被服务端拒绝
 * （「补码后至少需要达到最低带入」），按钮应该直接禁用。
 * 已经在上限时也返回 0。
 */
export function topUpToMax(table, player) {
  const bounds = topUpBounds(table, player)
  if (bounds.maximum <= 0 || bounds.maximum < bounds.minimum) return 0
  return bounds.maximum
}
