// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h } from 'vue'
import PokerRoom from './PokerRoom.vue'

function seat(id, nickname, chips, extra = {}) {
  return { id, nickname, chips, reserveChips: 0, streetBet: 0, handBet: 0, cards: [],
    status: 'ACTIVE', seat: 0, leaving: false, ai: false, ...extra }
}

function tableWith(extra = {}) {
  return { id: 't1', version: 4, name: '测试牌桌', phase: 'SHOWDOWN', handNumber: 3,
    pot: 0, currentBet: 0, minRaise: 20, communityCards: [], message: 'Alice 赢得 120（其他玩家弃牌）',
    minBuyIn: 1000, maxBuyIn: 4000, defaultBuyIn: 2000, smallBlind: 10, bigBlind: 20,
    totalChips: 10000, maxPlayers: 6, privateTable: true, actionTimeSeconds: 25,
    actionDeadline: 0, players: [seat('me', 'Alice', 2600), seat('bob', 'Bob', 1400, { status: 'FOLDED' })],
    showdownHoldUntil: 0, canShowCards: false, canSettle: false, settlementOpen: false, settlement: null,
    ...extra }
}

let app, root, revealCalls

function mountRoom(table, playerId = 'me') {
  root = document.createElement('div')
  document.body.append(root)
  revealCalls = []
  app = createApp({
    render: () => h(PokerRoom, {
      table, playerId, busy: false, connected: true, refreshing: false,
      onShowCards: () => revealCalls.push(true)
    })
  })
  app.mount(root)
}

const nextTick = () => new Promise(resolve => setTimeout(resolve, 0))

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
})

afterEach(() => {
  app?.unmount()
  root?.remove()
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('弃牌获胜后的亮牌', () => {
  it('offers the reveal button only to the uncontested winner', async () => {
    mountRoom(tableWith({ canShowCards: false }))
    await nextTick()
    expect(root.querySelector('.reveal-button')).toBeNull()

    app.unmount(); root.remove()
    mountRoom(tableWith({ canShowCards: true }))
    await nextTick()
    const button = root.querySelector('.reveal-button')
    expect(button).not.toBeNull()
    expect(button.textContent).toContain('亮出我的底牌')
    expect(root.querySelector('.reveal-controls').textContent).toContain('只有你点头')
  })

  it('asks the server to reveal the hole cards', async () => {
    mountRoom(tableWith({ canShowCards: true }))
    await nextTick()
    root.querySelector('.reveal-button').click()
    await nextTick()
    expect(revealCalls).toEqual([true])
  })

  it('hides the button once the cards are public', async () => {
    // 服务端亮牌后会重新下发 canShowCards=false，按钮自然消失
    mountRoom(tableWith({ canShowCards: false, players: [
      seat('me', 'Alice', 2600, { cards: ['As', 'Kd'] }),
      seat('bob', 'Bob', 1400, { status: 'FOLDED' })
    ] }))
    await nextTick()
    expect(root.querySelector('.reveal-button')).toBeNull()
    // 亮牌后自己的牌当然还是看得见的
    expect(root.querySelectorAll('.hole-cards').length).toBeGreaterThan(0)
  })

  it('does not auto-start the next hand while the hold window is open', async () => {
    const held = tableWith({
      canShowCards: true,
      showdownHoldUntil: Date.now() + 4_000,
      players: [seat('me', 'Alice', 2600), seat('bob', 'Bob', 1400, { status: 'FOLDED' })]
    })
    mountRoom(held)
    await nextTick()
    // 5 秒保留窗口内不该冒出自动下一局的倒计时
    expect(root.querySelector('.cancel-countdown')).toBeNull()
    expect(root.querySelector('.start-button').textContent).toBe('开始下一局')
  })
})
