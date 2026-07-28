const jsonHeaders = { 'Content-Type': 'application/json' }

async function request(url, options = {}) {
  const response = await fetch(url, options)
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    const error = new Error(body.message || `请求失败（${response.status}）`)
    error.status = response.status
    throw error
  }
  return body
}

export const api = {
  settings: () => request('/api/settings'),
  listTables: () => request('/api/tables'),
  createTable: (payload) => request('/api/tables', {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify(payload)
  }),
  joinTable: (tableId, nickname, buyIn) => request(`/api/tables/${tableId}/join`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ nickname, buyIn })
  }),
  reconnect: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}/reconnect`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, reconnectToken })
  }),
  getTable: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}?${new URLSearchParams({ playerId, reconnectToken })}`),
  advice: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}/advice?${new URLSearchParams({ playerId, reconnectToken })}`),
  start: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}/start`, {
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
  })
}

