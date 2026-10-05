export function shouldApplyTable(current, latest) {
  return Boolean(current && latest && current.id === latest.id
    && Number(latest.version || 0) >= Number(current.version || 0))
}

export function tableExitMessage(error) {
  if (error?.code === 'SEAT_LEFT') return '已结算离桌，筹码与战绩已保存，可以加入其他牌桌'
  if (error?.code === 'TABLE_CLOSED' || error?.status === 404) return '牌桌已关闭，请选择其他牌桌'
  if (error?.code === 'INVALID_SESSION') return '牌桌身份已失效，请从账号恢复座位'
  return ''
}

export function createTableRefresh({ load, onTable, onError }) {
  let stopped = false
  let running = false
  let again = false
  return {
    async request() {
      if (stopped) return
      if (running) { again = true; return }
      running = true
      try {
        do {
          again = false
          try {
            const latest = await load()
            if (!stopped) onTable(latest)
          } catch (error) {
            if (!stopped) onError(error)
          }
        } while (again && !stopped)
      } finally {
        running = false
      }
    },
    stop() { stopped = true }
  }
}
