<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({ workspace: Object, conversationId: String, workflowState: Object,
  busy: Boolean, sending: Boolean, error: String })
const emit = defineEmits(['save', 'refresh'])
const open = ref(false)
const draft = ref([])
const editingPhase = computed(() => props.workflowState?.phase === 'planning'
  && props.workflowState?.status === 'active')
const editable = computed(() => editingPhase.value && !props.busy && !props.sending)
const activeCount = computed(() => props.workspace?.rules.filter(rule => rule.active).length || 0)
const latest = computed(() => [...(props.workspace?.checks || [])].reverse().slice(0, 3))
const currentCheck = computed(() => latest.value.find(check => check.revision === props.workspace?.revision))
const statuses = { pass: 'Соблюдено', conflict: 'Конфликт', uncertain: 'Не проверено' }
const stages = { input: 'Запрос', output: 'Ответ', artifact: 'Подтверждение' }

watch(() => props.conversationId, () => {
  open.value = false
}, { immediate: true })
watch(() => [props.conversationId, props.workspace?.revision], () => {
  draft.value = (props.workspace?.rules || []).map(rule => ({ ...rule }))
}, { immediate: true })

function add() { draft.value.push({ id: null, kind: 'semantic', label: '', value: '', active: true }) }
function save() {
  if (!editable.value || draft.value.some(rule => !rule.label.trim() || !rule.value.trim())) return
  emit('save', draft.value.map(({ id, kind, label, value, active }) => ({ id, kind, label, value, active })))
}
</script>

<template>
  <section v-if="workspace" class="invariant-panel" aria-label="Обязательные правила задачи">
    <div class="invariant-bar">
      <div><strong>Обязательные правила</strong><span>{{ activeCount ? `${activeCount} активно` : 'Пока не заданы' }}</span>
        <span v-if="currentCheck" :class="['verdict', currentCheck.verdict]">{{ statuses[currentCheck.verdict] }}</span></div>
      <button :aria-expanded="open" @click="open = !open">{{ open ? 'Свернуть' : 'Настроить и проверить' }}</button>
    </div>
    <p v-if="error" class="invariant-error" role="alert">{{ error }}</p>
    <div v-if="open" class="invariant-body">
      <p>Эти правила относятся только к текущей задаче. Агент проверяет запрос и ответ отдельным judge на DeepSeek Pro — это дополнительные платные API-вызовы.</p>
      <p v-if="!editingPhase" class="invariant-notice">Изменять правила можно только во время активного планирования, до принятия плана.</p>
      <div v-for="(rule, index) in draft" :key="rule.id || `new-${index}`" class="invariant-rule">
        <div class="rule-top"><strong>Правило {{ index + 1 }}</strong>
          <label><input v-model="rule.active" type="checkbox" :disabled="!editable"> Активно</label>
          <button :disabled="!editable" :aria-label="`Удалить правило ${index + 1}`" @click="draft.splice(index, 1)">Удалить</button></div>
        <div class="rule-fields"><label>Тип<select v-model="rule.kind" :disabled="!editable">
          <option value="semantic">Обязательное условие</option><option value="language">Язык кода</option>
        </select></label><label>Название<input v-model="rule.label" maxlength="120" :disabled="!editable" placeholder="Например, Язык решения"></label></div>
        <label>Требование<textarea v-model="rule.value" maxlength="2000" :disabled="!editable"
          :placeholder="rule.kind === 'language' ? 'Например, Python' : 'Например, не использовать сортировку; сложность O(n)'" /></label>
      </div>
      <div class="rule-actions"><button :disabled="!editable || draft.length >= 20" @click="add">＋ Добавить правило</button>
        <button class="save-rules" :disabled="!editable || draft.some(rule => !rule.label.trim() || !rule.value.trim())"
          @click="save">Сохранить правила</button><button :disabled="busy" @click="emit('refresh')">Обновить</button></div>
      <div class="invariant-checks"><strong>Последние проверки</strong>
        <p v-if="!latest.length">Проверок пока нет. Они появятся после сообщения или подтверждения результата.</p>
        <details v-for="check in latest" :key="check.id"><summary><span>{{ stages[check.stage] }} · версия {{ check.revision }}</span>
          <span :class="['verdict', check.verdict]">{{ statuses[check.verdict] }}</span><small>{{ check.created_at }}</small></summary>
          <ul><li v-for="item in check.checks" :key="item.rule_id">{{ check.rules.find(rule => rule.id === item.rule_id)?.label }}:
            {{ item.reason }}</li></ul></details>
      </div>
    </div>
  </section>
</template>

<style scoped>
.invariant-panel { flex: 0 0 auto; min-width: 0; padding: 8px 20px; border-bottom: 1px solid var(--line); background: var(--panel); }
.invariant-bar, .invariant-bar > div, .rule-top, .rule-actions { display: flex; align-items: center; gap: 9px; }
.invariant-bar { justify-content: space-between; }
.invariant-bar > div { flex-wrap: wrap; }
.invariant-bar strong { font-size: 12px; }
.invariant-bar span, .invariant-body p { color: var(--muted); font-size: 11px; }
.invariant-bar button, .rule-actions button, .rule-top button { padding: 6px 9px; border: 1px solid var(--line); border-radius: 8px; color: var(--text); background: var(--page); cursor: pointer; font-size: 11px; }
.invariant-bar button:hover, .rule-actions button:hover { background: var(--accent-soft); }
.invariant-body { max-height: min(60vh, 650px); padding: 9px 0 5px; overflow-y: auto; }
.invariant-body > p { margin: 0 0 8px; }
.invariant-body .invariant-notice { color: var(--danger); }
.invariant-rule { display: grid; gap: 8px; margin: 8px 0; padding: 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--page); }
.invariant-rule label { display: flex; flex-direction: column; gap: 4px; color: var(--muted); font-size: 11px; }
.rule-top strong { flex: 1; font-size: 12px; }
.rule-top label { flex-direction: row; align-items: center; }
.rule-fields { display: grid; grid-template-columns: minmax(120px, 1fr) minmax(150px, 2fr); gap: 8px; }
.invariant-rule input:not([type='checkbox']), .invariant-rule select, .invariant-rule textarea { width: 100%; padding: 7px 9px; border: 1px solid var(--line); border-radius: 8px; color: var(--text); background: var(--panel); font: inherit; }
.invariant-rule textarea { min-height: 55px; resize: vertical; }
.rule-actions { flex-wrap: wrap; justify-content: flex-end; }
.rule-actions .save-rules { color: white; border-color: var(--accent); background: var(--accent); }
.invariant-checks { display: grid; gap: 6px; margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--line); font-size: 12px; }
.invariant-checks p { margin: 0; }
.invariant-checks details { padding: 7px 9px; border: 1px solid var(--line); border-radius: 8px; }
.invariant-checks summary { display: flex; align-items: center; gap: 9px; cursor: pointer; }
.invariant-checks summary small { margin-left: auto; color: var(--muted); }
.invariant-checks ul { margin: 7px 0 0; padding-left: 18px; line-height: 1.5; }
.verdict { padding: 2px 5px; border-radius: 6px; font-size: 10px; }
.verdict.pass { color: var(--accent); background: var(--accent-soft); }
.verdict.conflict, .verdict.uncertain { color: var(--danger); background: color-mix(in srgb, var(--danger) 10%, var(--panel)); }
.invariant-error { margin: 7px 0 0; color: var(--danger); font-size: 11px; }
button:disabled, input:disabled, select:disabled, textarea:disabled { opacity: .55; cursor: default; }
@media (max-width: 760px) { .invariant-panel { padding: 8px 11px; } .rule-fields { grid-template-columns: 1fr; } }
</style>
