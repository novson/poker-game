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
    ], settlements: [], ...extra }
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

// 展开明细：状态条整条可点
async function expandDetail() {
  const bar = root.querySelector('.squid-bar')
  bar.click()
  await nextTick()
}

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

  it('keeps the detail collapsed so the panel only takes one row', async () => {
    mountRoom(tableWith())
    await nextTick()
    // 明细默认收起：面板不再常驻一整块，把牌桌空间让给公共牌和手牌
    expect(root.querySelector('.squid-detail')).toBeNull()
    expect(root.querySelector('.squid-bar')).not.toBeNull()
    // 状态条本身要包含足够的概览信息
    const bar = root.querySelector('.squid-bar')
    expect(bar.textContent).toContain('鱿鱼游戏')
    expect(bar.textContent).toContain('剩余标记')
    expect(bar.textContent).toContain('赔付')
  })

  it('expands and collapses the detail on click', async () => {
    mountRoom(tableWith())
    await nextTick()
    expect(root.querySelector('.squid-detail')).toBeNull()

    await expandDetail()
    expect(root.querySelector('.squid-detail')).not.toBeNull()
    expect(root.querySelector('.squid-chip')).not.toBeNull()

    await expandDetail()
    expect(root.querySelector('.squid-detail')).toBeNull()
  })

  it('shows the marker progress as a single bar instead of one slot per marker', async () => {
    mountRoom(tableWith({
      squid: squidState({ markerTotal: 60, markersRemaining: 15 })
    }))
    await nextTick()
    const progress = root.querySelector('.squid-bar-progress')
    expect(progress).not.toBeNull()
    // 60 个标记用槽位画会换行撑高面板，改成单条进度条
    expect(progress.querySelectorAll('.squid-progress-slot')).toHaveLength(0)
    const fill = progress.querySelector('i > b')
    expect(fill).not.toBeNull()
    expect(fill.style.width).toBe('75%')
    expect(progress.textContent).toContain('45/60')
  })

  it('lists every player with their marker count', async () => {
    mountRoom(tableWith())
    await nextTick()
    await expandDetail()
    const chips = [...root.querySelectorAll('.squid-chip')]
    expect(chips).toHaveLength(2)
    expect(chips[0].textContent).toContain('Alice')
    expect(chips[0].textContent).toContain('×2')
    // 标记图标用内联 SVG 画（不用 emoji，系统字形会跳色）
    expect(chips[0].querySelector('svg use')).not.toBeNull()
    expect(chips[0].querySelector('svg use').getAttribute('href')).toBe('#squid-glyph')
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
    expect(badge.querySelector('svg use')).not.toBeNull()
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
    // 有待结清赔付时明细必须自动展开：警告和结算按钮不能被折叠藏起来
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

  it('shows the settlement box with who pays whom', async () => {
    mountRoom(tableWith({
      squid: squidState({
        settlements: [
          { squidId: 'bob', squid: 'Bob', recipientId: 'me', recipient: 'Alice',
            amount: 60, paid: 60, outstanding: 0, settled: true },
          { squidId: 'bob', squid: 'Bob', recipientId: 'carol', recipient: 'Carol',
            amount: 60, paid: 60, outstanding: 0, settled: true }
        ]
      })
    }))
    await nextTick()
    await expandDetail()
    const box = root.querySelector('.squid-settlement-box')
    expect(box).not.toBeNull()
    expect(box.textContent).toContain('本轮赔付结算')
    expect(box.textContent).toContain('共 120 筹码')
    const rows = [...root.querySelectorAll('.squid-settlement-row')]
    expect(rows).toHaveLength(2)
    expect(rows[0].textContent).toContain('Bob')
    expect(rows[0].textContent).toContain('Alice')
    expect(rows[0].textContent).toContain('60 筹码')
    expect(rows[0].textContent).toContain('已赔付')
    expect(rows[1].textContent).toContain('Carol')
  })

  it('shows the outstanding amount while the penalty is not settled', async () => {
    mountRoom(tableWith({
      squid: squidState({
        penaltyPending: true,
        penaltyPrompt: 'Bob 需要赔付（每人 60 筹码）才能开始下一局',
        settlements: [
          { squidId: 'bob', squid: 'Bob', recipientId: 'me', recipient: 'Alice',
            amount: 60, paid: 20, outstanding: 40, settled: false }
        ]
      })
    }))
    await nextTick()
    const row = root.querySelector('.squid-settlement-row')
    expect(row).not.toBeNull()
    expect(row.classList.contains('pending')).toBe(true)
    expect(row.textContent).toContain('已赔 20')
    expect(row.textContent).toContain('还欠 40')
  })

  it('hides the settlement box when the round has no penalty yet', async () => {
    mountRoom(tableWith())
    await nextTick()
    expect(root.querySelector('.squid-settlement-box')).toBeNull()
  })
})
