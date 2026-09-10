<script setup>
import { computed } from 'vue'
import { count, money, totals, downloadReport } from './report'

const props = defineProps({ models: Array, modelId: String, runs: Array, estimate: Object, estimating: Boolean, previewError: String, busy: Boolean, title: String, conversation: Object })

const model = computed(() => props.models?.find((m) => m.id === props.modelId))
const summary = computed(() => totals(props.runs || []))
const compression = computed(() => totals((props.runs || []).filter((r) => r.purpose === 'summary')))
</script>

<template>
  <section class="token-panel sidebar-section">
    <p class="section-title">Токены и расход</p>
    <details open class="token-details">
      <summary>Следующий запрос <span v-if="estimating">· подсчёт…</span></summary>
      <template v-if="estimate">
        <dl class="token-grid">
          <dt>Ваш текст ≈</dt><dd>{{ count(estimate.current_message_tokens) }}</dd>
          <dt>Вся исходная история ≈</dt><dd>{{ count(estimate.history_tokens) }}</dd>
          <dt>Системный текст ≈</dt><dd>{{ count(estimate.system_tokens) }}</dd>
          <dt>Весь prompt ≈</dt><dd>{{ count(estimate.prompt_tokens) }}</dd>
          <template v-if="estimate.context_mode === 'summary'">
            <dt>Без сжатия ≈</dt><dd>{{ count(estimate.full_prompt_tokens) }}</dd>
            <dt>Размер сводки ≈</dt><dd>{{ count(estimate.summary_tokens) }}</dd>
            <dt>Разница prompt ≈</dt><dd>{{ count((estimate.full_prompt_tokens ?? estimate.prompt_tokens) - estimate.prompt_tokens) }}</dd>
          </template>
          <dt>Лимит ответа</dt><dd>{{ count(estimate.reserved_output_tokens) }}</dd>
        </dl>
        <meter min="0" max="100" :value="Math.min(100, estimate.occupancy_percent)" aria-label="Заполнение окна контекста"></meter>
        <small>{{ estimate.occupancy_percent }}% окна с резервом ответа</small>
        <p v-if="estimate.exceeds_context" class="token-warning">Ожидается переполнение. Отправка разрешена — проверит провайдер.</p>
        <small class="muted">{{ estimate.method }}</small>
      </template>
      <p v-else class="muted">{{ previewError || (busy ? 'Ожидаем результат вызова…' : 'Считаем контекст…') }}</p>
    </details>
    <details class="token-details" :open="runs?.length > 0">
      <summary>Расход диалога · {{ runs?.length || 0 }} вызовов</summary>
      <dl class="token-grid">
        <dt>API total, сумма</dt><dd>{{ summary.known ? count(summary.tokens) : '—' }}</dd>
        <dt>Стоимость ≈</dt><dd>{{ summary.known ? money(summary.cost) : '—' }}</dd>
        <dt>В т.ч. сжатие, токены</dt><dd>{{ compression.known ? count(compression.tokens) : (compression.unknown ? '—' : '0') }}</dd>
        <dt>В т.ч. сжатие, USD ≈</dt><dd>{{ compression.known ? money(compression.cost) : (compression.unknown ? '—' : '$0') }}</dd>
      </dl>
      <small v-if="summary.unknown" class="token-warning">Без usage: {{ summary.unknown }}. Их расход неизвестен.</small>
      <div v-if="runs?.length" class="usage-table-wrap">
        <table class="usage-table">
          <caption>Рост токенов по вызовам (API)</caption>
          <thead><tr><th>№</th><th>Вход</th><th>Выход</th></tr></thead>
          <tbody><tr v-for="(run, i) in runs" :key="run.id" :title="`${run.requested_model}: ${run.error_code || run.status}`">
            <td>{{ i + 1 }}{{ run.purpose === 'summary' ? ' Σ' : '' }}{{ run.status === 'error' ? ' !' : '' }}</td><td>{{ count(run.usage?.prompt_tokens) }}</td><td>{{ count(run.usage?.completion_tokens) }}</td>
          </tr></tbody>
        </table>
      </div>
      <button class="report-button" :disabled="!runs?.length" @click="downloadReport(title, runs, conversation)">↓ Скачать MD-отчёт</button>
      <small class="muted">Отчёт содержит тексты переписки и сводку. Проверьте их перед публикацией.</small>
      <small class="muted">Σ — вызов сжатия, уже включён в общую сумму. Уменьшение prompt не равно чистой экономии: учитывайте затраты на summary.</small>
    </details>
    <a v-if="model" class="pricing-link" :href="model.pricing.source" target="_blank" rel="noopener noreferrer">Тарифы {{ model.provider }} ↗</a>
  </section>
</template>
