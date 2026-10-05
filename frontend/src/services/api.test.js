import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'

const calls = []

function respond(body, status = 200) {
  return Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) })
}

beforeEach(() => {
  calls.length = 0
  vi.stubGlobal('fetch', (url, options = {}) => {
    calls.push({ url, options })
    return respond({ ok: true })
  })
})

describe('account login codes', () => {
  it('sends the requested code when a custom one is supplied', async () => {
    await api.rotateAccountLoginCode('acc-1', 'token-1', 'MYCODE123456')
    expect(calls[0].url).toBe('/api/accounts/acc-1/login-code')
    expect(calls[0].options.method).toBe('POST')
    expect(calls[0].options.headers['X-Account-Token']).toBe('token-1')
    expect(JSON.parse(calls[0].options.body)).toEqual({ loginCode: 'MYCODE123456' })
  })

  it('keeps random rotation when no code is given', async () => {
    await api.rotateAccountLoginCode('acc-1', 'token-1')
    expect(JSON.parse(calls[0].options.body)).toEqual({ loginCode: null })
  })
})

describe('admin account management', () => {
  it('lists accounts with the admin token only', async () => {
    await api.adminAccounts('admin-token')
    expect(calls[0].url).toBe('/api/admin/accounts')
    expect(calls[0].options.headers['X-Admin-Token']).toBe('admin-token')
    expect(calls[0].options.method).toBeUndefined()
  })

  it('patches only the supplied fields', async () => {
    await api.updateAdminAccount('admin-token', 'acc-1', { nickname: 'River', chips: 800 })
    expect(calls[0].url).toBe('/api/admin/accounts/acc-1')
    expect(calls[0].options.method).toBe('PATCH')
    expect(JSON.parse(calls[0].options.body)).toEqual({ nickname: 'River', chips: 800 })
  })

  it('can patch just the login code', async () => {
    await api.updateAdminAccount('admin-token', 'acc-1', { loginCode: 'ADMINSET1234' })
    expect(JSON.parse(calls[0].options.body)).toEqual({ loginCode: 'ADMINSET1234' })
  })

  it('deletes an account', async () => {
    await api.deleteAdminAccount('admin-token', 'acc-1')
    expect(calls[0].url).toBe('/api/admin/accounts/acc-1')
    expect(calls[0].options.method).toBe('DELETE')
  })
})

describe('error mapping', () => {
  it('surfaces the server message and status', async () => {
    vi.stubGlobal('fetch', () => respond({ message: '该账号仍在牌局中，请先离桌再删除' }, 400))
    await expect(api.deleteAdminAccount('admin-token', 'acc-1')).rejects.toMatchObject({
      status: 400, message: '该账号仍在牌局中，请先离桌再删除'
    })
  })
})
