// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick as vueTick, reactive } from 'vue'
import PokerRoom from './PokerRoom.vue'
import { resetServerClock, syncServerClock } from '../services/clock'

function seat(id, nickname, chips, extra = {}) {
  return { id, nickname, chips, reserveChips: 0, streetBet: 0, handBet: 0, cards: [],
    status: 'ACTIVE', seat: 0, leaving: false, ai: false, ...extra }
}

function tableWith(extra = {}) {
  return { id: 't1', version: 5, name: '公开桌', phase: 'SHOWDOWN', handNumber: 4,
    pot: 0, currentBet: 0, minRaise: 20, communityCards: [], message: '本手结束',
    minBuyIn: 1000, maxBuyIn: 4000, defaultBuyIn: 2000, smallBlind: 10, bigBlind: 20,
    totalChips: 10000, maxPlayers: 6, privateTable: false, actionTimeSeconds: 25,
    actionDeadline: 0, players: [seat('me', 'Alice', 2600), seat('bob', 'Bob', 1400)],
    showdownHoldUntil: 0, canShowCards: false, isOwner: false,
    canSettle: false, settlementOpen: false, settlement: null, ...extra }
}

let app, root, startCalls

function mountRoom(table, controls = {}) {
  root = document.createElement('div')
  document.body.append(root)
  startCalls = 0
  const state = reactive({ busy: false, connected: true, ...controls })
  app = createApp({
    render: () => h(PokerRoom, {
      table, playerId: 'me', ...state, refreshing: false,
      onStart: () => { startCalls++ }
    })
  })
  app.mount(root)
  return state
}

const nextTick = () => vueTick()

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
})

afterEach(() => {
  app?.unmount()
  root?.remove()
  localStorage.clear()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  resetServerClock()
})

describe('客户端时钟不准时的倒计时', () => {
  it('本机时钟快 25 秒时，结果保留窗口仍然有效，不会立刻自动开局', async () => {
    vi.useFakeTimers()
    // 服务端真实时间：2026-11-06 02:00:00
    const serverTruth = 1793901600000
    // 本机时钟快了 25 秒
    const clientSkew = 25_000
    vi.setSystemTime(serverTruth + clientSkew)

    // 服务端下发：保留窗口到 serverTruth + 5000
    const holdUntil = serverTruth + 5_000
    // 客户端收到这份状态时，本机认为已经过了 25 秒
    syncServerClock(serverTruth, serverTruth + clientSkew)

    mountRoom(tableWith({ isOwner: true, showdownHoldUntil: holdUntil }))
    await nextTick()

    // 保留期没结束：按钮不该出现，倒计时应该是 10 秒（5 秒保留 + 5 秒可开）
    expect(root.querySelector('.start-button')).toBeNull()
    expect(root.querySelector('.hand-hold').textContent).toContain('10 秒后自动开始下一局')

    // 走完 5 秒保留期
    vi.advanceTimersByTime(5_000)
    await nextTick()
    expect(root.querySelector('.start-button')).not.toBeNull()
    expect(root.querySelector('.start-button').textContent).toContain('5 秒后自动开始下一局')

    // 再走 5 秒才应该自动开局
    vi.advanceTimersByTime(5_000)
    await nextTick()
    expect(startCalls).toBe(1)
  })

  it('本机时钟慢 30 秒时，保留窗口同样有效', async () => {
    vi.useFakeTimers()
    const serverTruth = 1793901600000
    const clientSkew = -30_000
    vi.setSystemTime(serverTruth + clientSkew)
    syncServerClock(serverTruth, serverTruth + clientSkew)

    mountRoom(tableWith({ isOwner: true, showdownHoldUntil: serverTruth + 5_000 }))
    await nextTick()
    expect(root.querySelector('.start-button')).toBeNull()
    expect(root.querySelector('.hand-hold').textContent).toContain('10 秒后自动开始下一局')

    vi.advanceTimersByTime(5_000)
    await nextTick()
    expect(root.querySelector('.start-button')).not.toBeNull()
  })

  it('行动倒计时也按服务端时间算', async () => {
    vi.useFakeTimers()
    const serverTruth = 1793901600000
    const clientSkew = 25_000
    vi.setSystemTime(serverTruth + clientSkew)
    syncServerClock(serverTruth, serverTruth + clientSkew)

    // 服务端说行动还剩 12 秒
    const actionDeadline = serverTruth + 12_000
    mountRoom(tableWith({
      phase: 'PRE_FLOP',
      players: [seat('me', 'Alice', 2600, { currentTurn: true }), seat('bob', 'Bob', 1400)],
      actionDeadline
    }))
    await nextTick()
    const clock = root.querySelector('.action-clock')
    expect(clock).not.toBeNull()
    expect(clock.textContent).toContain('12')
  })
})
