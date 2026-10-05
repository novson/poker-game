const jsonHeaders = { 'Content-Type': 'application/json' }

async function request(url, options = {}) {
  let response
  try {
    response = await fetch(url, { ...options, signal: AbortSignal.timeout(15000) })
  } catch (cause) {
    throw new Error(cause.name === 'TimeoutError' ? '请求超时，请刷新牌桌确认结果后再操作' : '网络连接失败，请检查网络后重试')
  }
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    const error = new Error(body.message || (response.status >= 500
      ? '服务暂时不可用，请稍后重试' : `请求失败（${response.status}）`))
    error.status = response.status
    error.code = body.code
    throw error
  }
  return body
}

export const api = {
  createAccount: (nickname) => request('/api/accounts', {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ nickname })
  }),
  loginAccount: (nickname, loginCode) => request('/api/accounts/login', {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ nickname, loginCode })
  }),
  accountProfile: (accountId, accountToken) => request(`/api/accounts/${accountId}`, {
    headers: { 'X-Account-Token': accountToken }
  }),
  activeAccountSeat: (accountId, accountToken) => request(`/api/accounts/${accountId}/active-seat`, {
    headers: { 'X-Account-Token': accountToken }
  }),
  // loginCode 为空时服务端随机生成（省略即保持原行为）
  rotateAccountLoginCode: (accountId, accountToken, loginCode) => request(`/api/accounts/${accountId}/login-code`, {
    method: 'POST', headers: { ...jsonHeaders, 'X-Account-Token': accountToken },
    body: JSON.stringify({ loginCode: loginCode || null })
  }),
  settings: () => request('/api/settings'),
  listTables: () => request('/api/tables'),
  createTable: (payload) => request('/api/tables', {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify(payload)
  }),
  joinTable: (tableId, nickname, buyIn, account) => request(`/api/tables/${tableId}/join`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ nickname, buyIn,
      accountId: account?.accountId, accountToken: account?.accountToken })
  }),
  reconnect: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}/reconnect`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, reconnectToken })
  }),
  getTable: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}?${new URLSearchParams({ playerId, reconnectToken })}`),
  advice: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}/advice?${new URLSearchParams({ playerId, reconnectToken })}`),
  start: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}/start`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, reconnectToken })
  }),
  leave: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}/leave`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, reconnectToken })
  }),
  act: (tableId, playerId, reconnectToken, type, raiseTo) => request(`/api/tables/${tableId}/actions`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, reconnectToken, type, raiseTo })
  }),
  topUp: (tableId, playerId, reconnectToken, amount) => request(`/api/tables/${tableId}/chips/top-up`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, reconnectToken, amount })
  }),
  cashOut: (tableId, playerId, reconnectToken, amount) => request(`/api/tables/${tableId}/chips/cash-out`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, reconnectToken, amount })
  }),
  emote: (tableId, playerId, reconnectToken, emoteId) => request(`/api/tables/${tableId}/emotes`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, reconnectToken, emoteId })
  }),
  adminSettings: (token) => request('/api/admin/settings', {
    headers: { 'X-Admin-Token': token }
  }),
  updateAdminSettings: (token, settings) => request('/api/admin/settings', {
    method: 'PUT', headers: { ...jsonHeaders, 'X-Admin-Token': token },
    body: JSON.stringify(settings)
  }),
  adminTables: (token) => request('/api/admin/tables', {
    headers: { 'X-Admin-Token': token }
  }),
  deleteAdminTable: (token, tableId) => request(`/api/admin/tables/${tableId}`, {
    method: 'DELETE', headers: { 'X-Admin-Token': token }
  }),
  adminAccounts: (token) => request('/api/admin/accounts', {
    headers: { 'X-Admin-Token': token }
  }),
  // patch 可含 nickname / chips / loginCode，省略的字段保持不变
  updateAdminAccount: (token, accountId, patch) => request(`/api/admin/accounts/${accountId}`, {
    method: 'PATCH', headers: { ...jsonHeaders, 'X-Admin-Token': token },
    body: JSON.stringify(patch)
  }),
  deleteAdminAccount: (token, accountId) => request(`/api/admin/accounts/${accountId}`, {
    method: 'DELETE', headers: { 'X-Admin-Token': token }
  })
}

