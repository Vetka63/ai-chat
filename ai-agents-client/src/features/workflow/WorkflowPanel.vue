<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({ workspace: Object, busy: Boolean, sending: Boolean, error: String })
const emit = defineEmits(['apply', 'refresh'])
const phases = { planning: 'Планирование', execution: 'Реализация', validation: 'Проверка', done: 'Завершено' }
const phaseOrder = Object.keys(phases)
const actions = { discuss_plan: 'Обсудите план с агентом', accept_plan: 'Подтвердите план',
  work_on_step: 'Обсудите выбранный шаг и решение', accept_solution: 'Подтвердите решение',
  review_solution: 'Проверьте сохранённое решение', accept_validation: 'Подтвердите проверку',
  resume: 'Продолжите задачу', completed: 'Задача завершена' }
const eventLabels = { pause: 'Пауза', resume: 'Продолжение', select_step: 'Выбран шаг',
  accept_plan: 'План принят', accept_solution: 'Решение сохранено', accept_validation: 'Проверка принята',
  invariants_changed: 'Правила задачи изменены' }
const artifactLabels = { plan: 'План', solution: 'Решение', validation: 'Проверка' }
const state = computed(() => props.workspace?.state)
const latest = computed(() => Object.fromEntries((props.workspace?.artifacts || []).map(item => [item.kind, item])))
const candidate = computed(() => props.workspace?.candidate_text || '')
const draft = ref('')
const method = ref('llm_review')
const trayOpen = ref(false)
const activeTab = ref('proposal')
const trayExpanded = ref(false)

watch(() => [props.workspace?.task_id, state.value?.phase, state.value?.candidate_message_id], (current, previous) => {
  if (current[0] !== previous?.[0]) {
    trayOpen.value = Boolean(current[2])
    activeTab.value = 'proposal'
    trayExpanded.value = false
  } else if (current[2] && current[2] !== previous?.[2]) {
    trayOpen.value = true
    activeTab.value = 'proposal'
  } else if (!current[2] && previous?.[2] && activeTab.value === 'proposal') {
    trayOpen.value = false
    trayExpanded.value = false
  }
}, { immediate: true })

function openTab(tab) {
  if (trayOpen.value && activeTab.value === tab) { trayOpen.value = false; return }
  activeTab.value = tab
  trayOpen.value = true
}

function planLines(text) {
  const sections = text.split(/\n(?=#{1,3}\s)/)
  const stepsSection = sections.find(section => /^#{1,3}\s*(?:шаги|план решения)/i.test(section.trim()))
  const lines = (stepsSection || text).split('\n').map(line => line.trim())
    .filter(line => /^(?:\d+[.)]|[-*])\s+/.test(line))
    .map(line => line.replace(/^(?:\d+[.)]|[-*])\s+/, '').replace(/^\*\*(.+?)\*\*/, '$1').trim())
  return lines.length ? lines.join('\n') : text.trim().slice(0, 500)
}

watch(() => [props.workspace?.task_id, state.value?.phase, state.value?.candidate_message_id, candidate.value], () => {
  draft.value = state.value?.phase === 'planning' ? planLines(candidate.value || '') : candidate.value || ''
}, { immediate: true })

const canAccept = computed(() => Boolean(candidate.value && draft.value.trim() && state.value?.status === 'active'
  && state.value?.phase !== 'done' && !props.busy && !props.sending))

function accept() {
  if (!canAccept.value) return
  const phase = state.value.phase
  const content = phase === 'planning'
    ? { steps: draft.value.split('\n').map(s => s.trim()).filter(Boolean) }
    : { text: draft.value.trim(), ...(phase === 'validation' ? { method: method.value } : {}) }
  emit('apply', { planning: 'accept_plan', execution: 'accept_solution', validation: 'accept_validation' }[phase], { content })
}
</script>

<template>
  <section v-if="workspace" class="workflow" aria-label="Состояние задачи">
    <div class="workflow-top">
      <div class="task-heading"><small>Задача · День 13</small><strong>{{ phases[state.phase] }}</strong>
        <span v-if="state.status === 'paused'" class="pause-badge">На паузе</span></div>
      <ol class="phases" aria-label="Этапы задачи"><li v-for="(label, phase) in phases" :key="phase"
        :class="{ current: state.phase === phase, complete: phaseOrder.indexOf(phase) < phaseOrder.indexOf(state.phase) }"
        :aria-current="state.phase === phase ? 'step' : undefined"><span>{{ phaseOrder.indexOf(phase) + 1 }}</span>{{ label }}</li></ol>
      <div class="workflow-actions">
        <button v-if="state.status === 'active' && state.phase !== 'done'" :disabled="busy" @click="emit('apply', 'pause')">Пауза</button>
        <button v-if="state.status === 'paused'" :disabled="busy" @click="emit('apply', 'resume')">Продолжить</button>
        <button :disabled="busy" aria-label="Обновить состояние задачи" title="Обновить" @click="emit('refresh')">↻</button>
      </div>
    </div>
    <div class="workflow-lower"><p class="next-action">Сейчас: {{ actions[state.expected_action] }}</p>
      <div class="task-tabs">
        <button v-if="candidate" :class="{ selected: trayOpen && activeTab === 'proposal' }"
          :aria-expanded="trayOpen && activeTab === 'proposal'" @click="openTab('proposal')">✦ Предложение <span class="unread-dot" aria-label="Новое"></span></button>
        <button :class="{ selected: trayOpen && activeTab === 'artifacts' }" :aria-expanded="trayOpen && activeTab === 'artifacts'"
          @click="openTab('artifacts')">▤ Материалы <span class="tab-count">{{ workspace.artifacts.length }}</span></button>
        <button :class="{ selected: trayOpen && activeTab === 'events' }" :aria-expanded="trayOpen && activeTab === 'events'"
          @click="openTab('events')">◷ История</button>
      </div>
    </div>
    <p v-if="error" role="alert" class="workflow-error">{{ error }}</p>
    <label v-if="state.phase === 'execution' && latest.plan" class="step-picker">Текущий шаг
      <select :value="state.current_step_id" :disabled="busy || sending || state.status === 'paused'"
        @change="emit('apply', 'select_step', { step_id: $event.target.value })">
        <option v-for="step in latest.plan.content.steps" :key="step.id" :value="step.id">{{ step.title }}</option>
      </select>
    </label>
    <div v-if="trayOpen" class="task-tray" :class="{ expanded: trayExpanded }">
      <div class="tray-header"><strong>{{ activeTab === 'proposal' ? 'Черновик результата' : activeTab === 'artifacts' ? 'Материалы задачи' : 'История задачи' }}</strong>
        <div><button :aria-label="trayExpanded ? 'Уменьшить область материалов' : 'Растянуть область материалов'"
          :aria-pressed="trayExpanded" @click="trayExpanded = !trayExpanded">{{ trayExpanded ? 'Уменьшить' : 'Растянуть' }}</button>
          <button aria-label="Свернуть материалы задачи" @click="trayOpen = false">×</button></div></div>
      <div v-if="activeTab === 'proposal' && candidate" class="tray-content proposal-editor">
        <p>Ответ агента можно отредактировать перед подтверждением. Продолжить обсуждение можно в чате ниже.</p>
        <label>{{ state.phase === 'planning' ? 'Шаги плана — один на строку' : 'Текст результата' }}
          <textarea v-model="draft" maxlength="30000" :disabled="state.status === 'paused'"></textarea></label>
        <label v-if="state.phase === 'validation'" class="method-picker">Источник проверки
          <select v-model="method" :disabled="state.status === 'paused'"><option value="llm_review">Анализ LLM — код не запускался</option>
            <option value="user_test_result">Результат моего запуска</option></select></label>
        <div class="tray-footer"><span v-if="state.status === 'paused'">Продолжите задачу, чтобы принять результат.</span>
          <button class="confirm" :disabled="!canAccept" @click="accept">{{ {
            planning: 'Принять план и перейти к реализации', execution: 'Сохранить решение и перейти к проверке',
            validation: 'Принять проверку и завершить' }[state.phase] }}</button></div>
      </div>
      <div v-else-if="activeTab === 'artifacts'" class="tray-content artifact-list">
        <p v-if="!workspace.artifacts.length">Пока нет подтверждённых материалов. Они появятся после принятия ответа агента.</p>
        <details v-for="item in [...workspace.artifacts].reverse()" :key="item.id" class="artifact-card">
          <summary><span class="artifact-icon">{{ { plan: '▤', solution: '⌘', validation: '✓' }[item.kind] }}</span>
            <span><strong>{{ artifactLabels[item.kind] }} · версия {{ item.revision }}</strong><small>{{ item.created_at }}</small></span><span aria-hidden="true">⌄</span></summary>
          <ol v-if="item.kind === 'plan'"><li v-for="step in item.content.steps" :key="step.id">{{ step.title }}</li></ol>
          <pre v-else>{{ item.content.text }}</pre>
          <small v-if="item.kind === 'validation'">{{ item.content.method === 'llm_review' ? 'Анализ LLM, код не запускался' : 'Результат запуска со слов пользователя' }}</small>
        </details>
      </div>
      <div v-else class="tray-content event-list">
        <p v-if="!workspace.events.length">Действий пока нет.</p>
        <ol v-else><li v-for="event in [...workspace.events].reverse()" :key="event.revision"><strong>{{ eventLabels[event.action] }}</strong>
          <span>{{ phases[event.from_phase] }} → {{ phases[event.to_phase] }} · {{ event.to_status === 'paused' ? 'на паузе' : 'активно' }}</span>
          <small>{{ event.created_at }}</small></li></ol>
      </div>
    </div>
  </section>
</template>

<style scoped>
.workflow { flex: 0 0 auto; min-width: 0; container-type: inline-size; padding: 10px 20px; border-bottom: 1px solid var(--line); background: var(--panel); }
.workflow-top, .workflow-actions, .workflow-lower, .task-tabs, .tray-header, .tray-header > div, .tray-footer { display: flex; align-items: center; gap: 9px; }
.workflow-top, .workflow-lower, .tray-header { justify-content: space-between; }
.task-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 5px 9px; flex: 0 0 auto; }
.task-heading small { width: 100%; color: var(--muted); font-size: 10px; letter-spacing: .1em; text-transform: uppercase; }
.task-heading strong { font-size: 15px; }
.pause-badge { padding: 3px 7px; border-radius: 999px; color: var(--danger); background: color-mix(in srgb, var(--danger) 12%, var(--panel)); font-size: 11px; }
.phases { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: 4px; min-width: 0; list-style: none; padding: 0; margin: 0; }
.phases li { display: flex; align-items: center; gap: 5px; padding: 6px 8px; border-radius: 9px; color: var(--muted); font-size: 11px; white-space: nowrap; }
.phases li span { display: grid; place-items: center; width: 20px; height: 20px; flex-shrink: 0; border: 1px solid var(--line); border-radius: 50%; font-size: 10px; }
.phases li.current { color: var(--text); background: var(--accent-soft); font-weight: 700; }
.phases li.current span { color: white; background: var(--accent); border-color: var(--accent); }
.phases li.complete span { color: var(--accent); border-color: var(--accent); }
.workflow-lower { min-height: 33px; border-top: 1px solid var(--line); margin-top: 8px; padding-top: 7px; }
.next-action { min-width: 0; margin: 0; color: var(--muted); font-size: 12px; }
.task-tabs { flex-wrap: wrap; justify-content: flex-end; }
.task-tabs button, .workflow-actions button, .tray-header button { display: inline-flex; align-items: center; justify-content: center; gap: 5px; padding: 5px 9px; border: 1px solid transparent; border-radius: 8px; background: transparent; cursor: pointer; font-size: 12px; white-space: nowrap; }
.task-tabs button:hover, .task-tabs button.selected, .workflow-actions button:hover, .tray-header button:hover { background: var(--accent-soft); }
.task-tabs button.selected { color: var(--accent); font-weight: 700; }
.workflow-actions button:first-child { border-color: var(--line); }
.tab-count { min-width: 18px; padding: 1px 4px; border-radius: 6px; background: var(--page); font-size: 10px; }
.unread-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); }
.workflow-error { margin: 8px 0 0; color: var(--danger); font-size: 12px; }
.step-picker { display: flex; align-items: center; gap: 9px; margin-top: 8px; color: var(--muted); font-size: 12px; }
.step-picker select { flex: 1; min-width: 0; }
.task-tray { display: flex; flex-direction: column; height: clamp(230px, 33vh, 370px); min-height: 190px; max-height: 70vh; overflow: auto; resize: vertical; margin-top: 9px; border: 1px solid var(--line); border-radius: 12px; background: var(--page); }
.task-tray.expanded { height: min(66vh, 700px); }
.tray-header { position: sticky; z-index: 1; top: 0; flex: 0 0 auto; padding: 8px 12px; border-bottom: 1px solid var(--line); background: var(--page); font-size: 13px; }
.tray-content { flex: 1 1 auto; min-height: 0; padding: 12px; overflow-y: auto; }
.proposal-editor { display: flex; flex-direction: column; gap: 8px; }
.proposal-editor > p, .artifact-list > p, .event-list > p { margin: 0; color: var(--muted); font-size: 11px; }
.proposal-editor label { display: flex; flex-direction: column; flex: 1 1 auto; min-height: 100px; gap: 5px; font-size: 12px; font-weight: 600; }
.proposal-editor textarea { flex: 1 1 auto; min-height: 100px; width: 100%; resize: vertical; line-height: 1.55; }
.proposal-editor .method-picker { flex: 0 0 auto; min-height: 0; }
.proposal-editor select, .step-picker select, .proposal-editor textarea { padding: 9px 11px; border: 1px solid var(--line); border-radius: 9px; color: var(--text); background: var(--panel); font: inherit; }
.tray-footer { justify-content: flex-end; flex: 0 0 auto; }
.tray-footer span { color: var(--muted); font-size: 11px; }
.confirm { padding: 9px 13px; border: 0; border-radius: 9px; color: white; background: var(--accent); cursor: pointer; font-size: 12px; font-weight: 700; }
button:disabled, select:disabled, textarea:disabled { opacity: .55; cursor: default; }
.artifact-list { display: grid; align-content: start; gap: 8px; }
.artifact-card { border: 1px solid var(--line); border-radius: 10px; background: var(--panel); }
.artifact-card summary { display: flex; align-items: center; gap: 10px; padding: 9px 11px; cursor: pointer; list-style: none; }
.artifact-card summary::-webkit-details-marker { display: none; }
.artifact-card summary > span:nth-child(2) { display: flex; flex-direction: column; flex: 1; min-width: 0; }
.artifact-card small { color: var(--muted); font-size: 10px; }
.artifact-icon { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 8px; color: var(--accent); background: var(--accent-soft); }
.artifact-card ol, .artifact-card pre { margin: 0; padding: 10px 18px 14px 32px; border-top: 1px solid var(--line); font-size: 12px; line-height: 1.6; }
.artifact-card pre { padding-left: 13px; white-space: pre-wrap; overflow-wrap: anywhere; }
.artifact-card > small { display: block; padding: 0 12px 12px; }
.event-list ol { margin: 0; padding-left: 21px; }
.event-list li { padding: 5px 0; font-size: 12px; }
.event-list li span, .event-list li small { display: block; color: var(--muted); font-size: 11px; }
@container (max-width: 760px) { .workflow-top { flex-wrap: wrap; } .phases { order: 3; width: 100%; justify-content: flex-start; } }
@media (max-width: 760px) {
  .workflow { padding: 9px 11px; }
  .workflow-lower { align-items: flex-start; flex-direction: column; }
  .phases { flex-wrap: nowrap; justify-content: space-between; gap: 1px; }
  .phases li { flex-direction: column; gap: 2px; padding: 4px 2px; font-size: 9px; }
  .task-tabs { justify-content: flex-start; }
  .step-picker { align-items: stretch; flex-direction: column; gap: 4px; }
  .task-tray { height: min(35vh, 330px); min-height: 190px; }
}
</style>
