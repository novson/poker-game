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

