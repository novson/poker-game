// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h } from 'vue'
import App from './App.vue'
import { api } from './services/api'

vi.mock('./services/api', () => ({ api: {
  settings: vi.fn(), listTables: vi.fn(), accountProfile: vi.fn(), activeAccountSeat: vi.fn(),
  reconnect: vi.fn(), getTable: vi.fn(), start: vi.fn(), leave: vi.fn()
} }))
vi.mock('./services/socket', () => ({ watchTable: vi.fn(() => () => {}) }))
vi.mock('./components/PokerRoom.vue', () => ({ default: {
  emits: ['start', 'settle-leave'],
  setup(_, { emit }) { return () => h('div', { 'data-testid': 'room' }, [
    h('button', { onClick: () => emit('start') }, '测试开局'),
    h('button', { onClick: () => emit('settle-leave') }, '测试离桌')
  ]) }
} }))

let app, root
const table = { id: 'a', version: 1, phase: 'WAITING', name: '测试牌桌', players: [{ id: 'p' }] }
const settle = () => new Promise(resolve => setTimeout(resolve, 0))

beforeEach(async () => {
  vi.clearAllMocks()
  localStorage.clear()
  localStorage.setItem('poker.session', JSON.stringify({ tableId: 'a', playerId: 'p', reconnectToken: 't', autoResume: true }))
  api.settings.mockResolvedValue({ minBuyIn: 1000, maxBuyIn: 4000, defaultBuyIn: 2000, bigBlind: 20 })
  api.listTables.mockResolvedValue([])
  api.reconnect.mockResolvedValue({ reconnectToken: 't', table })
  api.getTable.mockResolvedValue(table)
  root = document.createElement('div')
  document.body.append(root)
  app = createApp(App)
  app.mount(root)
  await settle()
})

afterEach(() => { app.unmount(); root.remove(); localStorage.clear() })

it('shows a failed action in a global alert while the room is open', async () => {
  api.start.mockRejectedValue(new Error('至少需要两名还有筹码的玩家'))
  root.querySelector('[data-testid="room"] button').click()
  await settle()
  expect(root.querySelector('[data-testid="room"]')).not.toBeNull()
  expect(root.querySelector('[role="alert"]').textContent).toContain('至少需要两名还有筹码的玩家')
})

it('clears the saved seat and returns to the lobby after settled departure', async () => {
  api.leave.mockResolvedValue({ pending: false, table: null })
  root.querySelectorAll('[data-testid="room"] button')[1].click()
  await settle()
  expect(root.querySelector('[data-testid="room"]')).toBeNull()
  expect(localStorage.getItem('poker.session')).toBeNull()
  expect(root.querySelector('[role="status"]').textContent).toContain('已结算离桌')
})
