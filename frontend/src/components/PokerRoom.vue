<script setup>
import { computed, ref, watch } from 'vue'
import PlayingCard from './PlayingCard.vue'
import { callAmount as getCallAmount, canAllIn, canStart as getCanStart, minimumRaiseTo, validRaise } from '../services/rules'
import { seatsFromViewer } from '../services/tableView'

const props = defineProps({ table: Object, playerId: String, busy: Boolean, connected: Boolean })
const emit = defineEmits(['action', 'chips', 'start', 'leave'])
const raiseTo = ref(40)
const chipAmount = ref(100)

const me = computed(() => props.table.players.find(player => player.id === props.playerId))
const myTurn = computed(() => me.value?.currentTurn)
const callAmount = computed(() => getCallAmount(props.table, me.value))
const canStart = computed(() => getCanStart(props.table))
const minRaiseTo = computed(() => minimumRaiseTo(props.table))
const canRaise = computed(() => validRaise(props.table, me.value, Number(raiseTo.value)))
const allInAllowed = computed(() => canAllIn(props.table, me.value))
const seats = computed(() => seatsFromViewer(props.table.players, props.table.maxPlayers, props.playerId))
const betweenHands = computed(() => ['WAITING', 'SHOWDOWN'].includes(props.table.phase))
const maxTopUp = computed(() => Math.max(0, Math.min(me.value?.reserveChips || 0,
  props.table.maxBuyIn - (me.value?.chips || 0))))
const transferAmount = computed(() => Number(chipAmount.value) || 0)
const canTopUp = computed(() => transferAmount.value > 0 && transferAmount.value <= maxTopUp.value
  && (me.value?.chips || 0) + transferAmount.value >= props.table.minBuyIn)
const canCashOut = computed(() => {
  const remaining = (me.value?.chips || 0) - transferAmount.value
  return transferAmount.value > 0 && remaining >= 0
    && (remaining === 0 || remaining >= props.table.minBuyIn)
})

watch(minRaiseTo, value => { raiseTo.value = value }, { immediate: true })
watch(() => props.table.bigBlind, value => { chipAmount.value = Math.max(value, value * 10) }, { immediate: true })

function act(type) {
  emit('action', { type, raiseTo: type === 'RAISE' ? Number(raiseTo.value) : null })
}

function transfer(type, amount = transferAmount.value) {
  emit('chips', type, Number(amount))
}
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
    </section>

    <section class="control-panel">
      <div class="status-copy">
        <p>{{ table.message }}</p>
        <small v-if="myTurn">轮到你了 · 跟注额 {{ callAmount }}</small>
        <small v-else-if="!canStart">等待其他玩家行动</small>
        <small v-else>至少两人即可开始下一局</small>
      </div>
      <div v-if="myTurn" class="actions">
        <button class="danger" :disabled="busy" @click="act('FOLD')">弃牌</button>
        <button v-if="callAmount === 0" :disabled="busy" @click="act('CHECK')">过牌</button>
        <button v-else :disabled="busy" @click="act(callAmount >= me.chips ? 'ALL_IN' : 'CALL')">{{ callAmount >= me.chips ? `全押跟注 ${me.chips}` : `跟注 ${callAmount}` }}</button>
        <label class="raise-input">加注至 <input v-model.number="raiseTo" type="number" :min="minRaiseTo" :max="me.chips + me.streetBet - 1" :step="table.bigBlind" /></label>
        <button class="gold" :disabled="busy || !canRaise" @click="act('RAISE')">加注</button>
        <button v-if="callAmount < me.chips" class="all-in-button" :disabled="busy || !allInAllowed" @click="act('ALL_IN')">全押 {{ me.chips }}</button>
      </div>
      <button v-else-if="canStart" class="gold start-button" :disabled="busy" @click="emit('start')">开始一局</button>
    </section>
    <p class="mvp-note">规则：2–6 人、盲注 {{ table.smallBlind }}/{{ table.bigBlind }}、带入 {{ table.minBuyIn }}–{{ table.maxBuyIn }}；支持补码、回收、全押、边池与断线身份恢复。</p>
  </main>
</template>
