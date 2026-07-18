<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
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
const busy = ref(false)
const error = ref('')
const connected = ref(false)
const savedSession = ref(readPokerSession())
let stopSocket

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
  const session = await run(() => api.createTable({ tableName: tableName.value, nickname: nickname.value, maxPlayers: maxPlayers.value }))
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

onMounted(initialize)
onBeforeUnmount(() => stopSocket?.())
</script>

<template>
  <PokerRoom v-if="table" :table="table" :player-id="playerId" :busy="busy" :connected="connected" @action="action" @start="start" @leave="leave" />
  <main v-else class="lobby-shell">
    <nav class="brand"><span class="brand-mark">R</span><strong>RIVER ROOM</strong><small>实时德州扑克</small></nav>
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
  <div v-if="table && error" class="toast" @click="error = ''">{{ error }} ×</div>
</template>

