export function buyInState(rules, amount, balance = rules.totalChips) {
  const minimum = Number(rules.minBuyIn)
  const maximum = Math.min(Number(rules.maxBuyIn), Number(balance))
  const value = Number(amount)
  let message = ''
  if (maximum < minimum) message = `余额不足，至少需要 ${minimum} 筹码`
  else if (!Number.isInteger(value)) message = '请输入整数筹码'
  else if (value < minimum || value > maximum) message = `可带入 ${minimum}–${maximum} 筹码`
  return { minimum, maximum, message, valid: !message }
}

export function tableAvailability(table) {
  if (table.playerCount >= table.maxPlayers) return { available: false, label: '已满座' }
  if (!['WAITING', 'SHOWDOWN'].includes(table.phase)) return { available: false, label: '牌局进行中' }
  return { available: true, label: '入座' }
}
