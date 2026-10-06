// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h } from 'vue'
import PokerRoom from './PokerRoom.vue'

function seat(id, nickname, chips, extra = {}) {
  return { id, nickname, chips, reserveChips: 0, streetBet: 0, handBet: 0, cards: [],
    status: 'ACTIVE', seat: 0, leaving: false, ai: false, ...extra }
}

function tableWith(players, extra = {}) {
  return { id: 't1', version: 3, name: '测试牌桌', phase: 'SHOWDOWN', handNumber: 1,
    pot: 0, currentBet: 0, minRaise: 20, communityCards: [], message: '',
    minBuyIn: 1000, maxBuyIn: 4000, defaultBuyIn: 2000, smallBlind: 10, bigBlind: 20,
    totalChips: 10000, maxPlayers: 6, privateTable: false, actionTimeSeconds: 25,
    actionDeadline: 0, players, ...extra }
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
