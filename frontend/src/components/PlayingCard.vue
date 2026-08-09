<script setup>
import { computed } from 'vue'
import { displayRank } from '../services/cards'

const props = defineProps({
  value: { type: String, default: '??' },
  small: Boolean,
  motion: { type: String, default: '' },
  delay: { type: Number, default: 0 },
  highlighted: Boolean,
  muted: Boolean
})
const hidden = computed(() => props.value === '??')
const red = computed(() => /[♥♦]/.test(props.value))
const rank = computed(() => displayRank(props.value))
const motionStyle = computed(() => ({ '--card-delay': `${Math.max(0, props.delay)}ms` }))
</script>

<template>
  <div class="playing-card" :class="[
    { hidden, red, small, highlighted, muted },
    motion ? `motion-${motion}` : ''
  ]" :style="motionStyle">
    <span v-if="hidden" class="card-back">R</span>
    <template v-else>
      <span class="rank">{{ rank }}</span>
      <span class="suit">{{ value.slice(-1) }}</span>
    </template>
  </div>
</template>

