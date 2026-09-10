<script setup>
import { ref, watch } from 'vue'
const props = defineProps({ settings: Object, summary: Object, busy: Boolean, hasConversation: Boolean, estimate: Object })
defineEmits(['change', 'fork'])
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
          <label>Оставлять сообщений (N)<input v-model.number="local.keep_last" type="number" min="2" max="100" step="2" required></label>
          <label>Сжимать порциями по<input v-model.number="local.summarize_every" type="number" min="2" max="100" step="2" required></label>
          <small class="muted">Считаются сообщения, не пары вопрос–ответ. До очередного сжатия хвост может быть длиннее N.</small>
        </div>
        <button class="report-button" type="submit">Применить режим</button>
      </fieldset>
      <small>Сохранено: {{ settings?.mode === 'summary' ? 'сжатая память' : 'полная история' }}. Переписка не удаляется.</small>
    </form>
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
