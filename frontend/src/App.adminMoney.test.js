// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp } from 'vue'
import App from './App.vue'
import { api } from './services/api'

vi.mock('./services/api', () => ({ api: {
  settings: vi.fn(), listTables: vi.fn(), adminSettings: vi.fn(), adminTables: vi.fn(),
  adminAccounts: vi.fn(), updateAdminSettings: vi.fn(), deleteAdminTable: vi.fn(),
  deleteAdminAccount: vi.fn(), updateAdminAccount: vi.fn()
} }))
vi.mock('./services/socket', () => ({ watchTable: vi.fn(() => () => {}) }))

let app, root
const settle = () => new Promise(resolve => setTimeout(resolve, 0))

beforeEach(async () => {
  vi.clearAllMocks()
  localStorage.clear()
  sessionStorage.clear()
  api.settings.mockResolvedValue({ minBuyIn: 5001, maxBuyIn: 20001, defaultBuyIn: 10001, bigBlind: 50 })
  api.listTables.mockResolvedValue([])
  api.adminSettings.mockResolvedValue({ totalChips: 50000, minBuyIn: 5001,
    defaultBuyIn: 10001, maxBuyIn: 20001, smallBlind: 25, bigBlind: 50 })
  api.adminTables.mockResolvedValue([])
  api.adminAccounts.mockResolvedValue([])
  api.updateAdminSettings.mockResolvedValue({ totalChips: 50000, minBuyIn: 5000,
    defaultBuyIn: 10000, maxBuyIn: 20000, smallBlind: 25, bigBlind: 50 })
  sessionStorage.setItem('poker.adminToken', '88914752')
  root = document.createElement('div')
  document.body.append(root)
  app = createApp(App)
  app.mount(root)
  await settle()
})

afterEach(() => {
  app.unmount()
  root.remove()
  localStorage.clear()
  sessionStorage.clear()
})

function moneyInput(label) {
  const field = [...root.querySelectorAll('.admin-money-settings label')]
    .find(item => item.textContent.includes(label))
  return field.querySelector('input')
}

async function openAdminPanel() {
  root.querySelector('.admin-link').click()
  await settle()
  await settle()
}

it('keeps money inputs free of the big-blind step so round numbers are valid', async () => {
  await openAdminPanel()
  for (const label of ['最低带入', '默认带入', '最高带入', '单次总筹码']) {
    expect(moneyInput(label).getAttribute('step')).toBe('1')
  }

  const minimum = moneyInput('最低带入')
  minimum.value = '5000'
  expect(minimum.checkValidity()).toBe(true)
  expect(minimum.validity.stepMismatch).toBe(false)
})

it('saves the deep stack preset with round 5000/10000/20000 numbers', async () => {
  await openAdminPanel()
  const preset = [...root.querySelectorAll('.money-presets button')]
    .find(button => button.textContent.includes('深筹'))
  preset.click()
  await settle()

  expect(Number(moneyInput('最低带入').value)).toBe(5000)
  expect(Number(moneyInput('默认带入').value)).toBe(10000)
  expect(Number(moneyInput('最高带入').value)).toBe(20000)

  root.querySelector('.admin-money-settings button.gold').click()
  await settle()

  expect(api.updateAdminSettings).toHaveBeenCalledTimes(1)
  expect(api.updateAdminSettings.mock.calls[0][1]).toEqual({
    totalChips: 50000, minBuyIn: 5000, defaultBuyIn: 10000, maxBuyIn: 20000,
    smallBlind: 25, bigBlind: 50
  })
})
