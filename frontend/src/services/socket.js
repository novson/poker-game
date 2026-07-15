import { Client } from '@stomp/stompjs'

export function watchTable(tableId, onChange, onStatus) {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  const client = new Client({
    brokerURL: `${scheme}://${location.host}/ws`,
    reconnectDelay: 2000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      onStatus?.(true)
      client.subscribe(`/topic/tables/${tableId}`, () => onChange())
    },
    onWebSocketClose: () => onStatus?.(false),
    onStompError: () => onStatus?.(false)
  })
  client.activate()
  return () => client.deactivate()
}

