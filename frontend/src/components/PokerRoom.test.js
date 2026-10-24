// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h } from 'vue'
import PokerRoom from './PokerRoom.vue'

function seat(id, nickname, chips, extra = {}) {
  return { id, nickname, chips, reserveChips: 0, streetBet: 0, handBet: 0, cards: [],
    status: 'ACTIVE', seat: 0, leaving: false, ai: false, ...extra }
}

function tableWith(players, extra = {}) {
  // 默认把自己当创建人：公开桌的「开始下一局」只给创建人显示
  return { id: 't1', version: 3, name: '测试牌桌', phase: 'SHOWDOWN', handNumber: 1,
    pot: 0, currentBet: 0, minRaise: 20, communityCards: [], message: '',
    minBuyIn: 1000, maxBuyIn: 4000, defaultBuyIn: 2000, smallBlind: 10, bigBlind: 20,
    totalChips: 10000, maxPlayers: 6, privateTable: false, actionTimeSeconds: 25,
    actionDeadline: 0, showdownHoldUntil: 0, canShowCards: false, isOwner: true,
    canSettle: false, settlementOpen: false, settlement: null, players, ...extra }
}

let app, root, started

function mountRoom(table, playerId = 'me') {
  root = document.createElement('div')
  document.body.append(root)
  started = []
  app = createApp({
    render: () => h(PokerRoom, {
      table, playerId, busy: false, connected: true, refreshing: false,
      onStart: () => started.push(true)
    })
  })
  app.mount(root)
}

function clickStart() {
  root.querySelector('.start-button').click()
  return new Promise(resolve => setTimeout(resolve, 0))
}

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  window.confirm = vi.fn(() => true)
})

afterEach(() => {
  app?.unmount()
  root?.remove()
  localStorage.clear()
  vi.unstubAllGlobals()
})

describe('starting the next hand', () => {
  it('starts immediately when every funded player still has chips', async () => {
    mountRoom(tableWith([seat('me', 'Alice', 2000), seat('bob', 'Bob', 1500)]))
    await clickStart()
    expect(started).toHaveLength(1)
    expect(window.confirm).not.toHaveBeenCalled()
  })

  it('asks for confirmation when a human player ran out of table chips', async () => {
    mountRoom(tableWith([
      seat('me', 'Alice', 2000),
      seat('bob', 'Bob', 1500),
      seat('carl', 'Carl', 0, { reserveChips: 5000 })
    ]))
    await clickStart()
    expect(window.confirm).toHaveBeenCalledTimes(1)
    expect(window.confirm.mock.calls[0][0]).toContain('Carl')
    expect(started).toHaveLength(1)
  })

  it('does not start when the confirmation is dismissed', async () => {
    window.confirm = vi.fn(() => false)
    mountRoom(tableWith([
      seat('me', 'Alice', 2000),
      seat('bob', 'Bob', 1500),
      seat('carl', 'Carl', 0, { reserveChips: 5000 })
    ]))
    await clickStart()
    expect(window.confirm).toHaveBeenCalledTimes(1)
    expect(started).toHaveLength(0)
  })

  it('accepts round chip amounts instead of blind-stepped ones', async () => {
    // 桌上筹码低于最低带入时筹码面板会自动展开（bigBlind=50 → 旧实现的合法值是 1,51,101…）
    mountRoom(tableWith([
      seat('me', 'Alice', 0, { reserveChips: 50000 }),
      seat('bob', 'Bob', 1500)
    ]))
    root.querySelector('.chip-manager-toggle').click()
    await new Promise(resolve => setTimeout(resolve, 0))
    const field = [...root.querySelectorAll('.bankroll-actions label')]
      .find(item => item.textContent.includes('调整金额'))
    const input = field.querySelector('input')
    expect(input.getAttribute('step')).toBe('1')
    input.value = '5000'
    expect(input.validity.stepMismatch).toBe(false)
    expect(input.checkValidity()).toBe(true)
  })

  it('tells a player who joined mid-hand that they start next hand', async () => {
    mountRoom(tableWith([
      seat('me', 'Alice', 0, { reserveChips: 50000, status: 'SITTING' }),
      seat('bob', 'Bob', 1500, { status: 'ACTIVE' }),
      seat('carl', 'Carl', 1500, { status: 'ACTIVE' })
    ], { phase: 'FLOP', currentBet: 50 }))
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(root.querySelector('.status-copy').textContent).toContain('下一局开始参与')
  })

  it('marks the winner with a compact badge beside the name plate', async () => {
    mountRoom(tableWith([
      seat('me', 'Alice', 2600, { winner: true, cards: ['As', 'Kd'] }),
      seat('bob', 'Bob', 1400)
    ]))
    await new Promise(resolve => setTimeout(resolve, 0))
    const badge = root.querySelector('.winner-badge')
    expect(badge).not.toBeNull()
    // 只留奖杯图标 + 挂在名牌右侧（样式里 right:-15px、垂直居中）：
    // 带文字的宽徽标会压住昵称和筹码，放在上方又会压住底牌花色
    expect(badge.textContent.trim()).toBe('🏆')
    expect(badge.getAttribute('title')).toContain('胜者')
    expect(badge.parentElement.className).toContain('player-chip')
    // 昵称和筹码仍在牌面里，没有被徽标顶掉
    const plate = badge.parentElement
    expect(plate.querySelector('.player-name').textContent).toContain('Alice')
    expect(plate.querySelector('.player-stack').textContent).toContain('2600')
  })

  it('does not bother the table when only an AI seat is empty', async () => {
    mountRoom(tableWith([
      seat('me', 'Alice', 2000),
      seat('bob', 'Bob', 1500),
      seat('ai', '机器人', 0, { reserveChips: 5000, ai: true })
    ]))
    await clickStart()
    expect(window.confirm).not.toHaveBeenCalled()
    expect(started).toHaveLength(1)
  })
})
