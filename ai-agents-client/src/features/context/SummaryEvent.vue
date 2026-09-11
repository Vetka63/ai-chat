<script setup>
import { computed } from 'vue'
import RunMetrics from '../tokens/RunMetrics.vue'
import { compressionDescription, requestNumber, callStatus } from './compressionDisplay'
const props = defineProps({ run: Object, runs: Array, messages: Array })
const request = computed(() => requestNumber(props.messages, props.run.user_index))
</script>

<template>
  <aside class="summary-event" aria-label="Служебное событие сжатия">
    <strong>Σ Обновление памяти · {{ callStatus(run) }}</strong>
    <p>Перед ответом на ваш запрос {{ request == null ? '' : `№${request}` }} · сообщение истории №{{ run.user_index + 1 }}.</p>
    <p>{{ compressionDescription(run, runs) }}</p>
    <small>Это отдельный вызов LLM для памяти, не второй ответ на ваш вопрос. Текущий вопрос в эту порцию сжатия не входит.</small>
    <RunMetrics :run="run" />
  </aside>
</template>
