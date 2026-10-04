<script setup lang="ts">
import { computed } from 'vue'
import type { Schema } from '../../api/client'
import { usageText } from '../experiments/report'
import { supportGenerations } from './report'

const props = defineProps<{ repair: Schema<'GroundingRepair'> }>()
const rejected = computed(() => {
  let claims: unknown[] = []
  try {
    const draft = JSON.parse(props.repair.originalGeneration.rawJson)
    if (Array.isArray(draft.claims)) claims = draft.claims
  } catch { /* Diagnostic text is shown verbatim below, never promoted to an answer. */ }
  return props.repair.originalSupportCheck.claims.filter(c => c.verdict !== 'SUPPORTED').map(c => {
    const claim = claims[c.claimIndex] as { text?: unknown } | undefined
    return { ...c, text: typeof claim?.text === 'string' ? claim.text : 'Текст пункта доступен в исходном JSON.' }
  })
})
</script>

<template>
  <details class="grounding-repair unverified-diagnostics">
    <summary>Исправление черновика · 1 попытка</summary>
    <p>Первая проверка отклонила черновик по смыслу. Выполнена одна попытка исправления на тех же источниках. Текст и причины ниже — непроверенная диагностика, не публичный ответ. Итоговый статус показан отдельно.</p>
    <p>Черновик: {{ repair.originalGeneration.model }} · {{ repair.originalGeneration.finishReason }} · {{ repair.originalGeneration.milliseconds }} мс · API input/output/total: {{ usageText(repair.originalGeneration.usage) }}.</p>
    <p>Первая проверка: {{ repair.originalSupportCheck.status }} · вызовов {{ supportGenerations(repair.originalSupportCheck).length }}.</p><p v-for="(g, i) in supportGenerations(repair.originalSupportCheck)" :key="i">Вызов {{ i + 1 }} · {{ g.model }} · {{ g.milliseconds }} мс · {{ usageText(g.usage) }}</p>
    <p class="hint">Расход черновика, его проверки и попытки исправления уже учтён в общей сумме; повторно прибавлять его не нужно.</p>
    <section v-for="claim in rejected" :key="claim.claimIndex" class="rejected-draft-claim">
      <p>Отклонённый пункт {{ claim.claimIndex + 1 }} · {{ claim.verdict }}</p>
      <blockquote>{{ claim.text }}</blockquote>
      <p>Причина: {{ claim.reason }}</p>
    </section>
    <p v-for="(issue, i) in repair.originalSupportCheck.issues" :key="i">{{ issue.code }} · {{ issue.message }}</p>
    <details><summary>Исходный JSON и промпт · непроверенный черновик</summary><pre v-for="(message, i) in repair.originalGeneration.messages" :key="i">{{ message.role }}: {{ message.content }}</pre><pre>{{ repair.originalGeneration.rawJson }}</pre></details>
    <details><summary>Исходная проверка · непроверенная диагностика</summary><section v-for="(g, n) in supportGenerations(repair.originalSupportCheck)" :key="n"><pre v-for="(message, i) in g.messages" :key="i">{{ message.role }}: {{ message.content }}</pre><pre>{{ g.rawJson }}</pre></section></details>
  </details>
</template>
