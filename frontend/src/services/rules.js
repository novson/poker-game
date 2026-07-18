export function callAmount(table, player) {
  return Math.max(0, table.currentBet - (player?.streetBet || 0))
}

export function canStart(table) {
  return ['WAITING', 'SHOWDOWN'].includes(table.phase) && table.players.length >= 2
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

export function canAutoStartNextHand(table, player) {
  if (!table?.privateTable || table.phase !== 'SHOWDOWN' || !player) return false
  if ((player.chips || 0) < (table.minBuyIn || 0)) return false
  return table.players.some(other => other.id !== player.id
    && ((other.chips || 0) > 0 || (other.ai && (other.reserveChips || 0) > 0)))
}

