<script setup>
import { computed } from 'vue'

const props = defineProps({ value: { type: String, default: '??' }, small: Boolean })
const hidden = computed(() => props.value === '??')
const red = computed(() => /[♥♦]/.test(props.value))
</script>

<template>
  <div class="playing-card" :class="{ hidden, red, small }">
    <span v-if="hidden" class="card-back">R</span>
    <template v-else>
      <span class="rank">{{ value.slice(0, -1) }}</span>
      <span class="suit">{{ value.slice(-1) }}</span>
    </template>
  </div>
</template>

