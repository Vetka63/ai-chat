<script setup>
import { computed, reactive, ref, watch } from 'vue'
const props = defineProps({ workspace: Object, busy: Boolean, loading: Boolean, error: String, lastContext: Object })
const emit = defineEmits(['refresh', 'problem', 'save', 'delete', 'propose', 'resolve'])
const fields = { statement: 'Условие', inputs: 'Входные данные', outputs: 'Ожидаемый результат', constraints: 'Ограничения', examples: 'Примеры' }
const keys = { goal: 'Цель', approach: 'Подход', plan: 'План', solution: 'Решение', test_cases: 'Тестовые случаи', findings: 'Наблюдения', constraints: 'Ограничения задачи' }
const problem = reactive({})
const layer = ref('working'), key = ref('goal'), value = ref(''), source = ref(''), editing = ref(false)
const userMessages = computed(() => props.workspace?.short_term.filter(m => m.role === 'user') || [])
const proposals = computed(() => props.workspace?.proposals.filter(p => p.status === 'pending') || [])
watch(() => JSON.stringify(props.workspace?.task.problem), () => {
  for (const name of Object.keys(fields)) problem[name] = props.workspace?.task.problem[name] || ''
}, { immediate: true })
watch(() => props.workspace?.task.id, reset)
function reset() { layer.value = 'working'; key.value = 'goal'; value.value = ''; source.value = ''; editing.value = false }
function changeLayer() { key.value = layer.value === 'working' ? 'goal' : ''; editing.value = false }
function edit(entry) {
  layer.value = entry.layer; key.value = entry.key; value.value = entry.value
  source.value = ''; editing.value = true
}
function save() {
  emit('save', { layer: layer.value, key: key.value.trim(), value: value.value.trim(), source_message_id: source.value ? Number(source.value) : null })
}
function remove(entry) {
  if (window.confirm(entry.layer === 'long_term' ? 'Удалить запись из долговременной памяти для всех задач наставника?' : 'Удалить запись из памяти этой задачи?')) emit('delete', entry.id)
}
</script>

<template>
  <section class="memory-panel sidebar-section">
    <p class="section-title">Три слоя памяти</p>
    <p v-if="error" role="alert" class="token-warning">{{ error }}</p>
    <p v-if="!workspace" class="muted">{{ loading ? 'Загружаем память…' : 'Создайте задачу, чтобы настроить память.' }}</p>
    <button v-if="!workspace && error" :disabled="busy || loading" @click="emit('refresh')">Обновить память</button>
    <template v-if="workspace">
      <small class="muted">Профиль: {{ workspace.profile.name }}. Долговременные записи изолированы по профилю и агенту. Анализ кода моделью не означает его запуск.</small>
      <details open>
        <summary>1. Краткосрочная · последние {{ workspace.keep_last }} сообщений</summary>
        <p>В запрос попадут {{ workspace.short_term.length }} из {{ workspace.history_message_count }} сохранённых сообщений + ваш новый запрос. Считаются сообщения, не пары.</p>
        <details v-for="message in workspace.short_term" :key="message.id" class="memory-entry">
          <summary>#{{ message.id }} · {{ message.role === 'user' ? 'Вы' : 'Наставник' }}</summary>
          <p class="memory-text">{{ message.content }}</p>
        </details>
        <p class="muted">Старая переписка остаётся в истории, но не отправляется в LLM.</p>
        <label v-if="userMessages.length">Сообщение для предложений
          <select :disabled="busy" v-model="source">
            <option value="">Последнее сообщение пользователя</option>
            <option v-for="m in userMessages" :key="m.id" :value="m.id">#{{ m.id }} · {{ m.content.slice(0, 60) }}</option>
          </select>
        </label>
        <button :disabled="busy || !userMessages.length || workspace.workflow?.state.status === 'paused' || workspace.workflow?.state.phase === 'done'" @click="emit('propose', Number(source) || userMessages.at(-1)?.id)">Предложить, что запомнить</button>
        <small class="muted">Отдельный API-вызов с расходом токенов. Ничего не сохраняет без вашего подтверждения.</small>
      </details>
      <details open>
        <summary>2. Рабочая · только эта задача</summary>
        <details>
          <summary>Карточка задачи</summary>
          <form @submit.prevent="emit('problem', { ...problem })">
            <label v-for="(label, name) in fields" :key="name">{{ label }}
              <textarea v-model="problem[name]" :disabled="busy" :maxlength="name === 'statement' ? 20000 : 10000" rows="3"></textarea>
            </label>
            <button :disabled="busy">Сохранить карточку</button>
          </form>
        </details>
        <p v-if="!workspace.working.length" class="muted">Пока нет записей. План и решения можно сохранить вручную ниже.</p>
        <article v-for="entry in workspace.working" :key="entry.id" class="memory-entry">
          <strong>{{ keys[entry.key] || entry.key }}</strong><p class="memory-text">{{ entry.value }}</p>
          <small>{{ entry.author === 'user' ? 'Сохранено вами' : 'Предложено LLM, подтверждено вами' }} · ревизия {{ entry.revision }}<br>{{ entry.source_excerpt }}</small>
          <div class="memory-actions"><button :disabled="busy" @click="edit(entry)">Изменить</button><button :disabled="busy" @click="remove(entry)">Удалить</button></div>
        </article>
      </details>
      <details open>
        <summary>3. Долговременная · будущие задачи</summary>
        <p class="muted">Общая для задач этого профиля и наставника. При удалении чата не удаляется. Отключение убирает запись из блока памяти, но не стирает её упоминания из переписки.</p>
        <p v-if="!workspace.long_term.length" class="muted">Нет подтверждённых записей.</p>
        <article v-for="entry in workspace.long_term" :key="entry.id" class="memory-entry">
          <strong>{{ entry.key }}</strong><p class="memory-text">{{ entry.value }}</p>
          <label class="memory-toggle"><input type="checkbox" :checked="entry.active" :disabled="busy" @change="emit('save', { layer: entry.layer, key: entry.key, value: entry.value, active: $event.target.checked })">Использовать в запросах</label>
          <small>{{ entry.author === 'user' ? 'Сохранено вами' : 'Предложено LLM, подтверждено вами' }} · ревизия {{ entry.revision }}<br>{{ entry.source_excerpt }}</small>
          <div class="memory-actions"><button :disabled="busy" @click="edit(entry)">Изменить</button><button :disabled="busy" @click="remove(entry)">Удалить</button></div>
        </article>
      </details>
      <details :open="editing">
        <summary>{{ editing ? 'Редактировать запись' : 'Сохранить в память вручную' }}</summary>
        <form @submit.prevent="save">
          <label>Куда сохранить<select v-model="layer" :disabled="busy || editing" @change="changeLayer"><option value="working">Рабочая — эта задача</option><option value="long_term">Долговременная — будущие задачи</option></select></label>
          <label>Название
            <select v-if="layer === 'working'" v-model="key" :disabled="busy || editing"><option v-for="(label, name) in keys" :key="name" :value="name">{{ label }}</option></select>
            <input v-else v-model="key" :disabled="busy || editing" required maxlength="80">
          </label>
          <label>Содержимое<textarea v-model="value" :disabled="busy" required maxlength="20000" rows="4"></textarea></label>
          <p class="muted">Источник: {{ source ? 'выбранное сообщение #' + source : 'ручной ввод' }}. Запись с таким же названием будет заменена.</p>
          <div class="memory-actions"><button :disabled="busy || !key.trim() || !value.trim()">Сохранить запись</button><button type="button" :disabled="busy" @click="reset">Сбросить форму</button></div>
        </form>
      </details>
      <details v-if="proposals.length" open>
        <summary>Предложения LLM · {{ proposals.length }}</summary>
        <p class="muted">Пока не подтверждены, в ответы агента не попадают.</p>
        <article v-for="p in proposals" :key="p.id" class="memory-entry">
          <strong>{{ p.layer === 'working' ? 'В эту задачу' : 'В будущие задачи' }} · {{ keys[p.key] || p.key }}</strong>
          <p class="memory-text">{{ p.value }}</p><small>{{ p.reason }}<br>Источник: {{ p.source_excerpt }}</small>
          <div class="memory-actions"><button :disabled="busy" @click="emit('resolve', p.id, 'accept')">Подтвердить</button><button :disabled="busy" @click="emit('resolve', p.id, 'reject')">Отклонить</button></div>
        </article>
      </details>
      <details v-if="lastContext">
        <summary>Память в последнем запросе</summary>
        <p class="muted">Снимок на момент вызова. Последующие изменения памяти не меняют этот снимок.</p>
        <pre>{{ JSON.stringify(lastContext, null, 2) }}</pre>
      </details>
      <button :disabled="busy || loading" @click="emit('refresh')">{{ busy ? 'Выполняем…' : 'Обновить память' }}</button>
    </template>
  </section>
</template>

<style scoped>
.memory-panel { font-size: 12px; line-height: 1.6; }
details { border: 1px solid var(--line); border-radius: 10px; padding: 10px; min-width: 0; }
summary { cursor: pointer; font-weight: 600; }
p { margin: 8px 0; }
form, label { display: flex; flex-direction: column; gap: 6px; margin: 8px 0; }
input, select, textarea { font: inherit; color: var(--text); background: var(--panel); border: 1px solid var(--line); border-radius: 7px; padding: 8px; width: 100%; min-width: 0; }
textarea { resize: vertical; }
button { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 7px 9px; cursor: pointer; font-size: 12px; }
button:hover { border-color: var(--accent); }
button:disabled { opacity: .5; cursor: default; }
small { display: block; color: var(--muted); overflow-wrap: anywhere; }
.memory-entry { border-bottom: 1px solid var(--line); padding: 10px 0; }
.memory-text { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 200px; overflow-y: auto; }
.memory-actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.memory-toggle { flex-direction: row; align-items: center; }
.memory-toggle input { width: auto; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 300px; overflow: auto; font-size: 11px; }
</style>
