<script setup>
import { computed } from 'vue'
import { requestNumber, callType, callStatus } from '../context/compressionDisplay'
import { count, money, totals, downloadReport } from './report'

const props = defineProps({ models: Array, modelId: String, runs: Array, estimate: Object, estimating: Boolean, previewError: String, busy: Boolean, title: String, conversation: Object })

const model = computed(() => props.models?.find((m) => m.id === props.modelId))
const summary = computed(() => totals(props.runs || []))
const compression = computed(() => totals((props.runs || []).filter((r) => r.purpose === 'summary')))
const savings = computed(() => props.conversation?.token_savings)
const savingsStatus = computed(() => {
  const value = savings.value
  if (!value || (!value.compared_dialogue_runs && !value.summary_runs)) return 'Экономия появится после первого сжатия и следующего ответа.'
  if (!value.complete) return 'Чистый итог неизвестен: не для всех вызовов API вернул usage.'
  if (value.net_savings_tokens > 0) return `Сжатие окупилось: сохранено примерно ${count(value.net_savings_tokens)} токенов.`
  if (value.net_savings_tokens < 0) return `Сжатие пока не окупилось: дополнительно потрачено примерно ${count(Math.abs(value.net_savings_tokens))} токенов.`
  return 'Затраты на сжатие пока равны уменьшению входного контекста.'
})
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
          <dt>Лимит ответа</dt><dd>{{ estimate.reserved_output_tokens == null ? 'По умолчанию API' : count(estimate.reserved_output_tokens) }}</dd>
        </dl>
        <meter min="0" max="100" :value="Math.min(100, estimate.occupancy_percent)" aria-label="Заполнение окна контекста"></meter>
        <small>{{ estimate.occupancy_percent }}% окна {{ estimate.reserved_output_tokens == null ? '— только вход' : 'с резервом ответа' }}</small>
        <p v-if="estimate.exceeds_context" class="token-warning">Ожидается переполнение. Отправка разрешена — проверит провайдер.</p>
        <small v-if="estimate.reserved_output_tokens == null" class="muted">Резерв ответа неизвестен. Даже при входе меньше окна API может отклонить запрос с учётом своего лимита ответа.</small>
        <small class="muted">{{ estimate.method }}</small>
      </template>
      <p v-else class="muted">{{ previewError || (busy ? 'Ожидаем результат вызова…' : 'Считаем контекст…') }}</p>
    </details>
    <details class="token-details savings-details" :open="Boolean(savings?.summary_runs || savings?.compared_dialogue_runs)">
      <summary>Эффект сжатия</summary>
      <template v-if="savings">
        <dl class="token-grid">
          <dt>Без сжатия, вход ≈</dt><dd>{{ count(savings.full_prompt_tokens) }}</dd>
          <dt>Со сжатием, вход ≈</dt><dd>{{ count(savings.compressed_prompt_tokens) }}</dd>
          <dt>Сокращено на входе ≈</dt><dd>{{ count(savings.gross_input_savings_tokens) }}</dd>
          <dt>Создание сводок, API</dt><dd>{{ count(savings.summary_usage_tokens) }}</dd>
          <dt>Чистая экономия ≈</dt><dd>{{ savings.net_savings_tokens == null ? 'неизвестна' : count(savings.net_savings_tokens) }}</dd>
          <dt>От исходного входа ≈</dt><dd>{{ savings.net_savings_percent == null ? '—' : `${savings.net_savings_percent}%` }}</dd>
        </dl>
        <p :class="savings.net_savings_tokens > 0 ? 'token-success' : 'muted'">{{ savingsStatus }}</p>
        <small v-if="savings.unknown_dialogue_runs || savings.unknown_summary_runs" class="token-warning">
          Без usage: ответов — {{ savings.unknown_dialogue_runs }}, сжатий — {{ savings.unknown_summary_runs }}.
        </small>
        <details v-if="savings.mixed_models" class="savings-models">
          <summary>Разбивка по моделям</summary>
          <p v-for="item in savings.by_model" :key="`${item.model_id}:${item.method}`">
            <strong>{{ item.requested_model }}</strong> · сокращено ≈ {{ count(item.gross_input_savings_tokens) }},
            summary API {{ count(item.summary_usage_tokens) }},
            чистый итог {{ item.net_savings_tokens == null ? 'неизвестен' : `≈ ${count(item.net_savings_tokens)}` }}
          </p>
        </details>
        <small class="muted">Формула: уменьшение входных prompt минус вход и выход всех вызовов summary. Это оценка контрфактического сценария; ответы без сжатия реально не выполняются.</small>
      </template>
      <p v-else class="muted">Данных пока нет.</p>
    </details>
    <details class="token-details" :open="runs?.length > 0">
      <summary>Расход диалога · {{ runs?.length || 0 }} вызовов LLM</summary>
      <small class="muted">{{ (runs || []).filter(r => r.purpose !== 'summary').length }} вызовов для ответа + {{ (runs || []).filter(r => r.purpose === 'summary').length }} вызовов сжатия. Один ваш запрос может вызвать оба; неудачные попытки тоже учитываются.</small>
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
          <thead><tr><th>Вызов</th><th>Тип / ваш запрос</th><th>Вход</th><th>Выход</th></tr></thead>
          <tbody><tr v-for="(run, i) in runs" :key="run.id" :title="`${run.requested_model}: ${run.error_code || run.status}`">
            <td>{{ i + 1 }}</td><td class="usage-purpose">{{ callType(run) }} · №{{ requestNumber(conversation?.messages, run.user_index) ?? '—' }}<small>{{ callStatus(run) }}</small></td><td>{{ count(run.usage?.prompt_tokens) }}</td><td>{{ count(run.usage?.completion_tokens) }}</td>
          </tr></tbody>
        </table>
      </div>
      <button class="report-button" :disabled="!runs?.length" @click="downloadReport(title, runs, conversation)">↓ Скачать MD-отчёт</button>
      <small class="muted">Отчёт содержит тексты переписки и сводку. Проверьте их перед публикацией.</small>
      <small class="muted">Σ — вызов сжатия, уже включён в общий расход. Блок «Эффект сжатия» вычитает его API usage из оценочного сокращения prompt.</small>
    </details>
    <a v-if="model" class="pricing-link" :href="model.pricing.source" target="_blank" rel="noopener noreferrer">Тарифы {{ model.provider }} ↗</a>
  </section>
</template>
