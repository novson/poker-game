// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp } from 'vue'
import App from './App.vue'
import { api } from './services/api'

vi.mock('./services/api', () => ({ api: {
  settings: vi.fn(), listTables: vi.fn(), createAccount: vi.fn(), createTable: vi.fn(),
  activeAccountSeat: vi.fn(), accountProfile: vi.fn()
} }))
vi.mock('./services/socket', () => ({ watchTable: vi.fn(() => () => {}) }))

let app, root
const settle = () => new Promise(resolve => setTimeout(resolve, 0))
const rules = { minBuyIn: 1000, maxBuyIn: 4000, defaultBuyIn: 2000, totalChips: 10000, bigBlind: 20 }
beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  localStorage.setItem('poker.nickname', '测试玩家')
  api.settings.mockResolvedValue(rules)
  api.listTables.mockResolvedValue([])
  root = document.createElement('div')
  document.body.append(root)
})
async function mount() { app = createApp(App); app.mount(root); await settle() }
afterEach(() => { app?.unmount(); root.remove(); localStorage.clear() })

it('distinguishes loading from an empty lobby', async () => {
  api.listTables.mockReturnValue(new Promise(() => {}))
  await mount()
  expect(root.querySelector('#open-tables').textContent).toContain('正在加载牌桌')
  expect(root.querySelector('.empty-lobby')).toBeNull()
})

it('shows an inline error and recovers after retry', async () => {
  api.listTables.mockRejectedValueOnce(new Error('网络连接失败')).mockResolvedValue([])
  await mount()
  expect(root.querySelector('#open-tables').textContent).toContain('网络连接失败')
  expect(root.querySelector('.empty-lobby')).toBeNull()
  root.querySelector('[data-testid="refresh-lobby"]').click()
  await settle()
  expect(root.querySelector('.empty-lobby')).not.toBeNull()
  expect(root.querySelector('.lobby-error')).toBeNull()
})

it('does not replace an edited buy-in during a lobby refresh', async () => {
  api.listTables.mockResolvedValue([{ ...rules, id: 'one', name: '好友桌', phase: 'WAITING', playerCount: 1, maxPlayers: 6 }])
  await mount()
  const input = root.querySelector('.row-buy-in input')
  input.value = '999'
  input.dispatchEvent(new Event('input', { bubbles: true }))
  root.querySelector('[data-testid="refresh-lobby"]').click()
  await settle()
  expect(input.value).toBe('999')
  expect(root.querySelector('.table-row > button').disabled).toBe(true)
})

it('blocks duplicate submission through the entire account and seat lookup', async () => {
  let resolveSeat
  api.createAccount.mockResolvedValue({ accountId: 'a', accountToken: 't', profile: { nickname: '测试玩家', chips: 10000 } })
  api.activeAccountSeat.mockReturnValue(new Promise(resolve => { resolveSeat = resolve }))
  api.createTable.mockRejectedValue(new Error('测试结束'))
  await mount()
  const form = root.querySelector('.create-card')
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await settle()
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await settle()
  expect(api.activeAccountSeat).toHaveBeenCalledTimes(1)
  expect(root.querySelector('.create-submit').disabled).toBe(true)
  resolveSeat(null)
  await settle()
  expect(api.createTable).toHaveBeenCalledTimes(1)
})
