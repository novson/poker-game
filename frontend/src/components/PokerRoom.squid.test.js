// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h } from 'vue'
import PokerRoom from './PokerRoom.vue'

function seat(id, nickname, chips, extra = {}) {
  return { id, nickname, chips, reserveChips: 0, streetBet: 0, handBet: 0, cards: [],
    status: 'ACTIVE', seat: 0, leaving: false, ai: false, squidMarkers: 0, squidId: false,
    squidDebt: 0, ...extra }
}

function squidState(extra = {}) {
  return { enabled: true, markerTotal: 4, markersRemaining: 2, penaltyBigBlinds: 3,
    penaltyAmount: 60, round: 1, penaltyPending: false, penaltyPrompt: null,
    players: [
      { id: 'me', nickname: 'Alice', markers: 2, squidId: false, debt: 0 },
      { id: 'bob', nickname: 'Bob', markers: 0, squidId: false, debt: 0 }
    ], ...extra }
}

function tableWith(extra = {}) {
  return { id: 't1', version: 3, name: '鱿鱼桌', phase: 'SHOWDOWN', handNumber: 2,
    pot: 0, currentBet: 0, minRaise: 20, communityCards: [], message: 'Bob 获得 1 个鱿鱼标记',
    minBuyIn: 1000, maxBuyIn: 4000, defaultBuyIn: 2000, smallBlind: 10, bigBlind: 20,
    totalChips: 10000, maxPlayers: 6, privateTable: false, actionTimeSeconds: 25,
    actionDeadline: 0, showdownHoldUntil: 0, isOwner: true,
    players: [seat('me', 'Alice', 2600), seat('bob', 'Bob', 1400)],
    canSettle: false, settlementOpen: false, settlement: null, squid: squidState(), ...extra }
}

let app, root, squidCalls

function mountRoom(table, playerId = 'me') {
  root = document.createElement('div')
  document.body.append(root)
  squidCalls = []
  app = createApp({
    render: () => h(PokerRoom, {
      table, playerId, busy: false, connected: true, refreshing: false,
      onSettleSquid: () => squidCalls.push(true)
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

describe('鱿鱼游戏', () => {
  it('shows the panel only on tables that enabled it', async () => {
    mountRoom(tableWith({ squid: null }))
    await nextTick()
    expect(root.querySelector('.squid-panel')).toBeNull()

    app.unmount(); root.remove()
    mountRoom(tableWith())
    await nextTick()
    expect(root.querySelector('.squid-panel')).not.toBeNull()
  })

  it('shows the round, the remaining markers and the penalty rule', async () => {
    mountRoom(tableWith())
    await nextTick()
    const panel = root.querySelector('.squid-panel')
    expect(panel.textContent).toContain('鱿鱼游戏')
    expect(panel.textContent).toContain('第 1 轮')
    expect(panel.textContent).toContain('剩余标记')
    expect(panel.textContent).toContain('3 BB')
    expect(panel.textContent).toContain('60 筹码')
  })

  it('lists every player with their marker count', async () => {
    mountRoom(tableWith())
    await nextTick()
    const chips = [...root.querySelectorAll('.squid-chip')]
    expect(chips).toHaveLength(2)
    expect(chips[0].textContent).toContain('Alice')
    expect(chips[0].textContent).toContain('🐙 ×2')
    expect(chips[1].textContent).toContain('Bob')
    expect(chips[1].textContent).toContain('无标记')
  })

  it('marks the squid on the seat plate', async () => {
    mountRoom(tableWith({
      squid: squidState({
        players: [
          { id: 'me', nickname: 'Alice', markers: 1, squidId: false, debt: 0 },
          { id: 'bob', nickname: 'Bob', markers: 0, squidId: true, debt: 0 }
        ]
      }),
      players: [seat('me', 'Alice', 2600, { squidMarkers: 1, seat: 0 }),
        seat('bob', 'Bob', 1400, { squidId: true, seat: 1 })]
    }), 'bob')
    await nextTick()
    const badge = root.querySelector('.squid-id-badge')
    expect(badge).not.toBeNull()
    expect(badge.textContent).toContain('鱿鱼')
  })

  it('shows the marker badge on the seat that owns one', async () => {
    mountRoom(tableWith({
      players: [seat('me', 'Alice', 2600, { squidMarkers: 2, seat: 0 }),
        seat('bob', 'Bob', 1400, { seat: 1 })]
    }), 'bob')
    await nextTick()
    const badge = root.querySelector('.squid-marker-badge')
    expect(badge).not.toBeNull()
    expect(badge.textContent).toContain('🐙')
    expect(badge.textContent).toContain('2')
  })

  it('warns about the pending penalty and offers the squid a settle button', async () => {
    mountRoom(tableWith({
      squid: squidState({
        penaltyPending: true,
        penaltyPrompt: 'Bob 需要赔付（每人 60 筹码）才能开始下一局',
        players: [
          { id: 'me', nickname: 'Alice', markers: 2, squidId: false, debt: 0 },
          { id: 'bob', nickname: 'Bob', markers: 0, squidId: true, debt: 60 }
        ]
      }),
      players: [seat('me', 'Alice', 2600, { squidDebt: 0 }), seat('bob', 'Bob', 1400, { squidDebt: 60 })]
    }), 'bob')
    await nextTick()
    const warning = root.querySelector('.squid-warning')
    expect(warning).not.toBeNull()
    expect(warning.textContent).toContain('Bob 需要赔付')
    const button = root.querySelector('.squid-settle')
    expect(button).not.toBeNull()
    expect(button.textContent).toContain('60')
    button.click()
    await nextTick()
    expect(squidCalls).toEqual([true])
  })

  it('does not offer the settle button to players without a debt', async () => {
    mountRoom(tableWith({
      squid: squidState({ penaltyPending: true }),
      players: [seat('me', 'Alice', 2600, { squidDebt: 0 }), seat('bob', 'Bob', 1400, { squidDebt: 60 })]
    }))
    await nextTick()
    expect(root.querySelector('.squid-settle')).toBeNull()
  })
})
