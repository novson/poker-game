export function callAmount(table, player) {
  return Math.max(0, table.currentBet - (player?.streetBet || 0))
}

export function canStart(table) {
  return ['WAITING', 'SHOWDOWN'].includes(table.phase) && table.players.filter(player =>
    !player.leaving && ((player.chips || 0) > 0 || (player.ai
      && (player.reserveChips || 0) >= (table.minBuyIn || 1)))).length >= 2
}

export function minimumRaiseTo(table) {
  return table.currentBet + table.minRaise
}

export function validRaise(table, player, raiseTo) {
  return Number.isFinite(raiseTo)
    && player?.canRaise !== false
    && raiseTo >= minimumRaiseTo(table)
    && raiseTo - player.streetBet < player.chips
}

export function canAllIn(table, player) {
  const allInTo = (player?.streetBet || 0) + (player?.chips || 0)
  return (player?.chips || 0) > 0 && (allInTo <= table.currentBet || player?.canRaise !== false)
}

export function quickRaiseTo(table, player, fraction) {
  const minimum = minimumRaiseTo(table)
  const maximum = (player?.streetBet || 0) + (player?.chips || 0) - 1
  if (player?.canRaise === false || maximum < minimum) return null
  const outstanding = callAmount(table, player)
  const potAfterCall = (table.pot || 0) + outstanding
  const step = Math.max(1, table.bigBlind || 1)
  const extra = Math.ceil((potAfterCall * fraction) / step) * step
  const target = (player?.streetBet || 0) + outstanding + Math.max(step, extra)
  return Math.min(maximum, Math.max(minimum, target))
}

/**
 * 桌上筹码为 0、且手里还有备用筹码可以补码的真人玩家。
 * AI 会自己补码、已预约离桌的人不算，备用筹码不足最低买入的等了也补不进来。
 */
export function playersNeedingTopUp(table) {
  if (!table?.players) return []
  const minimumStack = Number(table.minBuyIn) || 0
  return table.players.filter(player => !player.ai && !player.leaving
    && (player.chips || 0) <= 0 && (player.reserveChips || 0) >= minimumStack)
}

/**
 * 开始下一局前的确认文案：有人还没补码时返回提示，否则返回 null（直接开始）。
 * 确定 = 立即开始；取消 = 留时间给 TA 补码。
 */
export function topUpWaitPrompt(table) {
  const waiting = playersNeedingTopUp(table)
  if (!waiting.length) return null
  const names = waiting.map(player => player.nickname || '未知玩家').join('、')
  return `${names} 桌上已经没有筹码，是否仍要开始下一局？\n\n` +
    '点“确定”立即开始（TA 补码后才能参与下一局），点“取消”等 TA 补码。'
}

export function canAutoStartNextHand(table, player) {
  if (!table?.privateTable || table.phase !== 'SHOWDOWN' || !player || player.leaving || player.timedOut) return false
  if ((player.chips || 0) < (table.minBuyIn || 0)) return false
  return canStart(table)
}

