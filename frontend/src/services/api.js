const jsonHeaders = { 'Content-Type': 'application/json' }

async function request(url, options = {}) {
  const response = await fetch(url, options)
  const body = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(body.message || `请求失败（${response.status}）`)
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
  getTable: (tableId, playerId) => request(`/api/tables/${tableId}?playerId=${playerId}`),
  start: (tableId, playerId) => request(`/api/tables/${tableId}/start`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId })
  }),
  act: (tableId, playerId, type, raiseTo) => request(`/api/tables/${tableId}/actions`, {
    method: 'POST', headers: jsonHeaders, body: JSON.stringify({ playerId, type, raiseTo })
  })
}

