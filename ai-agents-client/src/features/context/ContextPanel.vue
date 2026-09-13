<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  settings: Object, summary: Object, facts: Object, conversation: Object,
  conversations: Array, busy: Boolean, hasConversation: Boolean, estimate: Object,
})
const emit = defineEmits(['fork', 'checkpoint', 'branches', 'select-branch'])
const checkpointTitle = ref('Варианты решения')
const branchA = ref('Ветка A')
const branchB = ref('Ветка B')
const family = computed(() => {
  const root = props.conversation?.root_conversation_id || props.conversation?.id
  return (props.conversations || []).filter(item => (item.root_conversation_id || item.id) === root)
})
const latestCheckpoint = computed(() => props.conversation?.checkpoints?.at(-1))
const mode = computed(() => ({
  full: ['∞', 'Полная история', 'В модель передаётся вся сохранённая история.'],
  summary: ['Σ', 'Summary + хвост', 'Старый префикс заменяется сводкой, свежие сообщения передаются как есть.'],
  sliding_window: ['⇥', 'Sliding Window', 'В модель передаются только последние N сообщений.'],
  sticky_facts: ['◆', 'Sticky Facts', 'Перед ответом обновляются key-value facts и добавляются к последним N сообщениям.'],
  branching: ['⑂', 'Branching', 'Этот чат развивается как независимая ветка своей истории.'],
}[props.settings?.mode] || ['∞', 'Полная история', 'В модель передаётся вся сохранённая история.']))
function createBranches(checkpointId) {
  emit('branches', { checkpointId, names: [branchA.value.trim(), branchB.value.trim()] })
}
</script>

<template>
  <section class="token-panel sidebar-section context-panel">
    <p class="section-title">Управление контекстом · День 10</p>
    <div class="locked-context token-details">
      <span class="locked-context-icon">{{ mode[0] }}</span>
      <div><strong>{{ mode[1] }}</strong><p>{{ mode[2] }}</p></div>
      <span class="locked-badge">Зафиксировано</span>
    </div>
    <dl v-if="['summary','sliding_window','sticky_facts'].includes(settings?.mode)" class="token-grid token-details">
      <dt>Последних сообщений (N)</dt><dd>{{ settings.keep_last }}</dd>
      <template v-if="settings.mode === 'summary'"><dt>Порция summary</dt><dd>{{ settings.summarize_every }}</dd></template>
    </dl>
    <small class="immutable-note">Стратегия задаётся при создании и не меняется. Для сравнения создайте новый чат.</small>

    <div v-if="['sliding_window','sticky_facts'].includes(settings?.mode) && estimate?.history_message_count != null" class="memory-progress token-details">
      <strong>Контекст следующего ответа</strong>
      <dl class="token-grid">
        <dt>Всего в SQLite</dt><dd>{{ estimate.history_message_count }}</dd>
        <dt>Передадим как есть</dt><dd>{{ estimate.retained_message_count }}</dd>
        <dt>Исключено из хвоста</dt><dd>{{ estimate.discarded_message_count }}</dd>
        <template v-if="settings.mode === 'sticky_facts'"><dt>Facts</dt><dd>{{ estimate.fact_count || 0 }}</dd></template>
      </dl>
    </div>

    <div v-if="settings?.mode === 'summary' && estimate?.history_message_count != null && !busy" class="memory-progress token-details">
      <strong>Перед следующим ответом</strong>
      <dl class="token-grid">
        <dt>В истории (обе роли)</dt><dd>{{ estimate.history_message_count }}</dd>
        <dt>Старых, ожидающих сжатия</dt><dd>{{ estimate.unsummarized_old_messages }} / {{ settings.summarize_every }}</dd>
        <dt>Пока передаём целиком</dt><dd>{{ estimate.retained_message_count }}</dd>
      </dl>
      <small v-if="estimate.pending_summary">Порог достигнут: сначала обновим сводку, затем запросим ответ.</small>
      <small v-else>До следующей сводки: {{ estimate.messages_until_summary }} сообщений.</small>
    </div>

    <details v-if="settings?.mode === 'summary' || summary" class="token-details">
      <summary>Сводка памяти {{ summary ? `· версия ${summary.revision}` : '' }}</summary>
      <template v-if="summary"><small>Охватывает первые {{ summary.covered_messages }} сообщений</small><pre class="summary-text">{{ summary.text }}</pre></template>
      <p v-else class="muted">Сводка ещё не создана.</p>
    </details>
    <small v-if="settings?.mode === 'summary' && estimate?.pending_summary" class="token-warning">При отправке сначала создадим новую сводку. Предварительный просмотр LLM не вызывает.</small>

    <details v-if="settings?.mode === 'sticky_facts' || facts" class="token-details" open>
      <summary>Sticky Facts {{ facts ? `· версия ${facts.revision}` : '' }}</summary>
      <dl v-if="facts && Object.keys(facts.facts).length" class="facts-list">
        <template v-for="(value, key) in facts.facts" :key="key"><dt>{{ key }}</dt><dd>{{ value }}</dd></template>
      </dl>
      <p v-else class="muted">Важные данные появятся после следующего сообщения.</p>
      <small v-if="facts">Последнее обновление: сообщение №{{ facts.updated_from_message }} · {{ facts.returned_model }}</small>
    </details>

    <div v-if="settings?.mode === 'branching'" class="token-details branching-controls">
      <strong>Checkpoint и две ветки</strong>
      <label>Название checkpoint<input v-model="checkpointTitle" maxlength="80"></label>
      <button class="report-button" :disabled="busy || !hasConversation || !checkpointTitle.trim()" @click="emit('checkpoint', checkpointTitle.trim())">＋ Сохранить checkpoint здесь</button>
      <template v-if="latestCheckpoint">
        <small>Последний: {{ latestCheckpoint.title }} · {{ latestCheckpoint.message_count }} сообщений</small>
        <div class="branch-name-fields"><input v-model="branchA" maxlength="60"><input v-model="branchB" maxlength="60"></div>
        <button class="report-button" :disabled="busy || !branchA.trim() || !branchB.trim() || branchA.trim() === branchB.trim()" @click="createBranches(latestCheckpoint.id)">⑂ Создать две ветки</button>
      </template>
      <div v-if="family.length > 1" class="branch-family">
        <small>Диалоги этого дерева</small>
        <button v-for="item in family" :key="item.id" :class="{ active: item.id === conversation?.id }" :disabled="busy || item.id === conversation?.id" @click="emit('select-branch', item.id)">{{ item.branch_name || 'Исходный диалог' }}</button>
      </div>
    </div>

    <template v-if="['full','summary'].includes(settings?.mode)">
      <button class="report-button" :disabled="busy || !hasConversation" @click="emit('fork')">⧉ Копия для сравнения</button>
      <small class="muted">Создаёт новый чат с альтернативной стратегией full/summary; исходный не меняется.</small>
    </template>
  </section>
</template>
