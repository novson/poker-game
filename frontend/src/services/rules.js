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
    && raiseTo >= minimumRaiseTo(table)
    && raiseTo - player.streetBet < player.chips
}

