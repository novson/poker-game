// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick as vueTick, reactive } from 'vue'
import PokerRoom from './PokerRoom.vue'

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

// 不能用 setTimeout(0) 等渲染：配合 vi.useFakeTimers() 时它永远不会 resolve
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
})

describe('公开桌的「开始下一局」只给创建人', () => {
  it('hides the button from everyone but the creator', async () => {
    mountRoom(tableWith({ isOwner: false }))
    await nextTick()
    expect(root.querySelector('.start-button')).toBeNull()
    expect(root.querySelector('.hand-hold').textContent).toContain('等待房主开始下一局')

    app.unmount(); root.remove()
    mountRoom(tableWith({ isOwner: true }))
    await nextTick()
    expect(root.querySelector('.start-button')).not.toBeNull()
    expect(root.querySelector('.hand-hold')).toBeNull()
  })

  it('keeps the button visible to every player on a private table', async () => {
    // 私人桌不动：谁都能开，不看 isOwner
    mountRoom(tableWith({ privateTable: true, isOwner: false }))
    await nextTick()
    expect(root.querySelector('.start-button')).not.toBeNull()
  })

  it('shows the countdown immediately but still hides the button during the result hold window', async () => {
    // 本手已结束 1 秒：剩余总窗口就是 9 秒，证明计时来自服务端时间戳
    mountRoom(tableWith({ isOwner: true, showdownHoldUntil: Date.now() + 4_000 }))
    await nextTick()
    expect(root.querySelector('.start-button')).toBeNull()
    expect(root.querySelector('.hand-hold').textContent).toContain('9 秒后自动开始下一局')
  })

  it.each([
    { scenario: '比牌摊牌、无主动亮牌按钮', canShowCards: false },
    { scenario: '无人争抢、可主动亮牌', canShowCards: true }
  ])('counts 10 seconds from the end of the hand: $scenario', async ({ canShowCards }) => {
    vi.useFakeTimers()
    mountRoom(tableWith({ isOwner: true, canShowCards, showdownHoldUntil: Date.now() + 5_000 }))
    await nextTick()
    expect(root.querySelector('.start-button')).toBeNull()
    expect(root.querySelector('.hand-hold').textContent).toContain('10 秒后自动开始下一局')
    expect(Boolean(root.querySelector('.reveal-button'))).toBe(canShowCards)

    vi.advanceTimersByTime(4_000)
    await nextTick()
    expect(root.querySelector('.start-button')).toBeNull()
    expect(root.querySelector('.hand-hold').textContent).toContain('6 秒后自动开始下一局')

    vi.advanceTimersByTime(1_000)
    await nextTick()
    expect(root.querySelector('.start-button').textContent).toContain('5 秒后自动开始下一局')
    expect(root.querySelector('.cancel-countdown')).not.toBeNull()

    vi.advanceTimersByTime(4_000)
    await nextTick()
    expect(root.querySelector('.start-button').textContent).toContain('1 秒后')

    vi.advanceTimersByTime(1_000)
    await nextTick()
    expect(startCalls).toBe(1)
  })

  it('lets the creator cancel the countdown when the button appears', async () => {
    vi.useFakeTimers()
    mountRoom(tableWith({ isOwner: true, showdownHoldUntil: Date.now() + 5_000 }))
    await nextTick()
    vi.advanceTimersByTime(5_000)
    await nextTick()
    root.querySelector('.cancel-countdown').click()
    await nextTick()
    expect(root.querySelector('.start-button').textContent).toBe('开始下一局')

    vi.advanceTimersByTime(12_000)
    await nextTick()
    expect(startCalls).toBe(0)
    vi.useRealTimers()
  })

  it('does not auto-start a public table for a non-creator', async () => {
    vi.useFakeTimers()
    mountRoom(tableWith({ isOwner: false }))
    await nextTick()
    vi.advanceTimersByTime(15_000)
    await nextTick()
    expect(startCalls).toBe(0)
    vi.useRealTimers()
  })

  it('continues the same countdown when the hold window closes', async () => {
    vi.useFakeTimers()
    mountRoom(tableWith({ isOwner: true, showdownHoldUntil: Date.now() + 5_000 }))
    await nextTick()
    expect(root.querySelector('.start-button')).toBeNull()

    // 保留期只控制按钮可见性，不能把已经走了 5 秒的倒计时重置成 10 秒
    vi.advanceTimersByTime(5_000)
    await nextTick()
    const button = root.querySelector('.start-button')
    expect(button).not.toBeNull()
    expect(button.textContent).toContain('5 秒后自动开始下一局')
  })

  it('keeps the absolute countdown running through a busy reveal request', async () => {
    vi.useFakeTimers()
    const controls = mountRoom(tableWith({
      isOwner: true, canShowCards: true, showdownHoldUntil: Date.now() + 5_000
    }))
    await nextTick()
    vi.advanceTimersByTime(2_000)
    await nextTick()
    expect(root.querySelector('.hand-hold').textContent).toContain('8 秒后自动开始下一局')

    // 亮牌请求期间 busy 再恢复，绝对截止时间不动
    controls.busy = true
    await nextTick()
    vi.advanceTimersByTime(1_000)
    controls.busy = false
    await nextTick()
    expect(root.querySelector('.hand-hold').textContent).toContain('7 秒后自动开始下一局')
  })
})
