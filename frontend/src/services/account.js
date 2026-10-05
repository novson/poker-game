const ACCOUNT_KEY = 'poker.account'

export function readPokerAccount(storage = localStorage) {
  try {
    const account = JSON.parse(storage.getItem(ACCOUNT_KEY) || 'null')
    if (!account?.accountId || !account?.accountToken) return null
    return account
  } catch (_) {
    return null
  }
}

export function savePokerAccount(account, storage = localStorage) {
  const saved = {
    accountId: account.accountId,
    accountToken: account.accountToken,
    loginCode: account.loginCode || '',
    nickname: account.profile?.nickname || account.nickname || ''
  }
  storage.setItem(ACCOUNT_KEY, JSON.stringify(saved))
  return saved
}

export function clearPokerAccount(storage = localStorage) {
  storage.removeItem(ACCOUNT_KEY)
}

/**
 * 归一化登录码输入：去掉横杠与空格并转大写。
 * 合法形态为 4 位纯数字（如 1234）或 12 位字母数字组合（如 ABCD-EFGH-IJKL）。
 */
export function normalizeLoginCodeInput(raw) {
  const code = String(raw ?? '').replace(/[-\s]/g, '').toUpperCase()
  return { code, valid: /^\d{4}$/.test(code) || /^[A-Z0-9]{12}$/.test(code) }
}
