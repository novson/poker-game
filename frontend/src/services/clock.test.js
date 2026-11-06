import { afterEach, describe, expect, it } from 'vitest'
import { remainingUntil, resetServerClock, serverClockOffset, serverNow, syncServerClock } from './clock'

afterEach(() => {
  resetServerClock()
})

describe('服务端时钟同步', () => {
  it('没同步过时信任本机时钟', () => {
    expect(serverNow(1_000_000)).toBe(1_000_000)
    expect(serverClockOffset(1_000_000)).toBe(0)
    expect(remainingUntil(5_000_000, 1_000_000)).toBe(4_000_000)
  })

  it('同步后按服务端时间基准推进', () => {
    // 服务端说现在是 2,000,000，本机以为现在是 1,000,000（本机慢 1 秒）
    syncServerClock(2_000_000, 1_000_000)
    expect(serverNow(1_000_000)).toBe(2_000_000)
    expect(serverNow(1_500_000)).toBe(2_500_000)
    expect(serverClockOffset(1_000_000)).toBe(1_000_000)
  })

  it('本机时钟快 25 秒时，服务端时间戳的剩余量算得对', () => {
    // 复现线上问题：本机比服务端快 25 秒
    const clientNow = 1_000_000
    const serverNowAtSync = clientNow - 25_000
    syncServerClock(serverNowAtSync, clientNow)

    // 服务端说保留窗口还有 5 秒（deadline 是服务端时间空间的绝对时间戳）
    const holdUntil = serverNowAtSync + 5_000
    expect(remainingUntil(holdUntil, clientNow)).toBe(5_000)
    // 本机时钟直接减会得到负数（整个窗口被跳过）——这正是线上表现的根因
    expect(holdUntil - clientNow).toBeLessThan(0)
  })

  it('保留窗口已过时返回 0，不返回负数', () => {
    syncServerClock(1_000_000, 1_000_000)
    expect(remainingUntil(500_000, 1_000_000)).toBe(0)
    expect(remainingUntil(0, 1_000_000)).toBe(0)
    expect(remainingUntil(undefined, 1_000_000)).toBe(0)
  })

  it('忽略非法的时间戳，保留上一次锚点', () => {
    syncServerClock(2_000_000, 1_000_000)
    syncServerClock(0, 1_000_000)
    syncServerClock(null, 1_000_000)
    syncServerClock('abc', 1_000_000)
    expect(serverNow(1_000_000)).toBe(2_000_000)
  })

  it('本机时钟被校准跳变后，下一次同步会重新锚定', () => {
    syncServerClock(2_000_000, 1_000_000)
    expect(serverNow(1_100_000)).toBe(2_100_000)
    // 本机时钟突然被校正到 2_000_000（与服务端对齐），再同步一次
    syncServerClock(2_200_000, 2_000_000)
    expect(serverNow(2_000_000)).toBe(2_200_000)
  })

  it('乱序到达的旧快照不会把时钟锚点拉回过去', () => {
    // 先收到较新的快照
    syncServerClock(2_000_000, 1_000_000)
    expect(serverNow(1_000_000)).toBe(2_000_000)

    // 稍后又收到一个更旧的快照（并发请求乱序返回），锚点不能被拉回
    syncServerClock(1_500_000, 1_000_000)
    expect(serverNow(1_000_000)).toBe(2_000_000)

    // 再同步更新的快照，锚点正常前进
    syncServerClock(2_500_000, 1_500_000)
    expect(serverNow(1_500_000)).toBe(2_500_000)
  })
})
