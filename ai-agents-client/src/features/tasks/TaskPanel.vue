<script setup>
import { computed, ref, watch } from 'vue'
const props = defineProps({ workspace: Object, busy: Boolean, artifactBusy: Boolean, sending: Boolean, error: String, taskRevision: Number, invariantRevision: Number })
const emit = defineEmits(['transition', 'save', 'step', 'refresh'])
const phases = { planning: 'Планирование', execution: 'Реализация', validation: 'Проверка', done: 'Завершено' }
const actions = { save_plan: 'Сохраните план решения', approve_plan: 'Утвердите текущую версию плана', work_on_step: 'Продолжите выбранный шаг', save_solution: 'Сохраните решение', submit_solution: 'Передайте текущую версию решения на проверку', review_solution: 'Проверьте решение и сохраните отчёт', accept_validation: 'Примите отчёт текущего решения и завершите задачу', completed: 'Задача завершена' }
const events = { approve_plan: 'Утвердить план → реализация', submit_solution: 'Передать решение → проверка', accept_validation: 'Принять проверку → завершить', request_changes: 'На доработку', request_replan: 'Перепланировать', pause: 'Пауза', resume: 'Продолжить' }
const state = computed(() => props.workspace?.state)
const latest = computed(() => {
  const items = Object.fromEntries((props.workspace?.artifacts || []).filter(a => (a.checked_workflow_version ?? 14) >= 15 && (!props.taskRevision || a.task_revision === props.taskRevision) && (!props.invariantRevision || (a.invariant_revision ?? 1) === props.invariantRevision)).map(a => [a.kind, a]))
  if (items.plan?.revision <= (state.value?.plan_floor_revision || 0)) delete items.plan
  if (items.solution && (items.solution.revision <= (state.value?.solution_floor_revision || 0) || items.solution.plan_revision !== state.value?.approved_plan_revision)) delete items.solution
  if (items.validation && items.validation.solution_revision !== items.solution?.revision) delete items.validation
  return items
})
const kind = computed(() => ({ planning: 'plan', execution: 'solution', validation: 'validation' })[state.value?.phase])
const draft = ref(''), method = ref('llm_review')
const issues = ref(''), reviewed = ref(false), remarks = ref('')
watch([() => props.workspace?.task_id, () => state.value?.phase], () => { draft.value = ''; method.value = 'llm_review'; issues.value = ''; reviewed.value = false; remarks.value = '' })
const disabled = computed(() => props.busy || props.artifactBusy || props.sending || state.value?.status === 'paused' || Boolean(props.workspace?.active_command_id))
const planLines = computed(() => draft.value.split('\n').map(s => s.trim()).filter(Boolean))
const valid = computed(() => draft.value.trim() && (kind.value !== 'plan' || planLines.value.length <= 30) && (kind.value !== 'validation' || reviewed.value))
const targetKind = { approve_plan: 'plan', submit_solution: 'solution', accept_validation: 'validation' }
function transition(event) {
  if (targetKind[event]) emit('transition', { event, artifact_id: latest.value[targetKind[event]]?.id })
  else if (event === 'request_changes') emit('transition', { event, remarks: remarks.value.trim(), step_id: state.value.current_step_id })
  else emit('transition', event)
}
function save() {
  if (!valid.value || disabled.value) return
  const content = kind.value === 'plan' ? { steps: planLines.value.map(title => ({ title })) }
    : { text: draft.value.trim(), ...(kind.value === 'validation' ? { method: method.value, blocking_issues: issues.value.split('\n').map(s => s.trim()).filter(Boolean) } : {}) }
  emit('save', { kind: kind.value, content })
}
</script>

<template>
  <section class="task-panel sidebar-section">
    <p class="section-title">Жизненный цикл · День 15</p>
    <p v-if="error" role="alert" class="token-warning">{{ error }}</p>
    <template v-if="state">
      <ol class="phases" aria-label="Этапы задачи"><li v-for="(label, phase) in phases" :key="phase" :aria-current="state.phase === phase ? 'step' : undefined">{{ label }}</li></ol>
      <p><strong>{{ state.status === 'paused' ? 'На паузе' : phases[state.phase] }}</strong> · версия {{ state.revision }}</p>
      <p>{{ state.status === 'paused' ? 'После продолжения: ' : 'Следующее действие: ' }}{{ actions[state.expected_action] || state.expected_action }}</p>
      <p v-if="state.approved_plan_id">Утверждён план v{{ state.approved_plan_revision }} · условие v{{ state.approved_task_revision }} · правила v{{ state.approved_invariant_revision }}</p>
      <p v-if="state.submitted_solution_id">На проверке решение v{{ state.submitted_solution_revision }}</p>
      <p v-if="state.accepted_validation_id">Принят отчёт {{ state.accepted_validation_id }} для решения v{{ state.validated_solution_revision }}</p>
      <p v-if="state.change_request">Замечания: {{ state.change_request }}</p>
      <p v-if="['execution', 'validation'].includes(state.phase) && !state.approved_plan_id" class="token-warning">У старой задачи нет подтверждения Дня 15. Для продолжения выберите «Перепланировать».</p>
      <label v-if="latest.plan">Текущий шаг плана v{{ latest.plan.revision }}
        <select :value="state.current_step_id" :disabled="disabled || state.phase === 'done'" @change="emit('step', $event.target.value)">
          <option v-for="step in latest.plan.content.steps" :key="step.id" :value="step.id">{{ step.title }}</option>
        </select>
      </label>
      <label v-if="state.phase === 'validation'">Замечания для возврата на доработку<textarea v-model="remarks" rows="2" maxlength="30000" :disabled="disabled"></textarea></label>
      <div class="task-actions"><button v-for="event in workspace.allowed_events" :key="event"
        :disabled="busy || ((sending || artifactBusy || workspace.active_command_id) && !['pause', 'resume'].includes(event)) || (event === 'request_changes' && (!remarks.trim() || !state.current_step_id))"
        @click="transition(event)">{{ events[event] }}{{ targetKind[event] && latest[targetKind[event]] ? ' · v' + latest[targetKind[event]].revision : '' }}</button></div>
      <p v-for="(reason, event) in workspace.blocked_events" :key="event" class="token-warning">{{ events[event] }}: {{ reason }}</p>
      <small v-if="sending || workspace.active_command_id">Запрос выполняется. Пауза сохранится сразу; поздний ответ не применится, но API может списать токены.</small>
      <small>Кнопка утверждает указанную версию, не весь чат. Код доступен после утверждения плана; завершение — после принятия отчёта без блокеров. Анализ LLM не заменяет запуск тестов. Перепланирование требует нового плана.</small>
      <details v-if="kind"><summary>Сохранить {{ { plan: 'план', solution: 'решение', validation: 'отчёт проверки' }[kind] }}</summary>
        <p>Ответ модели не становится артефактом автоматически. Скопируйте нужный результат сюда и сохраните явно.</p>
        <label>{{ kind === 'plan' ? 'Шаги плана: один на строку (до 30)' : 'Текст артефакта' }}
          <textarea v-model="draft" rows="5" maxlength="30000" :disabled="disabled"></textarea></label>
        <label v-if="kind === 'validation'">Источник проверки<select v-model="method" :disabled="disabled"><option value="llm_review">Анализ LLM — код не запускался</option><option value="user_test_result">Результат моего запуска</option></select></label>
        <template v-if="kind === 'validation'">
          <label>Блокирующие замечания — по одному на строку<textarea v-model="issues" rows="3" maxlength="30000" :disabled="disabled"></textarea></label>
          <label><span><input v-model="reviewed" type="checkbox" :disabled="disabled" /> Я проверил список блокеров; пустой список означает, что блокеров нет</span></label>
        </template>
        <button :disabled="disabled || !valid" @click="save">Сохранить новую версию</button>
        <small v-if="kind === 'plan'">Новая версия из формы создаёт новые шаги. Старые версии остаются в истории.</small>
      </details>
      <details><summary>Артефакты · {{ workspace.artifacts.length }} версий</summary>
        <article v-for="item in [...workspace.artifacts].reverse()" :key="item.id">
          <strong>{{ { plan: 'План', solution: 'Решение', validation: 'Проверка' }[item.kind] }} v{{ item.revision }}</strong>
          <small>Условие v{{ item.task_revision }} · план v{{ item.plan_revision ?? '—' }} · решение v{{ item.solution_revision ?? '—' }}</small>
          <p v-if="latest[item.kind]?.id !== item.id" class="token-warning">Историческая версия — не участвует в текущем согласовании.</p>
          <p v-if="item.task_revision !== taskRevision" class="token-warning">Рабочая память менялась после сохранения. Проверьте актуальность.</p>
          <p v-if="invariantRevision && (item.invariant_revision ?? 1) !== invariantRevision" class="token-warning">Правила изменились. Артефакт исключён из контекста; сохраните новую проверенную версию.</p>
          <ol v-if="item.kind === 'plan'"><li v-for="step in item.content.steps" :key="step.id">{{ step.title }}</li></ol>
          <pre v-else>{{ item.content.text }}</pre>
          <small v-if="item.content.method">{{ item.content.method === 'llm_review' ? 'Анализ LLM, не запуск кода' : 'Результат запуска со слов пользователя' }}</small>
          <ul v-if="item.content.blocking_issues?.length"><li v-for="(issue, i) in item.content.blocking_issues" :key="i">Блокер: {{ issue }}</li></ul>
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
