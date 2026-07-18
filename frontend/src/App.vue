<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import PokerRoom from './components/PokerRoom.vue'
import { api } from './services/api'
import { clearPokerSession, readPokerSession, savePokerSession } from './services/session'
import { watchTable } from './services/socket'

const tables = ref([])
const table = ref(null)
const playerId = ref('')
const reconnectToken = ref('')
const nickname = ref(localStorage.getItem('poker.nickname') || '')
const tableName = ref('周末牌局')
const maxPlayers = ref(6)
const privateTable = ref(false)
const aiPlayers = ref(1)
const busy = ref(false)
const error = ref('')
const connected = ref(false)
const savedSession = ref(readPokerSession())
const adminOpen = ref(false)
const adminToken = ref(sessionStorage.getItem('poker.adminToken') || '')
const adminAuthenticated = ref(false)
const adminSettings = ref({ startingChips: 2000 })
const adminTables = ref([])
let stopSocket

watch(maxPlayers, value => { aiPlayers.value = Math.min(aiPlayers.value, value - 1) })

async function loadTables() {
  try { tables.value = await api.listTables() } catch (e) { error.value = e.message }
}

async function run(task, showError = true) {
  busy.value = true
  error.value = ''
  try { return await task() } catch (e) { if (showError) error.value = e.message } finally { busy.value = false }
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
}

async function resumeSession(silent = false) {
  const session = savedSession.value
  if (!session) return
  busy.value = true
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
    busy.value = false
  }
  playerId.value = session.playerId
  reconnectToken.value = restored.reconnectToken
  table.value = restored.table
  nickname.value = session.nickname || nickname.value
  savedSession.value = savePokerSession({ ...session, reconnectToken: restored.reconnectToken,
    tableName: restored.table.name, autoResume: true })
  connect()
}

async function createTable() {
  if (!nickname.value.trim() || !tableName.value.trim()) return
  const session = await run(() => api.createTable({
    tableName: tableName.value,
    nickname: nickname.value,
    maxPlayers: maxPlayers.value,
    privateTable: privateTable.value,
    aiPlayers: privateTable.value ? aiPlayers.value : 0
  }))
  if (session) remember(session)
}

async function join(item) {
  if (!nickname.value.trim()) { error.value = '请先输入昵称'; return }
  const session = await run(() => api.joinTable(item.id, nickname.value))
  if (session) remember(session)
}

async function refresh() {
  if (!table.value || !playerId.value) return
  const latest = await run(() => api.getTable(table.value.id, playerId.value, reconnectToken.value))
  if (latest) table.value = latest
}

function connect() {
  stopSocket?.()
  stopSocket = watchTable(table.value.id, refresh, value => { connected.value = value })
}

async function start() {
  const latest = await run(() => api.start(table.value.id, playerId.value, reconnectToken.value))
  if (latest) table.value = latest
}

async function action(payload) {
  const latest = await run(() => api.act(table.value.id, playerId.value, reconnectToken.value,
    payload.type, payload.raiseTo))
  if (latest) table.value = latest
}

function leave() {
  if (!['WAITING', 'SHOWDOWN'].includes(table.value.phase)
      && !window.confirm('牌局仍在进行。暂时返回大厅后座位会保留，可通过“继续牌局”回来。确定暂离吗？')) return
  stopSocket?.(); stopSocket = null; connected.value = false
  if (savedSession.value) {
    savedSession.value = savePokerSession({ ...savedSession.value, autoResume: false })
  }
  table.value = null; playerId.value = ''; reconnectToken.value = ''; loadTables()
}

async function initialize() {
  await loadTables()
  if (savedSession.value?.autoResume) await resumeSession(true)
}

async function openAdmin() {
  adminOpen.value = true
  if (adminToken.value) await authenticateAdmin()
}

async function authenticateAdmin() {
  if (!adminToken.value.trim()) { error.value = '请输入管理员口令'; return }
  const result = await run(() => Promise.all([
    api.adminSettings(adminToken.value),
    api.adminTables(adminToken.value)
  ]))
  if (!result) return
  adminSettings.value = result[0]
  adminTables.value = result[1]
  adminAuthenticated.value = true
  sessionStorage.setItem('poker.adminToken', adminToken.value)
}

async function saveAdminSettings() {
  const latest = await run(() => api.updateAdminSettings(adminToken.value,
    Number(adminSettings.value.startingChips)))
  if (latest) adminSettings.value = latest
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

onMounted(initialize)
onBeforeUnmount(() => stopSocket?.())
</script>

<template>
  <PokerRoom v-if="table" :table="table" :player-id="playerId" :busy="busy" :connected="connected" @action="action" @start="start" @leave="leave" />
  <main v-else class="lobby-shell">
    <nav class="brand">
      <span class="brand-mark">R</span><strong>RIVER ROOM</strong>
      <div class="brand-actions"><small>实时德州扑克</small><button class="admin-link" type="button" @click="openAdmin">管理员</button></div>
    </nav>
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow">PRIVATE TABLES · REAL-TIME PLAY</p>
        <h1>今晚，<br /><em>河牌见。</em></h1>
        <p>创建一张私人牌桌，邀请朋友加入。无需注册，输入昵称即可开局。</p>
      </div>
      <form class="create-card" @submit.prevent="createTable">
        <p class="form-index">01 / 创建牌桌</p>
        <label>你的昵称<input v-model="nickname" maxlength="16" placeholder="例如：RiverKing" required /></label>
        <label>牌桌名称<input v-model="tableName" maxlength="30" required /></label>
        <label>人数上限<select v-model.number="maxPlayers"><option v-for="n in [2,3,4,5,6]" :key="n" :value="n">{{ n }} 人桌</option></select></label>
        <label class="private-toggle"><input v-model="privateTable" type="checkbox" /><span><strong>私人 AI 牌桌</strong><small>不会显示在公开大厅，仅供你与 AI 对局</small></span></label>
        <label v-if="privateTable">AI 选手数量<select v-model.number="aiPlayers"><option v-for="n in maxPlayers - 1" :key="n" :value="n">{{ n }} 位 AI</option></select></label>
        <button class="gold wide" :disabled="busy">{{ busy ? '正在创建…' : '创建并入座 →' }}</button>
      </form>
    </section>

    <section class="tables-section">
      <div class="section-title"><div><p class="eyebrow">OPEN TABLES</p><h2>公开牌桌</h2></div><button class="ghost-button" @click="loadTables">刷新</button></div>
      <article v-if="savedSession" class="resume-table">
        <div><p class="eyebrow">YOUR SEAT IS SAVED</p><strong>{{ savedSession.tableName || '上次牌局' }}</strong><small>{{ savedSession.nickname || nickname }}</small></div>
        <button class="gold" :disabled="busy" @click="resumeSession()">继续牌局 →</button>
      </article>
      <div v-if="tables.length" class="table-list">
        <article v-for="item in tables" :key="item.id" class="table-row">
          <div><span class="phase-dot" :class="{ waiting: item.phase === 'WAITING' || item.phase === 'SHOWDOWN' }"></span><strong>{{ item.name }}</strong><small>{{ item.phaseLabel }}</small></div>
          <span>{{ item.playerCount }} / {{ item.maxPlayers }} 人</span>
          <button :disabled="busy || item.playerCount >= item.maxPlayers || !['WAITING','SHOWDOWN'].includes(item.phase)" @click="join(item)">加入牌桌</button>
        </article>
      </div>
      <div v-else class="empty-lobby">还没有牌桌。成为今晚第一位庄家。</div>
    </section>
    <div v-if="error" class="toast" @click="error = ''">{{ error }} ×</div>
  </main>
  <div v-if="adminOpen" class="admin-overlay" @click.self="closeAdmin">
    <section class="admin-panel">
      <header><div><p class="eyebrow">ADMIN CONSOLE</p><h2>牌桌管理</h2></div><button class="panel-close" type="button" @click="closeAdmin">×</button></header>
      <div v-if="!adminAuthenticated" class="admin-login">
        <p>输入服务器管理员口令后，可修改新牌桌筹码并删除历史牌桌。</p>
        <label>管理员口令<input v-model="adminToken" type="password" autocomplete="current-password" @keyup.enter="authenticateAdmin" /></label>
        <button class="gold wide" type="button" :disabled="busy" @click="authenticateAdmin">进入管理面板</button>
      </div>
      <template v-else>
        <form class="admin-settings" @submit.prevent="saveAdminSettings">
          <div><strong>新牌桌初始筹码</strong><small>只影响之后创建的牌桌，现有牌桌不变</small></div>
          <input v-model.number="adminSettings.startingChips" type="number" min="100" max="1000000" step="100" />
          <button class="gold" :disabled="busy">保存</button>
        </form>
        <div class="admin-table-title"><strong>全部牌桌</strong><span>{{ adminTables.length }} 张</span></div>
        <div v-if="adminTables.length" class="admin-table-list">
          <article v-for="item in adminTables" :key="item.id">
            <div><strong>{{ item.name }}</strong><small><span>{{ item.privateTable ? '私人桌' : '公开桌' }}</span> · {{ item.phaseLabel }} · {{ item.playerCount }}/{{ item.maxPlayers }} 人<span v-if="item.aiCount"> · {{ item.aiCount }} AI</span></small></div>
            <button class="delete-table" type="button" :disabled="busy" @click="deleteAdminTable(item)">删除</button>
          </article>
        </div>
        <p v-else class="admin-empty">当前没有牌桌</p>
      </template>
    </section>
  </div>
  <div v-if="table && error" class="toast" @click="error = ''">{{ error }} ×</div>
</template>

