<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import PokerRoom from './components/PokerRoom.vue'
import { api } from './services/api'
import { clearPokerAccount, normalizeLoginCodeInput, readPokerAccount, savePokerAccount } from './services/account'
import { clearPokerSession, readPokerSession, savePokerSession } from './services/session'
import { watchTable } from './services/socket'
import { createTableRefresh, shouldApplyTable, tableExitMessage } from './services/tableSync'
import { buyInState, tableAvailability } from './services/lobby'

const tables = ref([])
const tablesLoading = ref(false)
const tablesLoaded = ref(false)
const tablesError = ref('')
const settingsReady = ref(false)
const settingsLoading = ref(false)
const initializing = ref(true)
const seatPending = ref(false)
const table = ref(null)
const advice = ref(null)
const playerId = ref('')
const reconnectToken = ref('')
const nickname = ref(localStorage.getItem('poker.nickname') || '')
const accountSession = ref(readPokerAccount())
const accountProfile = ref(null)
const accountOpen = ref(false)
const accountMode = ref('CREATE')
const loginCode = ref('')
const customCode = ref('')
const codeCopied = ref(false)
const historyFilter = ref('ALL')
const tableName = ref('周末牌局')
const maxPlayers = ref(6)
const privateTable = ref(false)
const aiPlayers = ref(maxPlayers.value - 1)
const tableSettings = ref({ totalChips: 10000, minBuyIn: 1000, defaultBuyIn: 2000,
  maxBuyIn: 4000, smallBlind: 10, bigBlind: 20 })
const buyIn = ref(2000)
const availableBalance = computed(() => accountProfile.value?.chips ?? tableSettings.value.totalChips)
const createBuyIn = computed(() => buyInState(tableSettings.value, buyIn.value, availableBalance.value))
const entryBlocked = computed(() => busy.value || initializing.value || !settingsReady.value)
function joinBuyIn(item) {
  return buyInState(item, joinBuyIns.value[item.id], accountProfile.value?.chips ?? item.totalChips)
}
const joinBuyIns = ref({})
const pendingTasks = ref(0)
const busy = computed(() => pendingTasks.value > 0)
const tableSynced = ref(false)
const refreshing = ref(false)
const notice = ref('')
let leavingTableId = ''
const error = ref('')
const connected = ref(false)
const emoteEvent = ref(null)
const savedSession = ref(readPokerSession())
const adminOpen = ref(false)
const adminToken = ref(sessionStorage.getItem('poker.adminToken') || '')
const adminAuthenticated = ref(false)
const adminSettings = ref({ ...tableSettings.value })
const adminTables = ref([])
const adminAccounts = ref([])
const accountEdits = ref({})
const adminMessage = ref('')
const moneyPresets = [
  { name: '入门 1/2', totalChips: 1000, minBuyIn: 100, defaultBuyIn: 200, maxBuyIn: 400, smallBlind: 1, bigBlind: 2 },
  { name: '标准 10/20', totalChips: 10000, minBuyIn: 1000, defaultBuyIn: 2000, maxBuyIn: 4000, smallBlind: 10, bigBlind: 20 },
  { name: '深筹 25/50', totalChips: 50000, minBuyIn: 5000, defaultBuyIn: 10000, maxBuyIn: 20000, smallBlind: 25, bigBlind: 50 }
]
const settingsRatios = computed(() => {
  const bb = Number(adminSettings.value.bigBlind) || 1
  return {
    minimum: Math.round((Number(adminSettings.value.minBuyIn) || 0) / bb),
    defaultValue: Math.round((Number(adminSettings.value.defaultBuyIn) || 0) / bb),
    maximum: Math.round((Number(adminSettings.value.maxBuyIn) || 0) / bb),
    bankroll: Math.round((Number(adminSettings.value.totalChips) || 0) / bb)
  }
})
const activeHistory = computed(() => accountProfile.value?.recentHands?.filter(hand =>
  historyFilter.value === 'ALL' || hand.mode === historyFilter.value) || [])
const activeStats = computed(() => {
  if (!accountProfile.value) return null
  if (historyFilter.value === 'AI') return accountProfile.value.ai
  if (historyFilter.value === 'HUMAN') return accountProfile.value.human
  return accountProfile.value.overall
})
let stopSocket
let tableRefresh
let fallbackTimer
let adviceRequest = 0
let lobbyTimer
let lobbyRequest

watch(maxPlayers, value => { aiPlayers.value = value - 1 })
watch(privateTable, enabled => {
  if (enabled) aiPlayers.value = maxPlayers.value - 1
})

function loadTables() {
  if (lobbyRequest) return lobbyRequest
  tablesLoading.value = true
  lobbyRequest = (async () => {
    try {
      const latest = await api.listTables()
      tables.value = latest
      joinBuyIns.value = Object.fromEntries(latest.map(item => [item.id,
        joinBuyIns.value[item.id] ?? Math.min(item.defaultBuyIn, accountProfile.value?.chips ?? item.maxBuyIn)]))
      tablesLoaded.value = true
      tablesError.value = ''
    } catch (e) { tablesError.value = e.message }
    finally { tablesLoading.value = false; lobbyRequest = null }
  })()
  return lobbyRequest
}

async function loadSettings() {
  if (settingsLoading.value) return
  settingsLoading.value = true
  try {
    tableSettings.value = await api.settings()
    buyIn.value = tableSettings.value.defaultBuyIn
    settingsReady.value = true
  } catch (e) { error.value = e.message }
  finally { settingsLoading.value = false }
}

async function run(task, showError = true) {
  pendingTasks.value++
  error.value = ''
  try { return await task() } catch (e) { if (showError) error.value = e.message } finally { pendingTasks.value-- }
}

async function loadAccountProfile(silent = false) {
  if (!accountSession.value) return null
  try {
    const profile = await api.accountProfile(accountSession.value.accountId,
      accountSession.value.accountToken)
    accountProfile.value = profile
    nickname.value = profile.nickname
    localStorage.setItem('poker.nickname', profile.nickname)
    return profile
  } catch (e) {
    if ([400, 404].includes(e.status)) {
      clearPokerAccount()
      accountSession.value = null
      accountProfile.value = null
    }
    if (!silent) error.value = e.message
    return null
  }
}

async function ensureAccount() {
  if (accountSession.value) {
    const profile = accountProfile.value || await loadAccountProfile()
    return profile ? accountSession.value : null
  }
  const name = nickname.value.trim()
  if (!name) {
    error.value = '请先输入昵称'
    return null
  }
  const created = await run(() => api.createAccount(name))
  if (!created) return null
  accountSession.value = savePokerAccount(created)
  accountProfile.value = created.profile
  nickname.value = created.profile.nickname
  return accountSession.value
}

async function restoreAccountSeat(enterTable = false, silent = true) {
  if (!accountSession.value) return false
  let session
  try {
    session = await api.activeAccountSeat(accountSession.value.accountId,
      accountSession.value.accountToken)
  } catch (e) {
    if (enterTable) throw e
    if (!silent) error.value = e.message
    return false
  }
  if (!session?.table || !session?.playerId || !session?.reconnectToken) return false
  if (enterTable) {
    accountOpen.value = false
    remember(session)
  } else {
    savedSession.value = savePokerSession({
      tableId: session.table.id,
      playerId: session.playerId,
      reconnectToken: session.reconnectToken,
      tableName: session.table.name,
      nickname: accountProfile.value?.nickname || nickname.value,
      autoResume: false
    })
  }
  return true
}

async function createAccountFromPanel() {
  const account = await ensureAccount()
  if (account) accountOpen.value = true
}

async function loginAccountFromPanel() {
  if (!nickname.value.trim() || !loginCode.value.trim()) {
    error.value = '请输入昵称和跨设备登录码'
    return
  }
  const session = await run(() => api.loginAccount(nickname.value.trim(), loginCode.value.trim()))
  if (!session) return
  accountSession.value = savePokerAccount(session)
  accountProfile.value = session.profile
  nickname.value = session.profile.nickname
  loginCode.value = ''
  localStorage.setItem('poker.nickname', nickname.value)
  await run(() => restoreAccountSeat(true, false))
}

// requestedCode 为空时随机生成；传入则使用指定码（4 位数字或 12 位字母数字组合）
async function rotateLoginCode(requestedCode) {
  if (!accountSession.value) return
  const { code, valid } = normalizeLoginCodeInput(requestedCode)
  if (code && !valid) {
    error.value = '自定义登录码需要 4 位数字或 12 位字母数字组合'
    return
  }
  if (accountSession.value.loginCode
      && !window.confirm('更换登录码后，旧登录码将不能再用于新设备登录。继续吗？')) return
  const result = await run(() => api.rotateAccountLoginCode(accountSession.value.accountId,
    accountSession.value.accountToken, code || null))
  if (!result) return
  accountSession.value = savePokerAccount({ ...accountSession.value, loginCode: result.loginCode })
  customCode.value = ''
  codeCopied.value = false
}

async function copyLoginCode() {
  if (!accountSession.value?.loginCode) return
  try {
    await navigator.clipboard.writeText(accountSession.value.loginCode)
    codeCopied.value = true
    window.setTimeout(() => { codeCopied.value = false }, 1800)
  } catch (_) {
    error.value = '复制失败，请长按登录码手动复制'
  }
}

function switchAccount() {
  if (!window.confirm('退出当前设备上的账号并切换其他账号？服务器中的筹码和战绩不会删除。')) return
  clearPokerAccount()
  clearPokerSession()
  accountSession.value = null
  accountProfile.value = null
  savedSession.value = null
  nickname.value = ''
  loginCode.value = ''
  accountMode.value = 'LOGIN'
  localStorage.removeItem('poker.nickname')
}

function percent(value) {
  return `${Math.round((Number(value) || 0) * 100)}%`
}

function handResultLabel(result) {
  return result === 'WIN' ? '获胜' : result === 'TIE' ? '平局' : '失利'
}

function handModeLabel(mode) {
  return mode === 'AI' ? '人机' : '人人'
}

function shortDate(value) {
  return new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric',
    hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function remember(session) {
  playerId.value = session.playerId
  reconnectToken.value = session.reconnectToken
  table.value = session.table
  localStorage.setItem('poker.nickname', nickname.value)
  savedSession.value = savePokerSession({
    tableId: session.table.id,
    playerId: session.playerId,
    reconnectToken: session.reconnectToken,
    tableName: session.table.name,
    nickname: nickname.value,
    autoResume: true
  })
  connect()
  loadAdvice()
}

async function resumeSession(silent = false) {
  const session = savedSession.value
  if (!session) return
  pendingTasks.value++
  error.value = ''
  let restored
  try {
    restored = await api.reconnect(session.tableId, session.playerId, session.reconnectToken)
  } catch (e) {
    if ([400, 404].includes(e.status)) {
      clearPokerSession()
      savedSession.value = null
      if (!silent) error.value = '上次牌局已失效，请重新加入'
    } else if (!silent) error.value = e.message
    return
  } finally {
    pendingTasks.value--
  }
  playerId.value = session.playerId
  reconnectToken.value = restored.reconnectToken
  table.value = restored.table
  nickname.value = session.nickname || nickname.value
  savedSession.value = savePokerSession({ ...session, reconnectToken: restored.reconnectToken,
    tableName: restored.table.name, autoResume: true })
  connect()
  loadAdvice()
}

async function createTable() {
  if (entryBlocked.value || seatPending.value) return
  if (!nickname.value.trim() || !tableName.value.trim()) return
  if (!createBuyIn.value.valid) { error.value = createBuyIn.value.message; return }
  seatPending.value = true
  try { await run(async () => {
    const account = await ensureAccount()
    if (!account) return
    if (await restoreAccountSeat(true)) return
    const session = await api.createTable({
    tableName: tableName.value,
    nickname: nickname.value,
    accountId: account.accountId,
    accountToken: account.accountToken,
    maxPlayers: maxPlayers.value,
    privateTable: privateTable.value,
    aiPlayers: privateTable.value ? aiPlayers.value : 0,
    buyIn: Number(buyIn.value)
    })
    remember(session)
  }) } finally { seatPending.value = false }
}

async function join(item) {
  if (entryBlocked.value || seatPending.value) return
  if (!tableAvailability(item).available) return
  if (!nickname.value.trim()) { error.value = '请先输入昵称'; return }
  const amount = joinBuyIn(item)
  if (!amount.valid) { error.value = amount.message; return }
  seatPending.value = true
  try { await run(async () => {
    const account = await ensureAccount()
    if (!account) return
    if (await restoreAccountSeat(true)) return
    const session = await api.joinTable(item.id, nickname.value, Number(joinBuyIns.value[item.id]), account)
    remember(session)
  }) } finally { seatPending.value = false }
}

async function refresh() {
  return tableRefresh?.request()
}

function stopConnection() {
  tableRefresh?.stop()
  tableRefresh = null
  stopSocket?.()
  stopSocket = null
  window.clearInterval(fallbackTimer)
  connected.value = false
  tableSynced.value = false
  refreshing.value = false
}

function finishLeaving(message) {
  stopConnection()
  ++adviceRequest
  clearPokerSession()
  savedSession.value = null
  table.value = null
  advice.value = null
  playerId.value = ''
  reconnectToken.value = ''
  leavingTableId = ''
  error.value = ''
  notice.value = message
  loadTables()
  loadAccountProfile(true)
}

function handleTableError(e, background = false) {
  const exitMessage = tableExitMessage(e)
  if (exitMessage) finishLeaving(exitMessage)
  else {
    tableSynced.value = false
    if (!background) error.value = e.message
  }
}

function applyTable(latest) {
  if (!shouldApplyTable(table.value, latest)) return
  if (!latest.players.some(player => player.id === playerId.value)) {
    finishLeaving('已结算离桌，筹码与战绩已保存，可以加入其他牌桌')
    return
  }
  table.value = latest
  tableSynced.value = true
  loadAdvice()
  if (latest.phase === 'SHOWDOWN') loadAccountProfile(true)
}

async function updateTable(task) {
  const id = table.value?.id
  const viewer = playerId.value
  if (!id || busy.value) return
  pendingTasks.value++
  error.value = ''
  try {
    const latest = await task(id, viewer, reconnectToken.value)
    if (table.value?.id === id && playerId.value === viewer) applyTable(latest)
  } catch (e) {
    if (table.value?.id === id && playerId.value === viewer) {
      handleTableError(e)
      refresh()
    }
  } finally {
    pendingTasks.value--
  }
}

async function loadAdvice() {
  const requestId = ++adviceRequest
  if (!table.value?.privateTable || !playerId.value
      || ['WAITING', 'SHOWDOWN'].includes(table.value.phase)) {
    advice.value = null
    return
  }
  try {
    const latest = await api.advice(table.value.id, playerId.value, reconnectToken.value)
    if (requestId === adviceRequest) advice.value = latest
  } catch (_) {
    if (requestId === adviceRequest) advice.value = null
  }
}

function connect() {
  stopConnection()
  notice.value = ''
  const id = table.value.id
  const viewer = playerId.value
  const token = reconnectToken.value
  const current = () => table.value?.id === id && playerId.value === viewer
  tableRefresh = createTableRefresh({
    load: async () => {
      refreshing.value = true
      try { return await api.getTable(id, viewer, token) }
      finally { if (current()) refreshing.value = false }
    },
    onTable: applyTable,
    onError: e => handleTableError(e, true)
  })
  stopSocket = watchTable(id, refresh, value => {
    if (current()) {
      connected.value = value
      if (!value) tableSynced.value = false
    }
  }, event => {
    if (current()) emoteEvent.value = { ...event, receivedAt: Date.now() }
  }, () => {
    if (current()) finishLeaving(leavingTableId === id || table.value.players.find(player => player.id === viewer)?.leaving
      ? '已结算离桌，筹码与战绩已保存' : '牌桌已关闭，请选择其他牌桌')
  })
  fallbackTimer = window.setInterval(() => {
    if (!connected.value || !tableSynced.value) refresh()
  }, 4000)
  refresh()
}

async function start() {
  await updateTable((id, viewer, token) => api.start(id, viewer, token))
}

async function action(payload) {
  await updateTable((id, viewer, token) => api.act(id, viewer, token, payload.type, payload.raiseTo))
}

function leave() {
  if (!['WAITING', 'SHOWDOWN'].includes(table.value.phase)
      && !window.confirm('牌局仍在进行。暂时返回大厅后座位会保留，可通过“继续牌局”回来。确定暂离吗？')) return
  stopConnection()
  if (savedSession.value) {
    savedSession.value = savePokerSession({ ...savedSession.value, autoResume: false })
  }
  ++adviceRequest
  table.value = null; advice.value = null; playerId.value = ''; reconnectToken.value = ''; loadTables()
}

async function adjustChips(type, amount) {
  const method = type === 'TOP_UP' ? api.topUp : api.cashOut
  await updateTable((id, viewer, token) => method(id, viewer, token, Number(amount)))
  loadAccountProfile(true)
}

async function settleLeave() {
  const id = table.value?.id
  const viewer = playerId.value
  if (!id || busy.value) return
  pendingTasks.value++
  leavingTableId = id
  error.value = ''
  try {
    const result = await api.leave(id, viewer, reconnectToken.value)
    if (table.value?.id !== id || playerId.value !== viewer) return
    if (result.pending) {
      applyTable(result.table)
      notice.value = '已预约本手结束后离桌；你仍可继续行动，结算后会自动返回大厅'
    } else finishLeaving('已结算离桌，筹码已保存，可以加入其他牌桌')
  } catch (e) {
    if (table.value?.id === id && playerId.value === viewer) handleTableError(e)
  } finally {
    if (leavingTableId === id) leavingTableId = ''
    pendingTasks.value--
  }
}

async function sendEmote(emoteId) {
  try {
    await api.emote(table.value.id, playerId.value, reconnectToken.value, emoteId)
  } catch (e) {
    error.value = e.message
  }
}

async function initialize() {
  try {
    await Promise.all([loadSettings(), loadTables(), loadAccountProfile(true)])
    if (savedSession.value?.autoResume) await resumeSession(true)
    if (!table.value && !savedSession.value && accountSession.value)
      await restoreAccountSeat(false)
  } finally { initializing.value = false }
}

async function openAdmin() {
  adminOpen.value = true
  if (adminToken.value) await authenticateAdmin()
}

async function authenticateAdmin() {
  if (!adminToken.value.trim()) { error.value = '请输入管理员口令'; return }
  const result = await run(() => Promise.all([
    api.adminSettings(adminToken.value),
    api.adminTables(adminToken.value),
    // 兼容尚未提供账号管理接口的后端：拉取失败时降级为空列表
    api.adminAccounts(adminToken.value).catch(() => [])
  ]))
  if (!result) return
  adminSettings.value = result[0]
  adminTables.value = result[1]
  applyAccountList(result[2] || [])
  adminAuthenticated.value = true
  sessionStorage.setItem('poker.adminToken', adminToken.value)
}

function applyAccountList(list) {
  adminAccounts.value = list
  accountEdits.value = Object.fromEntries(list.map(item => [item.id,
    { nickname: item.nickname, chips: item.chips, loginCode: '' }]))
}

function formatAdminDate(value) {
  if (!value) return '从未登录'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '从未登录' : date.toLocaleString('zh-CN', { hour12: false })
}

async function saveAdminAccount(item) {
  const draft = accountEdits.value[item.id]
  if (!draft) return
  const nickname = String(draft.nickname ?? '').trim()
  const { code: loginCode, valid } = normalizeLoginCodeInput(draft.loginCode)
  if (!nickname) { adminMessage.value = '昵称不能为空'; return }
  if (loginCode && !valid) {
    adminMessage.value = '登录码需要 4 位数字或 12 位字母数字组合'
    return
  }
  const chips = Number(draft.chips)
  const updated = await run(() => api.updateAdminAccount(adminToken.value, item.id, {
    nickname,
    chips: Number.isFinite(chips) ? chips : undefined,
    loginCode: loginCode || undefined
  }))
  if (!updated) return
  const list = await run(() => api.adminAccounts(adminToken.value))
  if (list) applyAccountList(list)
  adminMessage.value = updated.loginCode
    ? `已保存 ${nickname}，新登录码 ${updated.loginCode}（仅此一次显示）`
    : `已保存 ${nickname}`
}

async function removeAdminAccount(item) {
  if (!window.confirm(`确定删除账号“${item.nickname}”吗？该账号的筹码与战绩将一并删除，无法恢复。`)) return
  const list = await run(async () => {
    await api.deleteAdminAccount(adminToken.value, item.id)
    return api.adminAccounts(adminToken.value)
  })
  if (list) applyAccountList(list)
  adminMessage.value = `已删除账号 ${item.nickname}`
}

async function saveAdminSettings() {
  const payload = Object.fromEntries(Object.entries(adminSettings.value)
    .map(([key, value]) => [key, Number(value)]))
  const latest = await run(() => api.updateAdminSettings(adminToken.value, payload))
  if (latest) {
    adminSettings.value = latest
    tableSettings.value = latest
    buyIn.value = latest.defaultBuyIn
  }
}

function applyMoneyPreset(preset) {
  adminSettings.value = { ...preset }
  delete adminSettings.value.name
}

function recommendFromBlinds() {
  const bigBlind = Math.max(2, Number(adminSettings.value.bigBlind) || 20)
  const minBuyIn = Math.min(10_000_000, bigBlind * 50)
  const defaultBuyIn = Math.min(10_000_000, Math.max(minBuyIn, bigBlind * 100))
  const maxBuyIn = Math.min(10_000_000, Math.max(defaultBuyIn, bigBlind * 200))
  adminSettings.value = {
    totalChips: Math.min(10_000_000, Math.max(maxBuyIn, bigBlind * 500)),
    minBuyIn,
    defaultBuyIn,
    maxBuyIn,
    smallBlind: Math.max(1, Math.floor(bigBlind / 2)),
    bigBlind
  }
}

async function deleteAdminTable(item) {
  if (!window.confirm(`确定删除牌桌“${item.name}”吗？在线玩家会立即退出。`)) return
  const latest = await run(async () => {
    await api.deleteAdminTable(adminToken.value, item.id)
    return api.adminTables(adminToken.value)
  })
  if (latest) adminTables.value = latest
}

function closeAdmin() {
  adminOpen.value = false
}

function refreshWhenVisible() {
  if (document.visibilityState !== 'visible') return
  if (table.value) refresh()
  else {
    loadTables()
    if (!settingsReady.value) loadSettings()
  }
}
onMounted(() => {
  initialize()
  lobbyTimer = window.setInterval(() => {
    if (!table.value && document.visibilityState === 'visible') loadTables()
  }, 8000)
  window.addEventListener('online', refreshWhenVisible)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})
onBeforeUnmount(() => {
  stopConnection()
  window.clearInterval(lobbyTimer)
  window.removeEventListener('online', refreshWhenVisible)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>

<template>
  <PokerRoom v-if="table" :table="table" :player-id="playerId" :advice="advice"
    :busy="busy" :connected="connected && tableSynced" :refreshing="refreshing" :emote-event="emoteEvent"
    @action="action" @chips="adjustChips" @start="start" @emote="sendEmote" @leave="leave" @settle-leave="settleLeave" @refresh="refresh" />
  <main v-else class="lobby-shell">
    <nav class="brand">
      <div class="brand-lockup">
        <span class="brand-mark"><i>R</i></span>
        <span class="brand-wordmark"><strong>RIVER ROOM</strong><small>Texas Hold'em Club</small></span>
      </div>
      <div class="brand-actions">
        <span class="brand-live"><i></i>实时牌局</span>
        <a class="lobby-link" href="#open-tables">公开牌桌</a>
        <button class="account-link" type="button" @click="accountOpen = true">
          <span>{{ accountProfile?.nickname || '我的账号' }}</span>
          <small v-if="accountProfile">{{ accountProfile.chips }} 筹码</small>
        </button>
        <button class="admin-link" type="button" @click="openAdmin">管理</button>
      </div>
    </nav>
    <article v-if="savedSession" class="resume-table resume-banner">
      <div><p class="eyebrow">YOUR SEAT IS SAVED</p><strong>{{ savedSession.tableName || '上次牌局' }}</strong><small>{{ savedSession.nickname || nickname }} · 座位与筹码已保留</small></div>
      <button class="gold" :disabled="busy" @click="resumeSession()">继续牌局 →</button>
    </article>
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow">PRIVATE TABLES · REAL-TIME HOLDEM</p>
        <h1>今晚，<br /><em>河牌见。</em></h1>
        <p>为认真牌局打造的实时德州扑克空间。无密码账号自动保存，朋友同桌或随时挑战 AI，筹码与战绩长期保留。</p>
        <div class="hero-proof" aria-label="产品能力">
          <span><strong>2–6</strong><small>灵活桌型</small></span>
          <span><strong>Live</strong><small>实时同步</small></span>
          <span><strong>NLH</strong><small>全押与边池</small></span>
        </div>
      </div>
      <form class="create-card" @submit.prevent="createTable">
        <header class="create-card-head">
          <div><p class="form-index">QUICK SEAT</p><strong>创建你的牌桌</strong><small>约 10 秒即可入座</small></div>
          <span class="stakes-badge"><small>默认盲注</small>{{ tableSettings.smallBlind }}/{{ tableSettings.bigBlind }}</span>
        </header>
        <label class="create-nickname"><span>玩家昵称<small v-if="accountProfile">已绑定长期账号</small></span><input v-model="nickname" maxlength="16" placeholder="例如：RiverKing" :disabled="!!accountProfile" required /></label>
        <div class="create-mode" aria-label="牌桌模式">
          <button type="button" :aria-pressed="!privateTable" :class="{ active: !privateTable }" @click="privateTable = false">
            <span class="mode-icon">♣</span><span><strong>朋友牌桌</strong><small>创建后邀请朋友加入</small></span>
          </button>
          <button type="button" :aria-pressed="privateTable" :class="{ active: privateTable }" @click="privateTable = true">
            <span class="mode-icon">♦</span><span><strong>AI 私人桌</strong><small>立即和 AI 开始对局</small></span>
          </button>
        </div>
        <div class="create-essentials">
          <label>人数<select v-model.number="maxPlayers"><option v-for="n in [2,3,4,5,6]" :key="n" :value="n">{{ n }} 人桌</option></select></label>
          <label>带入筹码<input v-model.number="buyIn" type="number" :min="createBuyIn.minimum" :max="createBuyIn.maximum" step="1" :aria-invalid="!createBuyIn.valid" aria-describedby="create-buy-in-help" required /></label>
        </div>
        <p id="create-buy-in-help" class="buy-in-help" :class="{ invalid: !createBuyIn.valid }">{{ createBuyIn.message || `可带入 ${createBuyIn.minimum}–${createBuyIn.maximum} · 可用筹码 ${availableBalance}` }}</p>
        <div v-if="!settingsReady && !settingsLoading && !initializing" class="lobby-error" role="alert">金额规则加载失败，重试后即可入座。<button type="button" @click="loadSettings">重试</button></div>
        <details class="create-advanced">
          <summary><span>更多设置</span><small>牌桌名称、AI 数量与金额说明</small></summary>
          <div class="create-advanced-body">
            <label>牌桌名称<input v-model="tableName" maxlength="30" required /></label>
            <label v-if="privateTable">AI 选手数量<select v-model.number="aiPlayers"><option v-for="n in maxPlayers - 1" :key="n" :value="n">{{ n }} 位 AI</option></select></label>
            <p>允许带入 {{ tableSettings.minBuyIn }}–{{ tableSettings.maxBuyIn }}，当前可用 {{ availableBalance }} 筹码。</p>
          </div>
        </details>
        <button class="gold wide create-submit" :disabled="entryBlocked || !createBuyIn.valid"><span>{{ seatPending ? '正在入座…' : initializing || settingsLoading ? '正在准备…' : '创建并入座' }}</span><b aria-hidden="true">→</b></button>
      </form>
    </section>

    <section id="open-tables" class="tables-section">
      <div class="section-title"><div><p class="eyebrow">OPEN TABLES</p><h2>正在开放的牌桌</h2><small>{{ tablesError ? '暂时无法同步' : tablesLoaded ? `${tables.length} 张牌桌 · 每 8 秒自动更新` : '正在加载牌桌' }}</small></div><button class="ghost-button" data-testid="refresh-lobby" :disabled="tablesLoading" @click="loadTables"><span aria-hidden="true">↻</span> {{ tablesLoading ? '刷新中…' : tablesError ? '重试' : '刷新' }}</button></div>
      <div v-if="tablesError" class="lobby-error" role="alert"><strong>牌桌列表更新失败</strong><span>{{ tablesError }}{{ tables.length ? '；下方保留上次结果，入座前请重试。' : '，请点击重试。' }}</span></div>
      <div v-if="tablesLoading && !tablesLoaded && !tablesError" class="lobby-loading" role="status">正在加载牌桌…</div>
      <div v-if="tables.length" class="table-list">
        <article v-for="item in tables" :key="item.id" class="table-row">
          <div class="table-identity"><span class="table-monogram">R</span><span><strong>{{ item.name }}</strong><small><i class="phase-dot" :class="{ waiting: item.phase === 'WAITING' || item.phase === 'SHOWDOWN' }"></i>{{ item.phaseLabel }}</small></span></div>
          <span class="table-stakes"><strong>{{ item.smallBlind }}/{{ item.bigBlind }} · {{ item.playerCount }}/{{ item.maxPlayers }} 人</strong><small>总额度 {{ item.totalChips }}</small></span>
          <label class="row-buy-in">带入<input v-model.number="joinBuyIns[item.id]" type="number" :min="joinBuyIn(item).minimum" :max="joinBuyIn(item).maximum" step="1" :aria-invalid="!joinBuyIn(item).valid" :aria-describedby="`buy-in-${item.id}`" :disabled="!tableAvailability(item).available" /><small :id="`buy-in-${item.id}`" :class="{ invalid: !joinBuyIn(item).valid }">{{ joinBuyIn(item).message || `范围 ${joinBuyIn(item).minimum}–${joinBuyIn(item).maximum}` }}</small></label>
          <button :disabled="entryBlocked || !!tablesError || !joinBuyIn(item).valid || !tableAvailability(item).available" @click="join(item)">{{ tableAvailability(item).label }} <span v-if="tableAvailability(item).available" aria-hidden="true">→</span></button>
        </article>
      </div>
      <div v-else-if="tablesLoaded && !tablesError && !tablesLoading" class="empty-lobby"><span>♠</span><strong>今晚的第一张牌桌，等你开局</strong><small>完成上方设置后即可立即入座</small></div>
    </section>
  </main>
  <div v-if="adminOpen" class="admin-overlay" @click.self="closeAdmin">
    <section class="admin-panel">
      <header><div><p class="eyebrow">ADMIN CONSOLE</p><h2>牌桌管理</h2></div><button class="panel-close" type="button" @click="closeAdmin">×</button></header>
      <div v-if="!adminAuthenticated" class="admin-login">
        <p>输入服务器管理员口令后，可配置总筹码、买入范围、盲注并删除历史牌桌。</p>
        <label>管理员口令<input v-model="adminToken" type="password" autocomplete="current-password" @keyup.enter="authenticateAdmin" /></label>
        <button class="gold wide" type="button" :disabled="busy" @click="authenticateAdmin">进入管理面板</button>
      </div>
      <template v-else>
        <form class="admin-money-settings" @submit.prevent="saveAdminSettings">
          <div class="money-settings-head"><div><strong>新牌桌金额规则</strong><small>只影响之后创建的牌桌，现有牌桌保持原规则</small></div><button class="gold" :disabled="busy">保存设置</button></div>
          <div class="money-presets">
            <button v-for="preset in moneyPresets" :key="preset.name" type="button" @click="applyMoneyPreset(preset)">{{ preset.name }}</button>
            <button type="button" @click="recommendFromBlinds">按大盲智能配套</button>
          </div>
          <div class="money-settings-grid">
            <label>小盲<input v-model.number="adminSettings.smallBlind" type="number" min="1" max="99999" step="1" /></label>
            <label>大盲<input v-model.number="adminSettings.bigBlind" type="number" min="2" max="100000" step="1" /></label>
            <label>最低带入<input v-model.number="adminSettings.minBuyIn" type="number" min="1" max="10000000" :step="adminSettings.bigBlind || 1" /></label>
            <label>默认带入<input v-model.number="adminSettings.defaultBuyIn" type="number" min="1" max="10000000" :step="adminSettings.bigBlind || 1" /></label>
            <label>最高带入<input v-model.number="adminSettings.maxBuyIn" type="number" min="1" max="10000000" :step="adminSettings.bigBlind || 1" /></label>
            <label>单次总筹码<input v-model.number="adminSettings.totalChips" type="number" min="100" max="10000000" :step="adminSettings.bigBlind || 1" /></label>
          </div>
          <div class="money-guide">
            <strong>当前相当于：最低 {{ settingsRatios.minimum }}BB · 默认 {{ settingsRatios.defaultValue }}BB · 最高 {{ settingsRatios.maximum }}BB · 总额度 {{ settingsRatios.bankroll }}BB</strong>
            <small>推荐：小盲约为大盲一半；最低 50BB、默认 100BB、最高 200BB；单次总额度至少准备 5 个默认买入。系统最低允许 20BB。</small>
          </div>
        </form>
        <div class="admin-table-title"><strong>全部牌桌</strong><span>{{ adminTables.length }} 张</span></div>
        <div v-if="adminTables.length" class="admin-table-list">
          <article v-for="item in adminTables" :key="item.id">
            <div><strong>{{ item.name }}</strong><small><span>{{ item.privateTable ? '私人桌' : '公开桌' }}</span> · {{ item.phaseLabel }} · {{ item.playerCount }}/{{ item.maxPlayers }} 人<span v-if="item.aiCount"> · {{ item.aiCount }} AI</span></small></div>
            <button class="delete-table" type="button" :disabled="busy" @click="deleteAdminTable(item)">删除</button>
          </article>
        </div>
        <p v-else class="admin-empty">当前没有牌桌</p>
        <div class="admin-table-title"><strong>账号管理</strong><span>{{ adminAccounts.length }} 个</span></div>
        <p v-if="adminMessage" class="admin-notice">{{ adminMessage }}</p>
        <div v-if="adminAccounts.length" class="admin-account-list">
          <article v-for="item in adminAccounts" :key="item.id">
            <div class="admin-account-head">
              <strong>{{ item.nickname }}</strong>
              <small>{{ item.chips }} 筹码 · {{ item.hands }} 手 · 最近 {{ formatAdminDate(item.lastSeenAt) }}</small>
            </div>
            <div v-if="accountEdits[item.id]" class="admin-account-edit">
              <input v-model="accountEdits[item.id].nickname" maxlength="16" placeholder="昵称" />
              <input v-model.number="accountEdits[item.id].chips" type="number" min="0" max="10000000" placeholder="筹码" />
              <input v-model="accountEdits[item.id].loginCode" maxlength="14" placeholder="新登录码（4 位数字或 12 位，留空不变）" />
              <button type="button" :disabled="busy" @click="saveAdminAccount(item)">保存</button>
              <button class="delete-table" type="button" :disabled="busy" @click="removeAdminAccount(item)">删除</button>
            </div>
          </article>
        </div>
        <p v-else class="admin-empty">当前没有账号</p>
      </template>
    </section>
  </div>
  <div v-if="accountOpen" class="admin-overlay account-overlay" @click.self="accountOpen = false">
    <section class="admin-panel account-panel">
      <header>
        <div><p class="eyebrow">PLAYER PROFILE</p><h2>我的账号与战绩</h2></div>
        <button class="panel-close" type="button" @click="accountOpen = false">×</button>
      </header>
      <div v-if="!accountProfile" class="account-create">
        <span class="account-suit">♠</span>
        <strong>{{ accountMode === 'CREATE' ? '创建长期账号' : '登录已有账号' }}</strong>
        <p>{{ accountMode === 'CREATE' ? '创建后会获得跨设备登录码，筹码余额和每手战绩保存在服务器。' : '输入同一昵称和登录码，即可在手机、电脑间共享筹码与战绩。' }}</p>
        <div class="account-mode-tabs">
          <button type="button" :class="{ active: accountMode === 'CREATE' }" @click="accountMode = 'CREATE'">创建账号</button>
          <button type="button" :class="{ active: accountMode === 'LOGIN' }" @click="accountMode = 'LOGIN'">已有账号登录</button>
        </div>
        <label>玩家昵称<input v-model="nickname" maxlength="16" placeholder="输入账号昵称" /></label>
        <label v-if="accountMode === 'LOGIN'">跨设备登录码<input v-model="loginCode" autocomplete="one-time-code" placeholder="输入跨设备登录码" @keyup.enter="loginAccountFromPanel" /></label>
        <button v-if="accountMode === 'CREATE'" class="gold wide" type="button" :disabled="busy" @click="createAccountFromPanel">创建账号</button>
        <button v-else class="gold wide" type="button" :disabled="busy" @click="loginAccountFromPanel">登录并保存到本机</button>
        <small>登录码相当于账号恢复凭证，请勿发给其他人。各设备登录后都会自动保持登录。</small>
      </div>
      <template v-else>
        <div class="account-summary">
          <div><span class="account-avatar">{{ accountProfile.nickname.slice(0, 1).toUpperCase() }}</span><span><strong>{{ accountProfile.nickname }}</strong><small>无密码账号 · 本机身份已保存</small></span></div>
          <span class="account-bankroll"><small>长期筹码</small><strong>{{ accountProfile.chips }}</strong><button type="button" @click="switchAccount">切换账号</button></span>
        </div>
        <section class="account-login-code">
          <div><strong>跨设备登录</strong><small>在手机或其他电脑选择“已有账号登录”，输入昵称和此登录码。</small></div>
          <template v-if="accountSession?.loginCode">
            <code>{{ accountSession.loginCode }}</code>
            <button type="button" @click="copyLoginCode">{{ codeCopied ? '已复制' : '复制' }}</button>
            <button class="rotate-code" type="button" :disabled="busy" @click="rotateLoginCode(null)">随机更换</button>
          </template>
          <button v-else class="generate-code" type="button" :disabled="busy" @click="rotateLoginCode(null)">生成跨设备登录码</button>
        </section>
        <div class="account-custom-code">
          <input v-model="customCode" maxlength="14" placeholder="自定义登录码（4 位数字或 12 位字母数字）" @keyup.enter="rotateLoginCode(customCode)" />
          <button type="button" :disabled="busy" @click="rotateLoginCode(customCode)">使用指定码</button>
        </div>
        <div class="history-tabs" role="tablist" aria-label="战绩类型">
          <button v-for="item in [{ key: 'ALL', label: '全部' }, { key: 'AI', label: '人机' }, { key: 'HUMAN', label: '人人' }]"
            :key="item.key" type="button" :class="{ active: historyFilter === item.key }" @click="historyFilter = item.key">{{ item.label }}</button>
          <button class="history-refresh" type="button" :disabled="busy" @click="loadAccountProfile()">刷新</button>
        </div>
        <div v-if="activeStats" class="account-stats">
          <span><small>总手数</small><strong>{{ activeStats.hands }}</strong></span>
          <span><small>胜率</small><strong>{{ percent(activeStats.winRate) }}</strong></span>
          <span><small>胜 / 平 / 负</small><strong>{{ activeStats.wins }} / {{ activeStats.ties }} / {{ activeStats.losses }}</strong></span>
          <span><small>净筹码</small><strong :class="{ positive: activeStats.netChips > 0, negative: activeStats.netChips < 0 }">{{ activeStats.netChips > 0 ? '+' : '' }}{{ activeStats.netChips }}</strong></span>
        </div>
        <div class="history-heading"><strong>最近牌局</strong><small>按单手结算记录，平局不计入胜场</small></div>
        <div v-if="activeHistory.length" class="history-list">
          <article v-for="hand in activeHistory" :key="hand.id">
            <span class="history-result" :class="hand.result.toLowerCase()">{{ handResultLabel(hand.result) }}</span>
            <span class="history-table"><strong>{{ hand.tableName }}</strong><small>{{ handModeLabel(hand.mode) }} · 第 {{ hand.handNumber }} 局 · {{ shortDate(hand.playedAt) }}</small></span>
            <span class="history-net" :class="{ positive: hand.netChips > 0, negative: hand.netChips < 0 }"><strong>{{ hand.netChips > 0 ? '+' : '' }}{{ hand.netChips }}</strong><small>余额 {{ hand.endingChips }}</small></span>
          </article>
        </div>
        <p v-else class="history-empty">暂无{{ historyFilter === 'AI' ? '人机' : historyFilter === 'HUMAN' ? '人人' : '' }}战绩，完成一手牌后会自动记录。</p>
      </template>
    </section>
  </div>
  <div v-if="error || notice" class="toast" :class="{ 'toast-info': !error }" :role="error ? 'alert' : 'status'">
    <span>{{ error || notice }}</span>
    <button type="button" aria-label="关闭提示" @click="error = ''; notice = ''">×</button>
  </div>
</template>
