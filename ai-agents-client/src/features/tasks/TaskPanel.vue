<script setup>
import { computed, ref, watch } from 'vue'
const props = defineProps({ workspace: Object, busy: Boolean, artifactBusy: Boolean, sending: Boolean, error: String, taskRevision: Number, invariantRevision: Number })
const emit = defineEmits(['transition', 'save', 'step', 'refresh'])
const phases = { planning: 'Планирование', execution: 'Реализация', validation: 'Проверка', done: 'Завершено' }
const actions = { save_plan: 'Сохраните план решения', start_execution: 'Перейдите к реализации', work_on_step: 'Продолжите выбранный шаг', save_solution: 'Сохраните решение', start_validation: 'Перейдите к проверке решения', review_solution: 'Проверьте решение и сохраните отчёт', finish: 'Просмотрите отчёт и завершите задачу', completed: 'Задача завершена' }
const events = { start_execution: 'К реализации', start_validation: 'К проверке', finish: 'Завершить', request_changes: 'На доработку', request_replan: 'Перепланировать', pause: 'Пауза', resume: 'Продолжить' }
const state = computed(() => props.workspace?.state)
const latest = computed(() => Object.fromEntries((props.workspace?.artifacts || []).filter(a => !props.invariantRevision || (a.invariant_revision ?? 1) === props.invariantRevision).map(a => [a.kind, a])))
const kind = computed(() => ({ planning: 'plan', execution: 'solution', validation: 'validation' })[state.value?.phase])
const draft = ref(''), method = ref('llm_review')
watch(() => [props.workspace?.task_id, state.value?.phase], () => { draft.value = ''; method.value = 'llm_review' })
const disabled = computed(() => props.busy || props.artifactBusy || props.sending || state.value?.status === 'paused' || Boolean(props.workspace?.active_command_id))
const planLines = computed(() => draft.value.split('\n').map(s => s.trim()).filter(Boolean))
const valid = computed(() => draft.value.trim() && (kind.value !== 'plan' || planLines.value.length <= 30))
function save() {
  if (!valid.value || disabled.value) return
  const content = kind.value === 'plan' ? { steps: planLines.value.map(title => ({ title })) }
    : { text: draft.value.trim(), ...(kind.value === 'validation' ? { method: method.value } : {}) }
  emit('save', { kind: kind.value, content })
}
</script>

<template>
  <section class="task-panel sidebar-section">
    <p class="section-title">Задача · День 13</p>
    <p v-if="error" role="alert" class="token-warning">{{ error }}</p>
    <template v-if="state">
      <ol class="phases" aria-label="Этапы задачи"><li v-for="(label, phase) in phases" :key="phase" :aria-current="state.phase === phase ? 'step' : undefined">{{ label }}</li></ol>
      <p><strong>{{ state.status === 'paused' ? 'На паузе' : phases[state.phase] }}</strong> · версия {{ state.revision }}</p>
      <p>{{ state.status === 'paused' ? 'После продолжения: ' : 'Следующее действие: ' }}{{ actions[state.expected_action] || state.expected_action }}</p>
      <label v-if="latest.plan">Текущий шаг плана v{{ latest.plan.revision }}
        <select :value="state.current_step_id" :disabled="disabled || state.phase === 'done'" @change="emit('step', $event.target.value)">
          <option v-for="step in latest.plan.content.steps" :key="step.id" :value="step.id">{{ step.title }}</option>
        </select>
      </label>
      <div class="task-actions"><button v-for="event in workspace.allowed_events" :key="event"
        :disabled="busy || ((sending || artifactBusy || workspace.active_command_id) && !['pause', 'resume'].includes(event))"
        @click="emit('transition', event)">{{ events[event] }}</button></div>
      <small v-if="sending || workspace.active_command_id">Запрос выполняется. Пауза сохранится сразу; поздний ответ не применится, но API может списать токены.</small>
      <small>Этапы меняются кнопками, не текстом чата. День 13 проверяет порядок, но ещё не требует утверждения плана и принятия проверки — это День 15.</small>
      <details v-if="kind"><summary>Сохранить {{ { plan: 'план', solution: 'решение', validation: 'отчёт проверки' }[kind] }}</summary>
        <p>Ответ модели не становится артефактом автоматически. Скопируйте нужный результат сюда и сохраните явно.</p>
        <label>{{ kind === 'plan' ? 'Шаги плана: один на строку (до 30)' : 'Текст артефакта' }}
          <textarea v-model="draft" rows="5" maxlength="30000" :disabled="disabled"></textarea></label>
        <label v-if="kind === 'validation'">Источник проверки<select v-model="method" :disabled="disabled"><option value="llm_review">Анализ LLM — код не запускался</option><option value="user_test_result">Результат моего запуска</option></select></label>
        <button :disabled="disabled || !valid" @click="save">Сохранить новую версию</button>
        <small v-if="kind === 'plan'">Новая версия из формы создаёт новые шаги. Старые версии остаются в истории.</small>
      </details>
      <details><summary>Артефакты · {{ workspace.artifacts.length }} версий</summary>
        <article v-for="item in [...workspace.artifacts].reverse()" :key="item.id">
          <strong>{{ { plan: 'План', solution: 'Решение', validation: 'Проверка' }[item.kind] }} v{{ item.revision }}</strong>
          <small>Условие v{{ item.task_revision }} · план v{{ item.plan_revision ?? '—' }} · решение v{{ item.solution_revision ?? '—' }}</small>
          <p v-if="item.task_revision !== taskRevision" class="token-warning">Рабочая память менялась после сохранения. Проверьте актуальность.</p>
          <p v-if="invariantRevision && (item.invariant_revision ?? 1) !== invariantRevision" class="token-warning">Правила изменились. Артефакт исключён из контекста; сохраните новую проверенную версию.</p>
          <ol v-if="item.kind === 'plan'"><li v-for="step in item.content.steps" :key="step.id">{{ step.title }}</li></ol>
          <pre v-else>{{ item.content.text }}</pre>
          <small v-if="item.content.method">{{ item.content.method === 'llm_review' ? 'Анализ LLM, не запуск кода' : 'Результат запуска со слов пользователя' }}</small>
        </article>
      </details>
      <details><summary>Журнал · {{ workspace.events.length }} событий</summary><ol><li v-for="(item, i) in workspace.events" :key="i">{{ events[item.event] || item.event }} · {{ item.after.phase }} / {{ item.after.status }} · v{{ item.after.revision }}<small>{{ item.created_at }}</small></li></ol></details>
    </template>
    <p v-else>Создайте задачу или дождитесь загрузки.</p>
    <button :disabled="busy" @click="emit('refresh')">Обновить задачу</button>
  </section>
</template>

<style scoped>
.task-panel { font-size: 12px; line-height: 1.6; }
.phases { display: grid; grid-template-columns: 1fr 1fr; gap: 4px; list-style: none; padding: 0; }
.phases li { border: 1px solid var(--line); padding: 5px; border-radius: 6px; color: var(--muted); }
.phases [aria-current] { color: var(--text); border-color: var(--accent); font-weight: 600; }
label { display: grid; gap: 5px; margin: 8px 0; }
select, textarea { width: 100%; min-width: 0; font: inherit; color: var(--text); background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 8px; }
button { font: inherit; padding: 7px 9px; border-radius: 8px; border: 1px solid var(--line); background: var(--panel); color: var(--text); cursor: pointer; }
button:disabled { opacity: .5; cursor: default; }
.task-actions { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0; }
small { display: block; color: var(--muted); margin: 6px 0; }
details { border: 1px solid var(--line); border-radius: 8px; padding: 9px; margin: 9px 0; }
summary { cursor: pointer; font-weight: 600; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 11px; }
article + article { border-top: 1px solid var(--line); margin-top: 10px; padding-top: 10px; }
</style>
