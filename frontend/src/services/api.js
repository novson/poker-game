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
  listTables: () => request('/api/tables'),
  createTable: (payload) => request('/api/tables', {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify(payload)
  }),
  joinTable: (tableId, nickname) => request(`/api/tables/${tableId}/join`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ nickname })
  }),
  reconnect: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}/reconnect`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, reconnectToken })
  }),
  getTable: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}?${new URLSearchParams({ playerId, reconnectToken })}`),
  start: (tableId, playerId, reconnectToken) => request(`/api/tables/${tableId}/start`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, reconnectToken })
  }),
  act: (tableId, playerId, reconnectToken, type, raiseTo) => request(`/api/tables/${tableId}/actions`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, reconnectToken, type, raiseTo })
  })
}

