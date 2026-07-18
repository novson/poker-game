<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import PlayingCard from './PlayingCard.vue'
import { canTopUpAmount, suggestedTopUp } from '../services/chips'
import { callAmount as getCallAmount, canAllIn, canAutoStartNextHand, canStart as getCanStart,
  minimumRaiseTo, quickRaiseTo, validRaise } from '../services/rules'
import { seatsFromViewer } from '../services/tableView'

const props = defineProps({ table: Object, playerId: String, advice: Object, busy: Boolean, connected: Boolean })
const emit = defineEmits(['action', 'chips', 'start', 'leave'])
const raiseTo = ref(40)
const chipAmount = ref(100)
const autoNext = ref(localStorage.getItem('poker.autoNext') !== 'false')
const autoCountdown = ref(0)
const strategyExpanded = ref(false)
let autoTimer

const me = computed(() => props.table.players.find(player => player.id === props.playerId))
const myTurn = computed(() => me.value?.currentTurn)
const callAmount = computed(() => getCallAmount(props.table, me.value))
const canStart = computed(() => getCanStart(props.table))
const minRaiseTo = computed(() => minimumRaiseTo(props.table))
const canRaise = computed(() => validRaise(props.table, me.value, Number(raiseTo.value)))
const allInAllowed = computed(() => canAllIn(props.table, me.value))
const seats = computed(() => seatsFromViewer(props.table.players, props.table.maxPlayers, props.playerId))
const betweenHands = computed(() => ['WAITING', 'SHOWDOWN'].includes(props.table.phase))
const transferAmount = computed(() => Number(chipAmount.value) || 0)
const canTopUp = computed(() => canTopUpAmount(props.table, me.value, transferAmount.value))
const canCashOut = computed(() => {
  const remaining = (me.value?.chips || 0) - transferAmount.value
  return transferAmount.value > 0 && remaining >= 0
    && (remaining === 0 || remaining >= props.table.minBuyIn)
})
const autoNextEligible = computed(() => canAutoStartNextHand(props.table, me.value))
const quickRaises = computed(() => {
  const options = [
    { label: '½ 池', amount: quickRaiseTo(props.table, me.value, 0.5) },
    { label: '¾ 池', amount: quickRaiseTo(props.table, me.value, 0.75) },
    { label: '满池', amount: quickRaiseTo(props.table, me.value, 1) }
  ]
  if (props.advice?.recommendedAction === 'RAISE' && props.advice.raiseTo) {
    options.unshift({ label: '建议', amount: props.advice.raiseTo })
  }
  const seen = new Set()
  return options.filter(option => option.amount && validRaise(props.table, me.value, option.amount)
    && !seen.has(option.amount) && seen.add(option.amount))
})

watch(minRaiseTo, value => { raiseTo.value = value }, { immediate: true })
watch([
  () => props.table.phase,
  () => me.value?.chips,
  () => me.value?.reserveChips,
  () => props.table.defaultBuyIn
], () => {
  if (!betweenHands.value || canTopUp.value) return
  const suggestion = suggestedTopUp(props.table, me.value)
  if (suggestion > 0) chipAmount.value = suggestion
}, { immediate: true })
watch([autoNextEligible, autoNext, () => props.busy], ([eligible, enabled, busy]) => {
  clearAutoTimer()
  if (!eligible || !enabled || busy) return
  autoCountdown.value = 3
  autoTimer = window.setInterval(() => {
    autoCountdown.value--
    if (autoCountdown.value <= 0) {
      clearAutoTimer()
      emit('start')
    }
  }, 1000)
}, { immediate: true })
watch(myTurn, value => {
  if (value && navigator.vibrate) navigator.vibrate(70)
})

function act(type) {
  emit('action', { type, raiseTo: type === 'RAISE' ? Number(raiseTo.value) : null })
}

function quickRaise(amount) {
  emit('action', { type: 'RAISE', raiseTo: Number(amount) })
}

function startHand() {
  clearAutoTimer()
  emit('start')
}

function clearAutoTimer() {
  if (autoTimer) window.clearInterval(autoTimer)
  autoTimer = null
  autoCountdown.value = 0
}

function toggleAutoNext() {
  localStorage.setItem('poker.autoNext', String(autoNext.value))
}

function transfer(type, amount = transferAmount.value) {
  emit('chips', type, Number(amount))
}

function percent(value) {
  return `${Math.round((Number(value) || 0) * 100)}%`
}

onBeforeUnmount(clearAutoTimer)
</script>

<template>
  <main class="room-shell">
    <header class="room-header">
      <button class="ghost-button" @click="emit('leave')">← 暂离牌桌</button>
      <div>
        <p class="eyebrow">{{ table.phaseLabel }} · 第 {{ table.handNumber || 0 }} 局</p>
        <h1>{{ table.name }}</h1>
      </div>
      <div class="connection" :class="{ online: connected }">
        <span></span>{{ connected ? '实时在线' : '正在重连' }}
      </div>
    </header>

    <section class="table-stage">
      <div class="poker-table">
        <div class="felt-copy">
          <span>RIVER ROOM</span>
          <small>NO LIMIT · {{ table.smallBlind }}/{{ table.bigBlind }}</small>
        </div>
        <div class="board">
          <div class="community-cards">
            <PlayingCard v-for="(card, index) in table.communityCards" :key="index" :value="card" />
            <div v-for="index in 5 - table.communityCards.length" :key="`slot-${index}`" class="empty-card"></div>
          </div>
          <div class="pot">底池 <strong>{{ table.pot }}</strong></div>
          <div v-if="table.pots?.length > 1" class="side-pots">
            <span v-for="(amount, index) in table.pots" :key="index">{{ index ? `边池 ${index}` : '主池' }} {{ amount }}</span>
          </div>
        </div>

        <div v-for="seat in seats" :key="seat.actualSeat" class="seat" :class="[`seat-${seat.position}`, { empty: !seat.player, active: seat.player?.currentTurn, mine: seat.player?.id === playerId, 'all-in': seat.player?.status === 'ALL_IN' }]">
          <template v-if="seat.player">
            <div class="hole-cards">
              <PlayingCard v-for="(card, index) in seat.player.cards" :key="index" :value="card" small />
            </div>
            <div class="player-chip" :title="seat.player.status">
              <span v-if="seat.player.dealer" class="dealer">D</span>
              <span v-if="seat.player.ai" class="ai-badge">AI</span>
              <strong class="player-name">{{ seat.player.nickname }}</strong>
              <small class="player-stack" :title="`桌外备用 ${seat.player.reserveChips}`"><span aria-hidden="true">◉</span>{{ seat.player.chips }}</small>
            </div>
            <span v-if="seat.player.streetBet" class="bet-chip">{{ seat.player.streetBet }}</span>
          </template>
          <span v-else class="empty-label">空位</span>
        </div>
      </div>
    </section>

    <section v-if="betweenHands && me" class="bankroll-bar">
      <div class="bankroll-summary">
        <span>桌上 <strong>{{ me.chips }}</strong></span>
        <span>备用 <strong>{{ me.reserveChips }}</strong></span>
        <span>当前总计 <strong>{{ me.totalChips }}</strong></span>
      </div>
      <div class="bankroll-actions">
        <label>调整金额<input v-model.number="chipAmount" type="number" min="1" :step="table.bigBlind" /></label>
        <button :disabled="busy || !canTopUp" @click="transfer('TOP_UP')">补码</button>
        <button :disabled="busy || !canCashOut" @click="transfer('CASH_OUT')">回收</button>
        <button class="cash-all" :disabled="busy || !me.chips" @click="transfer('CASH_OUT', me.chips)">全部回收</button>
      </div>
      <small>只可在两局之间调整；桌上需保持 {{ table.minBuyIn }}–{{ table.maxBuyIn }}，也可全部回收暂时停手。</small>
      <small v-if="table.privateTable && me.chips < table.minBuyIn" class="top-up-reminder">桌上筹码低于最低带入 {{ table.minBuyIn }}，自动下一局已暂停；补码后会自动恢复。</small>
    </section>

    <section v-if="table.privateTable && !betweenHands" class="strategy-panel">
      <header>
        <div><p class="eyebrow">REAL-TIME STRATEGY</p><strong>近似 GTO 实时参考</strong></div>
        <div class="strategy-head-actions"><span>{{ myTurn ? '轮到你行动' : '持续计算中' }}</span><button type="button" @click="strategyExpanded = !strategyExpanded">{{ strategyExpanded ? '收起' : '详情' }}</button></div>
      </header>
      <template v-if="advice?.available">
        <div class="strategy-metrics">
          <div><small>预估胜率</small><strong>{{ percent(advice.equity) }}</strong></div>
          <div><small>底池赔率</small><strong>{{ percent(advice.potOdds) }}</strong></div>
          <div><small>胜率优势</small><strong :class="{ negative: advice.edge < 0 }">{{ advice.edge >= 0 ? '+' : '' }}{{ percent(advice.edge) }}</strong></div>
          <div class="strategy-action"><small>建议动作</small><strong>{{ advice.actionLabel }}</strong></div>
        </div>
        <div v-if="strategyExpanded" class="strategy-mix">
          <div><span>弃牌 {{ advice.foldPercent }}%</span><i><b :style="{ width: `${advice.foldPercent}%` }"></b></i></div>
          <div><span>{{ advice.passiveLabel }} {{ advice.checkCallPercent }}%</span><i><b :style="{ width: `${advice.checkCallPercent}%` }"></b></i></div>
          <div><span>加注 {{ advice.raisePercent }}%</span><i><b :style="{ width: `${advice.raisePercent}%` }"></b></i></div>
        </div>
        <p>{{ advice.summary }}</p>
        <small v-if="strategyExpanded" class="strategy-note">{{ advice.note }}</small>
      </template>
      <div v-else class="strategy-loading">正在根据手牌、公共牌和对手数量模拟胜率…</div>
    </section>

    <section class="control-panel">
      <div class="status-copy">
        <p>{{ table.message }}</p>
        <small v-if="myTurn">轮到你了 · 跟注额 {{ callAmount }}</small>
        <small v-else-if="!canStart">等待其他玩家行动</small>
        <small v-else>至少两人即可开始下一局</small>
      </div>
      <div v-if="myTurn" class="actions">
        <button class="danger" :class="{ recommended: advice?.recommendedAction === 'FOLD' }" :disabled="busy" @click="act('FOLD')">弃牌</button>
        <button v-if="callAmount === 0" :class="{ recommended: advice?.recommendedAction === 'CHECK' }" :disabled="busy" @click="act('CHECK')">过牌</button>
        <button v-else :class="{ recommended: ['CALL','ALL_IN'].includes(advice?.recommendedAction) }" :disabled="busy" @click="act(callAmount >= me.chips ? 'ALL_IN' : 'CALL')">{{ callAmount >= me.chips ? `全押跟注 ${me.chips}` : `跟注 ${callAmount}` }}</button>
        <div v-if="quickRaises.length" class="quick-raises">
          <button v-for="option in quickRaises" :key="option.amount" :class="{ recommended: option.label === '建议' }" :disabled="busy" @click="quickRaise(option.amount)">{{ option.label }} <strong>{{ option.amount }}</strong></button>
        </div>
        <label class="raise-input">加注至 <input v-model.number="raiseTo" type="number" :min="minRaiseTo" :max="me.chips + me.streetBet - 1" :step="table.bigBlind" /></label>
        <button class="gold" :class="{ recommended: advice?.recommendedAction === 'RAISE' }" :disabled="busy || !canRaise" @click="act('RAISE')">自定义加注</button>
        <button v-if="callAmount < me.chips" class="all-in-button" :class="{ recommended: advice?.recommendedAction === 'ALL_IN' }" :disabled="busy || !allInAllowed" @click="act('ALL_IN')">全押 {{ me.chips }}</button>
      </div>
      <div v-else-if="canStart" class="next-hand-controls">
        <button class="gold start-button" :disabled="busy" @click="startHand">{{ autoCountdown ? `${autoCountdown} 秒后自动下一局` : '开始下一局' }}</button>
        <button v-if="autoCountdown" class="cancel-countdown" type="button" @click="clearAutoTimer">取消倒计时</button>
        <label v-if="table.privateTable" class="auto-next-toggle"><input v-model="autoNext" type="checkbox" @change="toggleAutoNext" /><span>自动下一局</span></label>
      </div>
    </section>
    <p class="mvp-note">规则：2–6 人、盲注 {{ table.smallBlind }}/{{ table.bigBlind }}、带入 {{ table.minBuyIn }}–{{ table.maxBuyIn }}；支持补码、回收、全押、边池与断线身份恢复。</p>
  </main>
</template>
