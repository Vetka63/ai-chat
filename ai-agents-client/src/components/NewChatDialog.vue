<script setup>
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps({ open: Boolean, busy: Boolean, memoryLayers: Boolean })
const emit = defineEmits(['cancel', 'create'])
const dialog = ref(null)
const title = ref('Новый чат')
const settings = ref({ mode: 'full', keep_last: 10, summarize_every: 10 })
const problem = ref('')
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
  await nextTick()
  dialog.value?.querySelector('input')?.focus()
}, { immediate: true })
function submit() {
  emit('create', { title: title.value.trim(), contextSettings: { ...settings.value },
    ...(props.memoryLayers ? { problem: { statement: problem.value.trim() } } : {}) })
}
function keyboard(event) {
  if (event.key === 'Escape') { event.preventDefault(); emit('cancel'); return }
  if (event.key !== 'Tab') return
  const items = [...dialog.value.querySelectorAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled)')]
  const first = items[0], last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" @click.self="emit('cancel')">
    <section ref="dialog" class="new-chat-dialog" role="dialog" aria-modal="true" aria-labelledby="new-chat-title" @keydown="keyboard">
      <header><div><small>{{ memoryLayers ? 'Алгоритмический наставник' : 'Новый независимый диалог' }}</small><h2 id="new-chat-title">{{ memoryLayers ? 'Новая задача' : 'Настройте память чата' }}</h2></div><button type="button" aria-label="Закрыть создание чата" @click="emit('cancel')">×</button></header>
      <form @submit.prevent="submit">
        <label class="dialog-title">Название<input v-model="title" maxlength="120" required autofocus></label>
        <fieldset :disabled="busy">
          <legend>{{ memoryLayers ? 'Три слоя памяти' : 'Стратегия контекста' }}</legend>
          <template v-if="memoryLayers">
            <p class="muted">Профиль: Мой учебный профиль. Рабочая память будет отдельной; сохранённые знания доступны и в следующих задачах.</p>
            <label class="dialog-title">Условие задачи<textarea class="problem-input" v-model="problem" rows="4" maxlength="20000" placeholder="Вставьте условие или добавьте его позже в карточке задачи"></textarea></label>
          </template>
          <div v-else class="strategy-grid creation-strategies">
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
        <p class="immutable-note">{{ memoryLayers ? 'Условие сохранится в рабочую память. Новые сведения из переписки нужно сохранять явно в панели памяти. Размер хвоста фиксируется при создании.' : 'Стратегия фиксируется после создания. Для другого способа управления контекстом создайте новый чат.' }}</p>
        <footer><button type="button" class="secondary-button" :disabled="busy" @click="emit('cancel')">Отмена</button><button class="report-button" :disabled="busy || !title.trim()" type="submit">Создать чат</button></footer>
      </form>
    </section>
  </div>
</template>

<style scoped>
.problem-input { width: 100%; min-height: 110px; resize: vertical; font: inherit; padding: 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); color: var(--text); }
</style>
