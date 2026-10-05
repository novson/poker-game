import { Client } from '@stomp/stompjs'

export function watchTable(tableId, onChange, onStatus, onEvent, onClosed) {
  let stopped = false
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  const client = new Client({
    brokerURL: `${scheme}://${location.host}/ws`,
    reconnectDelay: 2000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      if (stopped) return
      onStatus?.(true)
      client.subscribe(`/topic/tables/${tableId}`, message => {
        if (stopped) return
        let event
        try { event = JSON.parse(message.body) } catch (_) { event = null }
        if (event?.version === -1) {
          stopped = true
          onStatus?.(false)
          client.deactivate()
          onClosed?.()
        } else if (event?.type === 'EMOTE') onEvent?.(event)
        else onChange()
      })
      onChange()
    },
    onWebSocketClose: () => { if (!stopped) onStatus?.(false) },
    onStompError: () => { if (!stopped) onStatus?.(false) }
  })
  client.activate()
  return () => { stopped = true; return client.deactivate() }
}

