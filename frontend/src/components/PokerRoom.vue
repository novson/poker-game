<script setup>
import { computed, ref, watch } from 'vue'
import PlayingCard from './PlayingCard.vue'
import { callAmount as getCallAmount, canAllIn, canStart as getCanStart, minimumRaiseTo, validRaise } from '../services/rules'
import { seatsFromViewer } from '../services/tableView'

const props = defineProps({ table: Object, playerId: String, busy: Boolean, connected: Boolean })
const emit = defineEmits(['action', 'start', 'leave'])
const raiseTo = ref(40)

const me = computed(() => props.table.players.find(player => player.id === props.playerId))
const myTurn = computed(() => me.value?.currentTurn)
const callAmount = computed(() => getCallAmount(props.table, me.value))
const canStart = computed(() => getCanStart(props.table))
const minRaiseTo = computed(() => minimumRaiseTo(props.table))
const canRaise = computed(() => validRaise(props.table, me.value, Number(raiseTo.value)))
const allInAllowed = computed(() => canAllIn(props.table, me.value))
const seats = computed(() => seatsFromViewer(props.table.players, props.table.maxPlayers, props.playerId))

watch(minRaiseTo, value => { raiseTo.value = value }, { immediate: true })

function act(type) {
  emit('action', { type, raiseTo: type === 'RAISE' ? Number(raiseTo.value) : null })
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
              <strong class="player-name">{{ seat.player.nickname }}</strong>
              <small class="player-stack"><span aria-hidden="true">◉</span>{{ seat.player.chips }}</small>
            </div>
            <span v-if="seat.player.streetBet" class="bet-chip">{{ seat.player.streetBet }}</span>
          </template>
          <span v-else class="empty-label">空位</span>
        </div>
      </div>
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
    <p class="mvp-note">规则：2–6 人、起始 2,000 筹码；支持全押、边池与断线身份恢复。</p>
  </main>
</template>
