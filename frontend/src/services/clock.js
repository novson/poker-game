/**
 * 服务端时钟同步。
 *
 * 服务端在每个 TableView 里下发 `serverTime`（服务端当前 epoch ms）。
 * 客户端收到后把它当成时间锚点，之后用本机经过的时间往后推，算出「服务端现在几点」。
 *
 * 为什么需要：行动时限、结果保留窗口这些时长都是服务端用绝对时间戳算的。
 * 如果客户端本机时钟不准（比如快了 25 秒），直接拿 Date.now() 去减服务端时间戳就会算错，
 * 表现是「一局结束立刻开下一局」——5 秒结果保留窗口被整个跳过去了。
 *
 * 注意：这里用 Date.now() 的**差值**来推进时间，而不是直接拿它当当前时刻。
 * 差值对「本机时钟稳定偏快」是免疫的；万一本机时钟被校准跳变，
 * 下一次同步就会重新锚定，自动纠正。
 */

// 时间锚点：{ serverTime: 服务端时刻, localTime: 本机当时的时刻 }
let anchor = null

/** 收到服务端时间戳后重新锚定。serverTime 为空或非法时忽略。 */
export function syncServerClock(serverTime, localTime = Date.now()) {
  const server = Number(serverTime)
  if (!Number.isFinite(server) || server <= 0) return anchor
  const local = Number(localTime)
  // 并发请求可能乱序返回，旧的快照不能把锚点拉回过去，
  // 否则倒计时会凭空多出一段时间。只接受比当前锚点更新的快照。
  if (anchor && server <= anchor.serverTime) return anchor
  anchor = { serverTime: server, localTime: local }
  return anchor
}

/**
 * 当前的服务端时刻（epoch ms）。
 * 从没同步过时退回本机时钟（此时偏移量为 0，行为和以前一致）。
 */
export function serverNow(localTime = Date.now()) {
  if (!anchor) return Number(localTime)
  const elapsed = Math.max(0, Number(localTime) - anchor.localTime)
  return anchor.serverTime + elapsed
}

/** 当前的服务端时钟偏移量（毫秒）：正值表示本机时钟慢于服务端。 */
export function serverClockOffset(localTime = Date.now()) {
  if (!anchor) return 0
  return anchor.serverTime - Number(localTime)
}

/**
 * 距离某个服务端时间戳还剩多少毫秒。
 *
 * deadline 是服务端下发的绝对时间戳；now 是**本机**时刻（默认 Date.now()），
 * 内部会换算到服务端时间空间再相减。
 */
export function remainingUntil(deadline, now = Date.now()) {
  const target = Number(deadline)
  if (!Number.isFinite(target) || target <= 0) return 0
  return Math.max(0, target - serverNow(now))
}

/** 测试用：清掉锚点，回到「信任本机时钟」的状态。 */
export function resetServerClock() {
  anchor = null
}
