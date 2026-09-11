<script setup>
import { count, money } from './report'
defineProps({ run: Object })
</script>
<template>
  <div v-if="run" class="run-metrics">
    <p v-if="run.status === 'error' || run.status === 'interrupted'" class="token-warning" role="status">{{ run.error_message }} · {{ run.error_code }}</p>
    <details>
      <summary>{{ run.purpose === 'summary' ? 'Σ Сжатие · ' : 'Ответ · ' }}{{ run.returned_model || run.requested_model }} · {{ run.usage ? `${count(run.usage.total_tokens)} токенов` : 'usage неизвестен' }} · {{ money(run.estimated_cost_usd) }} ≈</summary>
      <dl class="token-grid">
        <dt>API input / output</dt><dd>{{ count(run.usage?.prompt_tokens) }} / {{ count(run.usage?.completion_tokens) }}</dd>
        <dt>Cached / reasoning</dt><dd>{{ count(run.usage?.cached_tokens) }} / {{ count(run.usage?.reasoning_tokens) }}</dd>
        <dt>Время / завершение</dt><dd>{{ count(run.duration_ms) }} мс / {{ run.finish_reason || '—' }}</dd>
        <dt>Лимит ответа</dt><dd>{{ run.estimate.reserved_output_tokens == null ? 'По умолчанию API' : count(run.estimate.reserved_output_tokens) }}</dd>
        <dt>Оценка prompt</dt><dd>{{ count(run.estimate.prompt_tokens) }}</dd>
        <template v-if="run.estimate.context_mode === 'summary'">
          <dt>Без сжатия ≈</dt><dd>{{ count(run.estimate.full_prompt_tokens) }}</dd>
          <dt>Сообщений в сводке</dt><dd>{{ count(run.estimate.summarized_messages) }}</dd>
        </template>
        <dt>HTTP провайдера</dt><dd>{{ run.provider_status || '—' }}</dd>
      </dl>
      <small>{{ run.estimate.method }}. Тариф: {{ run.pricing.label }}.</small>
      <small>Запрошена {{ run.requested_model }}, возвращена {{ run.returned_model || '—' }}.</small>
    </details>
    <small v-if="run.finish_reason === 'length'" class="token-warning">Ответ остановлен лимитом выходных токенов. Это не переполнение входного контекста.</small>
  </div>
</template>
