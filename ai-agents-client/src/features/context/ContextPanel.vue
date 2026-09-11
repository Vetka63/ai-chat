<script setup>
import { computed, ref, watch } from 'vue'
const props = defineProps({ settings: Object, summary: Object, busy: Boolean, hasConversation: Boolean, estimate: Object })
defineEmits(['change', 'fork'])
const dirty = computed(() => props.settings && ['mode', 'keep_last', 'summarize_every'].some(key => local.value[key] !== props.settings[key]))
const local = ref({ mode: 'full', keep_last: 10, summarize_every: 10 })
watch(() => props.settings, (value) => { if (value) local.value = { ...value } }, { immediate: true })
</script>

<template>
  <section class="token-panel sidebar-section context-panel">
    <p class="section-title">Память · День 9</p>
    <form class="token-details" @submit.prevent="$emit('change', { ...local })">
      <fieldset :disabled="busy">
        <legend>Стратегия контекста</legend>
        <label class="context-choice"><input v-model="local.mode" type="radio" value="full" name="context-mode"> Полная история</label>
        <label class="context-choice"><input v-model="local.mode" type="radio" value="summary" name="context-mode"> Summary + последние сообщения</label>
        <div v-if="local.mode === 'summary'" class="context-fields">
          <label>Оставлять целиком минимум сообщений<input v-model.number="local.keep_last" type="number" min="2" max="100" step="2" required></label>
          <label>Порог сжатия старых сообщений<input v-model.number="local.summarize_every" type="number" min="2" max="100" step="2" required></label>
          <small class="muted">Считаем каждое ваше сообщение и каждый ответ модели отдельно. 4 сообщения — обычно 2 вопроса и 2 ответа.</small>
          <small class="muted">Сначала оставляем минимум {{ local.keep_last }} последних сообщений. Когда за их пределами накопится хотя бы {{ local.summarize_every }} ещё не сжатых сообщений, обновляем сводку перед следующим ответом. До этого целиком передаётся больше сообщений; первая порция в длинном чате может быть больше порога.</small>
        </div>
        <button class="report-button" type="submit">Применить настройки</button>
      </fieldset>
      <p v-if="dirty" class="token-warning">Изменения ещё не применены. Нажмите «Применить настройки».</p>
      <small>Сохранено: {{ settings?.mode === 'summary' ? 'сжатая память' : 'полная история' }}<template v-if="settings?.mode === 'summary'">; минимум {{ settings.keep_last }} целиком, порог {{ settings.summarize_every }}</template>. Переписка не удаляется.</small>
    </form>
    <div v-if="settings?.mode === 'summary' && estimate?.history_message_count != null && !busy" class="memory-progress token-details">
      <strong>Перед следующим ответом</strong>
      <dl class="token-grid">
        <dt>В истории (обе роли)</dt><dd>{{ estimate.history_message_count }}</dd>
        <dt>Старых, ожидающих сжатия</dt><dd>{{ estimate.unsummarized_old_messages }} / {{ settings.summarize_every }}</dd>
        <dt>Пока передаём целиком</dt><dd>{{ estimate.retained_message_count }}</dd>
      </dl>
      <small v-if="estimate.pending_summary">Порог достигнут: сначала обновим сводку, затем запросим ответ.</small>
      <small v-else>Порог ещё не достигнут. За пределами последних {{ settings.keep_last }} нужно накопить ещё {{ estimate.messages_until_summary }} старых сообщений.</small>
      <small class="muted">Черновик и будущий ответ не входят в счётчик. Граница может сдвигаться, чтобы не разрывать вопрос и ответ.</small>
    </div>
    <details class="token-details">
      <summary>Сводка памяти {{ summary ? `· версия ${summary.revision}` : '' }}</summary>
      <template v-if="summary">
        <small>Охватывает первые {{ summary.covered_messages }} сообщений · {{ summary.returned_model }}</small>
        <small v-if="settings?.mode === 'full'" class="muted">Сохранена, но в полном режиме не используется.</small>
        <pre class="summary-text">{{ summary.text }}</pre>
      </template>
      <p v-else class="muted">Появится при отправке, когда накопится достаточно старых сообщений. Просмотр и черновик не вызывают LLM.</p>
    </details>
    <small v-if="estimate?.pending_summary" class="token-warning">При отправке сначала создадим новую сводку. Оценка ниже пока использует текущую память.</small>
    <button class="report-button" :disabled="busy || !hasConversation" @click="$emit('fork')">⧉ Копия для сравнения</button>
    <small class="muted">Создаёт независимый чат с той же перепиской и другим режимом, без прежних затрат и summary. Сделайте копию до контрольного вопроса и задайте его в обоих чатах.</small>
  </section>
</template>
