// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h } from 'vue'
import PokerRoom from './PokerRoom.vue'

function seat(id, nickname, chips, extra = {}) {
  return { id, nickname, chips, reserveChips: 0, streetBet: 0, handBet: 0, cards: [],
    status: 'ACTIVE', seat: 0, leaving: false, ai: false, ...extra }
}

const preview = {
  players: [
    { id: 'me', nickname: 'Alice', ai: false, owner: true, bankroll: 2000, current: 2600, net: 600 },
    { id: 'bob', nickname: 'Bob', ai: false, owner: false, bankroll: 2000, current: 1400, net: -600 }
  ],
  transfers: [{ fromId: 'bob', from: 'Bob', toId: 'me', to: 'Alice', amount: 600 }],
  note: '共 1 笔转账就能结清，转账后每人到手的钱等于开局本金加本场盈亏。'
}

function tableWith(extra = {}) {
  return { id: 't1', version: 3, name: '测试牌桌', phase: 'SHOWDOWN', handNumber: 1,
    pot: 0, currentBet: 0, minRaise: 20, communityCards: [], message: '',
    minBuyIn: 1000, maxBuyIn: 4000, defaultBuyIn: 2000, smallBlind: 10, bigBlind: 20,
    totalChips: 10000, maxPlayers: 6, privateTable: false, actionTimeSeconds: 25,
    actionDeadline: 0, players: [seat('me', 'Alice', 2600), seat('bob', 'Bob', 1400)],
    canSettle: false, settlementOpen: false, settlement: null, ...extra }
}

let app, root, settleCalls

function mountRoom(table, playerId = 'me') {
  root = document.createElement('div')
  document.body.append(root)
  settleCalls = []
  app = createApp({
    render: () => h(PokerRoom, {
      table, playerId, busy: false, connected: true, refreshing: false,
      onSettle: open => settleCalls.push(open)
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

describe('本局试算', () => {
  it('shows the toggle only to the table creator', async () => {
    mountRoom(tableWith({ canSettle: false }))
    await nextTick()
    expect(root.querySelector('.settlement-toggle')).toBeNull()

    app.unmount(); root.remove()
    mountRoom(tableWith({ canSettle: true }))
    await nextTick()
    expect(root.querySelector('.settlement-toggle')).not.toBeNull()
  })

  it('asks the server to open and close the preview', async () => {
    mountRoom(tableWith({ canSettle: true }))
    await nextTick()
    root.querySelector('.settlement-toggle').click()
    await nextTick()
    expect(settleCalls).toEqual([true])

    app.unmount(); root.remove()
    mountRoom(tableWith({ canSettle: true, settlementOpen: true, settlement: preview }))
    await nextTick()
    root.querySelector('.settlement-panel .settlement-head-actions button').click()
    await nextTick()
    expect(settleCalls).toEqual([false])
  })

  it('shows the same numbers to everyone once it is open', async () => {
    // Bob 不是创建人：没有按钮，但看得到创建人打开的试算
    mountRoom(tableWith({ canSettle: false, settlementOpen: true, settlement: preview }), 'bob')
    await nextTick()
    expect(root.querySelector('.settlement-toggle')).toBeNull()

    const panel = root.querySelector('.settlement-panel')
    expect(panel).not.toBeNull()
    const text = panel.textContent
    expect(text).toContain('本局试算')
    expect(text).toContain('Alice')
    expect(text).toContain('Bob')
    expect(text).toContain('+600')
    expect(text).toContain('-600')
    expect(panel.querySelector('.settlement-transfers li').textContent).toContain('Bob')
    expect(panel.querySelector('.settlement-transfers li').textContent).toContain('600')
    expect(text).toContain('1 笔')
    expect(panel.querySelector('.settlement-note').textContent).toContain('转账后每人到手的钱')
    // 房主标记只给创建人
    expect(panel.querySelectorAll('.settlement-tag')).toHaveLength(1)
    expect(panel.querySelector('.settlement-tag').textContent).toBe('房主')
  })

  it('lets a non-creator hide the panel locally', async () => {
    mountRoom(tableWith({ canSettle: false, settlementOpen: true, settlement: preview }), 'bob')
    await nextTick()
    const buttons = root.querySelectorAll('.settlement-panel .settlement-head-actions button')
    expect(buttons).toHaveLength(1)
    buttons[0].click()
    await nextTick()
    expect(root.querySelector('.settlement-panel')).toBeNull()
    expect(settleCalls).toHaveLength(0)
  })

  it('says nothing is owed when everyone is even', async () => {
    mountRoom(tableWith({
      canSettle: true, settlementOpen: true, settlement: {
        players: [{ id: 'me', nickname: 'Alice', ai: false, owner: true, bankroll: 2000, current: 2000, net: 0 }],
        transfers: [],
        note: '大家都不输不赢，不用转账'
      }
    }))
    await nextTick()
    const panel = root.querySelector('.settlement-panel')
    expect(panel.querySelector('.settlement-empty').textContent).toContain('不需要转账')
    expect(panel.querySelector('.settlement-transfers')).toBeNull()
    expect(panel.textContent).toContain('0')
  })

  it('marks AI rows and keeps them out of the transfer list', async () => {
    mountRoom(tableWith({
      canSettle: true, settlementOpen: true, settlement: {
        players: [
          { id: 'me', nickname: 'Alice', ai: false, owner: true, bankroll: 2000, current: 2000, net: 100 },
          { id: 'ai', nickname: 'AI·小河', ai: true, owner: false, bankroll: 2000, current: 2200, net: 200 },
          { id: 'bob', nickname: 'Bob', ai: false, owner: false, bankroll: 2000, current: 1800, net: -100 }
        ],
        transfers: [{ fromId: 'bob', from: 'Bob', toId: 'me', to: 'Alice', amount: 100 }],
        note: 'AI（AI·小河）的盈亏已并入房主 Alice，由房主统一结算。'
      }
    }))
    await nextTick()
    const panel = root.querySelector('.settlement-panel')
    expect(panel.textContent).toContain('AI·小河')
    expect(panel.querySelector('.settlement-transfers').textContent).not.toContain('AI')
    expect(panel.querySelector('.settlement-note').textContent).toContain('房主')
  })
})
