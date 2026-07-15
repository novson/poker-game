<script setup>
import { computed, ref, watch } from 'vue'
import PlayingCard from './PlayingCard.vue'
import { callAmount as getCallAmount, canStart as getCanStart, minimumRaiseTo, validRaise } from '../services/rules'

const props = defineProps({ table: Object, playerId: String, busy: Boolean, connected: Boolean })
const emit = defineEmits(['action', 'start', 'leave'])
const raiseTo = ref(40)

const me = computed(() => props.table.players.find(player => player.id === props.playerId))
const myTurn = computed(() => me.value?.currentTurn)
const callAmount = computed(() => getCallAmount(props.table, me.value))
const canStart = computed(() => getCanStart(props.table))
const minRaiseTo = computed(() => minimumRaiseTo(props.table))
const canRaise = computed(() => validRaise(props.table, me.value, Number(raiseTo.value)))
const seats = computed(() => Array.from({ length: props.table.maxPlayers }, (_, seat) =>
  props.table.players.find(player => player.seat === seat) || null))

watch(minRaiseTo, value => { raiseTo.value = value }, { immediate: true })

function act(type) {
  emit('action', { type, raiseTo: type === 'RAISE' ? Number(raiseTo.value) : null })
}
</script>

<template>
  <main class="room-shell">
    <header class="room-header">
      <button class="ghost-button" @click="emit('leave')">← 返回大厅</button>
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
        </div>

        <div v-for="(player, seat) in seats" :key="seat" class="seat" :class="[`seat-${seat}`, { empty: !player, active: player?.currentTurn, mine: player?.id === playerId }]">
          <template v-if="player">
            <div class="hole-cards">
              <PlayingCard v-for="(card, index) in player.cards" :key="index" :value="card" small />
            </div>
            <div class="player-chip" :title="player.status">
              <span v-if="player.dealer" class="dealer">D</span>
              <strong>{{ player.nickname }}</strong>
              <small>◉ {{ player.chips }}</small>
            </div>
            <span v-if="player.streetBet" class="bet-chip">{{ player.streetBet }}</span>
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
        <button v-else :disabled="busy || callAmount >= me.chips" @click="act('CALL')">跟注 {{ callAmount }}</button>
        <label class="raise-input">加注至 <input v-model.number="raiseTo" type="number" :min="minRaiseTo" :max="me.chips + me.streetBet - 1" :step="table.bigBlind" /></label>
        <button class="gold" :disabled="busy || !canRaise" @click="act('RAISE')">加注</button>
      </div>
      <button v-else-if="canStart" class="gold start-button" :disabled="busy" @click="emit('start')">开始一局</button>
    </section>
    <p class="mvp-note">MVP 规则：2–6 人、起始 2,000 筹码；暂不支持全押、边池和中途重连身份恢复。</p>
  </main>
</template>
