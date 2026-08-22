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
