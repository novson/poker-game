import { remainingUntil } from './clock'

export function callAmount(table, player) {
  return Math.max(0, table.currentBet - (player?.streetBet || 0))
}

export function canStart(table) {
  if (squidPenaltyPending(table)) return false
  return ['WAITING', 'SHOWDOWN'].includes(table.phase) && table.players.filter(player =>
    !player.leaving && ((player.chips || 0) > 0 || (player.ai
      && (player.reserveChips || 0) >= (table.minBuyIn || 1)))).length >= 2
}

/**
 * 鱿鱼赔付还没结清时不允许开始下一局，必须先让鱿鱼补码并赔付。
 */
export function squidPenaltyPending(table) {
  return !!table?.squid?.penaltyPending
}

/** 鱿鱼赔付提示文案；没有待结清赔付时返回 null。 */
export function squidPenaltyPrompt(table) {
  const squid = table?.squid
  if (!squid?.penaltyPending) return null
  return squid.penaltyPrompt || '鱿鱼赔付尚未结清，必须先补码并完成赔付才能开始下一局'
}

/** 当前玩家是不是待赔付的鱿鱼。 */
export function isSquidPayer(table, player) {
  return !!player && !!table?.squid?.penaltyPending && player.squidDebt > 0
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
 * 当前玩家最近一轮被罚了多少罚金（筹码）。
 *
 * 罚金会直接把桌上筹码打下去，可能低于 minBuyIn。但这是规则内应缴的罚款，
 * 不是「输光了没钱继续」，所以不该因此弹出补码菜单 —— 否则玩家刚被罚完
 * 就被要求补码，既突兀又会打断「看赔付结算」的结果画面。
 *
 * 数据来自服务端下发的 squid.players[].penaltyPaid：赔付明细会在下一局开始时
 * 清空，但「刚被罚过」这个事实要跨轮次留着，否则下一局一开又会被弹补码菜单。
 */
export function squidPenaltyPaidBy(table, player) {
  const squidId = player?.id
  if (!squidId) return 0
  const entry = (table?.squid?.players || []).find(item => item?.id === squidId)
  if (entry) return Number(entry.penaltyPaid) || 0
  // 兼容旧结构：没有 penaltyPaid 时退回赔付明细里算
  return (table?.squid?.settlements || [])
    .filter(item => item.squidId === squidId)
    .reduce((sum, item) => sum + (Number(item.amount) || 0), 0)
}

/**
 * 桌上筹码低于最低带入时，是否应该自动弹出补码菜单。
 *
 * 规则：
 * - 筹码不低于 minBuyIn → 不弹（筹码还够）
 * - 筹码为 0（真的输光了）→ 要弹，不补码就上不了下一局
 * - 罚金还挂着欠款（squidDebt > 0）→ 要弹：得先补码才赔得掉那一笔
 * - 筹码低是因为刚被罚了罚金 → 不弹：那是规则内应缴的罚款，不是「没钱继续」，
 *   弹出来既突兀又会盖住刚结算完的赔付结果画面
 * - 其它情况（打输了、筹码不够下一局）→ 弹
 */
export function shouldAutoOpenTopUp(table, player) {
  if (!player) return false
  const chips = Number(player.chips) || 0
  const minimum = Number(table?.minBuyIn) || 0
  if (chips >= minimum) return false
  // 真的输光了：不补码就上不了下一局
  if (chips <= 0) return true
  // 罚金还欠着：不弹不行，得先补码才赔得掉
  if (Number(player.squidDebt) > 0) return true
  // 筹码低是被罚金扣的：那不是缺钱
  if (squidPenaltyPaidBy(table, player) > 0) return false
  return true
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

/**
 * 结果画面的保留剩余毫秒数。每手打完服务端都会留 5 秒（showdownHoldUntil），
 * 这段时间内既不能自动开下一局，也不显示「开始下一局」按钮。
 *
 * deadline 是服务端下发的绝对时间戳，now 是本机时刻（内部会换算成服务端时间），
 * 所以本机时钟不准时也不会把整个保留窗口算错。
 */
export function showdownHoldRemaining(table, now = Date.now()) {
  const deadline = Number(table?.showdownHoldUntil) || 0
  if (deadline <= 0) return 0
  return remainingUntil(deadline, now)
}

export function canAutoStartNextHand(table, player, now = Date.now()) {
  if (!table?.privateTable || table.phase !== 'SHOWDOWN' || !player || player.leaving || player.timedOut) return false
  if (showdownHoldRemaining(table, now) > 0) return false
  if ((player.chips || 0) < (table.minBuyIn || 0)) return false
  return canStart(table)
}

