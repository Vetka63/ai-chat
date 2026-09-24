<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { listMcpServers } from '../features/mcp/api'

const props = defineProps({ open: Boolean, busy: Boolean, memoryLayers: Boolean, personalization: Boolean, mcpTools: Boolean, scheduledReports: Boolean, profiles: Array, defaultProfileId: String, agentName: String })
const emit = defineEmits(['cancel', 'create'])
const dialog = ref(null)
const title = ref('Новый чат')
const settings = ref({ mode: 'full', keep_last: 10, summarize_every: 10 })
const problem = ref('')
const profileId = ref('local')
const mcpServers = ref([])
const selectedServers = ref([])
const mcpError = ref('')
const modes = [
  ['full', '∞', 'Полная история', 'Все сообщения диалога'],
  ['summary', 'Σ', 'Summary', 'Сводка старого контекста и свежий хвост'],
  ['sliding_window', '⇥', 'Sliding Window', 'Только последние N сообщений'],
  ['sticky_facts', '◆', 'Sticky Facts', 'Ключевые факты и последние N сообщений'],
  ['branching', '⑂', 'Branching', 'Checkpoint и независимые продолжения'],
]
const requiresWindow = computed(() => ['summary', 'sliding_window', 'sticky_facts'].includes(settings.value.mode))
watch(() => props.open, async value => {
  if (!value) return
  title.value = 'Новый чат'
  settings.value = { mode: props.memoryLayers ? 'sliding_window' : 'full', keep_last: props.memoryLayers ? 4 : 10, summarize_every: 10 }
  problem.value = ''
  profileId.value = props.defaultProfileId || 'local'
  mcpServers.value = []
  selectedServers.value = []
  mcpError.value = ''
  if (props.mcpTools) {
    try {
      const response = await listMcpServers()
      if (!props.open) return
      mcpServers.value = (Array.isArray(response) ? response : response.servers || []).filter(server => server.chat_enabled)
      selectedServers.value = mcpServers.value.length ? [mcpServers.value[0].id] : []
      if (!mcpServers.value.length) mcpError.value = 'Нет MCP-серверов, доступных для чата'
    } catch (cause) { mcpError.value = cause.message }
  }
  await nextTick()
  dialog.value?.querySelector('input')?.focus()
}, { immediate: true })
function submit() {
  if (props.busy || (props.personalization && !props.profiles?.some(p => p.id === profileId.value))) return
  emit('create', { title: title.value.trim(), contextSettings: { ...settings.value },
    ...(props.memoryLayers ? { problem: { statement: problem.value.trim() } } : {}),
    ...(props.personalization ? { profileId: profileId.value } : {}),
    ...(props.mcpTools ? { mcpServerIds: [...selectedServers.value] } : {}) })
}
function keyboard(event) {
  if (event.key === 'Escape') { event.preventDefault(); emit('cancel'); return }
  if (event.key !== 'Tab') return
  const items = [...dialog.value.querySelectorAll('button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled)')]
  const first = items[0], last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" @click.self="emit('cancel')">
    <section ref="dialog" class="new-chat-dialog" role="dialog" aria-modal="true" aria-labelledby="new-chat-title" @keydown="keyboard">
      <header><div><small>{{ agentName || 'Текущий агент' }}</small><h2 id="new-chat-title">{{ scheduledReports ? 'Новый чат сводок' : mcpTools ? 'Подключения нового чата' : memoryLayers ? 'Новая задача' : 'Настройте память чата' }}</h2></div><button type="button" aria-label="Закрыть создание чата" @click="emit('cancel')">×</button></header>
      <form @submit.prevent="submit">
        <label class="dialog-title">Название<input v-model="title" maxlength="120" required autofocus></label>
        <fieldset v-if="!scheduledReports" :disabled="busy">
          <legend>{{ mcpTools ? 'Доступные MCP-серверы' : memoryLayers ? 'Три слоя памяти' : 'Стратегия контекста' }}</legend>
          <template v-if="mcpTools">
            <p class="muted">Это начальный набор инструментов, доступных модели. Она сама решает, когда их вызвать. Можно создать чат без MCP и подключить серверы позже.</p>
            <p v-if="mcpError" role="alert">{{ mcpError }}</p>
            <label v-for="server in mcpServers" :key="server.id" class="mcp-server-option">
              <input v-model="selectedServers" type="checkbox" :value="server.id">
              <span><strong>{{ server.name }}</strong><small>{{ server.description }}</small></span>
            </label>
          </template>
          <template v-if="memoryLayers">
            <label v-if="personalization" class="dialog-title">Профиль для задачи
              <select v-model="profileId" class="profile-select" required>
                <option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.name }}</option>
              </select>
            </label>
            <p class="muted">{{ personalization ? 'Профиль фиксируется при создании. Его предпочтения можно редактировать отдельно; знания другого профиля сюда не попадут.' : 'Профиль: Мой учебный профиль.' }} Рабочая память будет отдельной для этой задачи.</p>
            <label class="dialog-title">Условие задачи<textarea class="problem-input" v-model="problem" rows="4" maxlength="20000" placeholder="Вставьте условие или добавьте его позже в карточке задачи"></textarea></label>
          </template>
          <div v-else-if="!mcpTools" class="strategy-grid creation-strategies">
            <label v-for="item in modes" :key="item[0]" class="strategy-choice" :class="{ selected: settings.mode === item[0] }">
              <input v-model="settings.mode" type="radio" :value="item[0]" name="new-context-mode">
              <span>{{ item[1] }}</span><strong>{{ item[2] }}</strong><small>{{ item[3] }}</small>
            </label>
          </div>
          <div v-if="requiresWindow" class="context-fields">
            <label>Последних сообщений (N)<input v-model.number="settings.keep_last" type="number" :min="settings.mode === 'summary' ? 2 : 1" max="100" :step="settings.mode === 'summary' ? 2 : 1" required></label>
            <label v-if="settings.mode === 'summary'">Обновлять summary порциями по<input v-model.number="settings.summarize_every" type="number" min="2" max="100" step="2" required></label>
          </div>
        </fieldset>
        <p class="immutable-note">{{ scheduledReports ? 'После создания задайте расписание в чате. Worker будет добавлять игры через MCP и сохранять три последние сводки.' : mcpTools ? 'Чат хранится в SQLite. Выбранные здесь серверы можно изменить для каждого сообщения в поле ввода.' : memoryLayers ? 'Условие сохранится в рабочую память. Новые сведения из переписки нужно сохранять явно в панели памяти. Размер хвоста фиксируется при создании.' : 'Стратегия фиксируется после создания. Для другого способа управления контекстом создайте новый чат.' }}</p>
        <footer><button type="button" class="secondary-button" :disabled="busy" @click="emit('cancel')">Отмена</button><button class="report-button" :disabled="busy || !title.trim() || (personalization && !profiles?.some(p => p.id === profileId))" type="submit">Создать чат</button></footer>
      </form>
    </section>
  </div>
</template>

<style scoped>
.problem-input { width: 100%; min-height: 110px; resize: vertical; font: inherit; padding: 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); color: var(--text); }
.profile-select { width: 100%; font: inherit; padding: 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); color: var(--text); }
.mcp-server-option { display: flex; gap: 12px; padding: 14px; margin: 8px 0; border: 1px solid var(--line); border-radius: 12px; background: var(--panel); cursor: pointer; }
.mcp-server-option input { width: 18px; accent-color: var(--accent); }
.mcp-server-option span { display: grid; gap: 3px; }
.mcp-server-option small { color: var(--muted); }
</style>
