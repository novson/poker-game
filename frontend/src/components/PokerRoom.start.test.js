// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick as vueTick } from 'vue'
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

function mountRoom(table, playerId = 'me') {
  root = document.createElement('div')
  document.body.append(root)
  startCalls = 0
  app = createApp({
    render: () => h(PokerRoom, {
      table, playerId, busy: false, connected: true, refreshing: false,
      onStart: () => { startCalls++ }
    })
  })
  app.mount(root)
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

  it('still hides the button during the result hold window', async () => {
    mountRoom(tableWith({ isOwner: true, showdownHoldUntil: Date.now() + 4_000 }))
    await nextTick()
    expect(root.querySelector('.start-button')).toBeNull()
    expect(root.querySelector('.hand-hold').textContent).toContain('本手结果保留中')
  })

  it('counts 10 seconds down for the creator of a public table', async () => {
    vi.useFakeTimers()
    mountRoom(tableWith({ isOwner: true }))
    await nextTick()
    const button = root.querySelector('.start-button')
    expect(button.textContent).toContain('10 秒后自动开始下一局')
    expect(root.querySelector('.cancel-countdown')).not.toBeNull()

    vi.advanceTimersByTime(9_000)
    await nextTick()
    expect(root.querySelector('.start-button').textContent).toContain('1 秒后')

    vi.advanceTimersByTime(1_000)
    await nextTick()
    expect(startCalls).toBe(1)
    vi.useRealTimers()
  })

  it('lets the creator cancel the countdown', async () => {
    vi.useFakeTimers()
    mountRoom(tableWith({ isOwner: true }))
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
})
