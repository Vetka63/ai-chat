<script setup>
import { count, money } from './report'
import { callType } from '../context/compressionDisplay'
defineProps({ run: Object })
</script>
<template>
  <div v-if="run" class="run-metrics">
    <p v-if="run.status === 'error' || run.status === 'interrupted'" class="token-warning" role="status">{{ run.error_message }} · {{ run.error_code }}</p>
    <details>
      <summary>{{ callType(run) }} · {{ run.returned_model || run.requested_model }} · {{ run.usage ? `${count(run.usage.total_tokens)} токенов` : 'usage неизвестен' }} · {{ money(run.estimated_cost_usd) }} ≈</summary>
      <dl class="token-grid">
        <dt>API input / output</dt><dd>{{ count(run.usage?.prompt_tokens) }} / {{ count(run.usage?.completion_tokens) }}</dd>
        <dt>Cached / reasoning</dt><dd>{{ count(run.usage?.cached_tokens) }} / {{ count(run.usage?.reasoning_tokens) }}</dd>
        <dt>Время / завершение</dt><dd>{{ count(run.duration_ms) }} мс / {{ run.finish_reason || '—' }}</dd>
        <dt>Лимит ответа</dt><dd>{{ run.estimate.reserved_output_tokens == null ? 'По умолчанию API' : count(run.estimate.reserved_output_tokens) }}</dd>
        <dt>Оценка prompt</dt><dd>{{ count(run.estimate.prompt_tokens) }}</dd>
        <template v-if="run.memory_context?.profile">
          <dt>Профиль при вызове</dt><dd>{{ run.memory_context.profile.name }} · v{{ run.memory_context.profile.revision }}</dd>
          <dt>Профиль, токены ≈</dt><dd>{{ count(run.estimate.profile_tokens) }}</dd>
        </template>
        <template v-if="run.estimate.context_mode === 'memory_layers'">
          <dt>Рабочая / долговременная ≈</dt><dd>{{ count(run.estimate.working_memory_tokens) }} / {{ count(run.estimate.long_term_memory_tokens) }}</dd>
          <dt>Сообщений в контексте</dt><dd>{{ run.estimate.retained_message_count }} + текущий запрос</dd>
        </template>
        <template v-if="!['full', 'memory_layers'].includes(run.estimate.context_mode) && run.purpose === 'dialogue'">
          <dt>Полная история ≈</dt><dd>{{ count(run.estimate.full_prompt_tokens) }}</dd>
          <dt>Исключено сообщений</dt><dd>{{ count(run.estimate.discarded_message_count) }}</dd>
        </template>
        <template v-if="run.estimate.context_mode === 'summary'">
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
