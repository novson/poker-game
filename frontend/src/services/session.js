const SESSION_KEY = 'poker.session'

function validSession(value) {
  return value
    && typeof value.tableId === 'string'
    && typeof value.playerId === 'string'
    && typeof value.reconnectToken === 'string'
}

export function readPokerSession(storage = localStorage) {
  try {
    const value = JSON.parse(storage.getItem(SESSION_KEY))
    return validSession(value) ? value : null
  } catch {
    return null
  }
}

export function savePokerSession(session, storage = localStorage) {
  const value = {
    tableId: session.tableId,
    playerId: session.playerId,
    reconnectToken: session.reconnectToken,
    tableName: session.tableName || '',
    nickname: session.nickname || '',
    autoResume: session.autoResume !== false
  }
  storage.setItem(SESSION_KEY, JSON.stringify(value))
  return value
}

export function clearPokerSession(storage = localStorage) {
  storage.removeItem(SESSION_KEY)
}
