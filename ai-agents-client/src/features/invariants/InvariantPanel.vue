<script setup>
import { computed, ref, watch } from 'vue'
const props = defineProps({ workspace: Object, state: Object, taskId: String, busy: Boolean, error: String })
const emit = defineEmits(['save'])
const kinds = { language: 'Язык кода', signature: 'Сигнатура Python', allowed_imports: 'Разрешённые импорты', semantic: 'Смысловое правило' }
const verdicts = { pass: 'Пройдено', conflict: 'Конфликт', uncertain: 'Не удалось подтвердить' }
const stages = { input: 'Запрос', output: 'Ответ', artifact: 'Артефакт' }
const drafts = ref([])
watch([() => props.taskId, () => props.workspace?.revision], () => {
  drafts.value = (props.workspace?.rules || []).map(({ id, kind, label, value, active }) => ({ id, kind, label, value, active }))
}, { immediate: true })
const disabled = computed(() => props.busy || !props.workspace || props.state?.phase === 'done')
const valid = computed(() => drafts.value.every(r => r.label.trim() && r.value.trim()))
function example() {
  drafts.value = [
    { kind: 'language', label: 'Только Python', value: 'python', active: true },
    { kind: 'signature', label: 'Интерфейс функции', value: 'two_sum(nums, target)', active: true },
    { kind: 'allowed_imports', label: 'Без импортов', value: '-', active: true },
    { kind: 'semantic', label: 'Не менять вход', value: 'Решение не должно изменять исходный список nums.', active: true },
  ]
}
function ruleLabel(check, id) { return check.rules.find(r => r.id === id)?.label || id }
</script>

<template>
  <section class="invariant-panel sidebar-section">
    <p class="section-title">Обязательные правила · День 14</p>
    <p v-if="error" role="alert" class="token-warning">{{ error }}</p>
    <template v-if="workspace">
      <p>Версия {{ workspace.revision }} · активных правил: {{ workspace.rules.filter(r => r.active).length }}</p>
      <ul v-if="workspace.rules.some(r => r.active)"><li v-for="rule in workspace.rules.filter(r => r.active)" :key="rule.id"><strong>{{ rule.label }}</strong>: {{ rule.value }}</li></ul>
      <p v-else>Пользовательские правила не включены. Обязательная проверка этапа Дня 15 остаётся активной.</p>
      <small>Системное правило «Допустимое действие этапа» нельзя отключить. Оно проверяется тем же judge вместе с правилами задачи, без отдельной цепочки вызовов.</small>
      <small>Проверяем запрос, ответ до публикации и сохраняемый артефакт. Judge настроен отдельно на сервере (по умолчанию DeepSeek Pro). Это дополнительные платные вызовы. Неуверенная проверка блокирует результат; анализ не гарантирует корректность и не запускает код.</small>
      <details><summary>Настроить правила</summary>
        <p>Сохранение вернёт задачу к планированию. Старые артефакты останутся в истории, но не попадут в новый контекст. Пауза сохранится.</p>
        <button :disabled="disabled" @click="example">Черновик правил Two Sum</button>
        <article v-for="(rule, index) in drafts" :key="index">
          <label>Тип<select v-model="rule.kind" :disabled="disabled"><option v-for="(label, kind) in kinds" :key="kind" :value="kind">{{ label }}</option></select></label>
          <label>Название<input v-model="rule.label" maxlength="120" :disabled="disabled" /></label>
          <label>Правило<textarea v-model="rule.value" maxlength="2000" rows="2" :disabled="disabled"></textarea></label>
          <small v-if="rule.kind === 'language'">Поддерживается python.</small>
          <small v-if="rule.kind === 'signature'">Например: two_sum(nums, target), без def и тела функции.</small>
          <small v-if="rule.kind === 'allowed_imports'">Корневые модули стандартной библиотеки через запятую, например math, collections. Знак «-» запрещает импорты.</small>
          <label class="inline"><input v-model="rule.active" type="checkbox" :disabled="disabled" /> Включено</label>
          <button :disabled="disabled" @click="drafts.splice(index, 1)">Убрать из черновика</button>
        </article>
        <div class="actions"><button :disabled="disabled || drafts.length >= 20" @click="drafts.push({ kind: 'semantic', label: '', value: '', active: true })">Добавить правило</button>
          <button :disabled="disabled || !valid" @click="emit('save', drafts)">Сохранить правила и перепланировать</button></div>
        <small>Изменения выше — черновик до нажатия «Сохранить». Удаление всех правил отключает проверки.</small>
      </details>
      <details :open="workspace.checks.some(c => c.verdict !== 'pass')"><summary>Проверки · {{ workspace.checks.length }} последних</summary>
        <p v-if="!workspace.checks.length">Проверок ещё нет.</p>
        <article v-for="check in [...workspace.checks].reverse()" :key="check.id">
          <strong>{{ stages[check.stage] }} · {{ verdicts[check.verdict] }}</strong>
          <small>Правила v{{ check.revision }} · {{ check.created_at }}</small>
          <ul><li v-for="item in check.checks" :key="item.rule_id"><strong>{{ ruleLabel(check, item.rule_id) }}</strong> — {{ verdicts[item.verdict] }}: {{ item.reason }}<small>ID: {{ item.rule_id }}</small></li></ul>
          <small v-if="check.error_code">Ошибка проверки: {{ check.error_code }}. Кандидат не опубликован.</small>
          <small v-else>{{ check.run_id ? 'LLM judge; расход включён в статистику.' : 'Локальная проверка, без LLM-вызова.' }}</small>
        </article>
      </details>
    </template>
    <p v-else>Правила загружаются…</p>
  </section>
</template>

<style scoped>
.invariant-panel { font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
label { display: grid; gap: 5px; margin: 8px 0; }
input, select, textarea { width: 100%; min-width: 0; font: inherit; color: var(--text); background: var(--panel); border: 1px solid var(--line); border-radius: 8px; padding: 8px; }
.inline { display: flex; align-items: center; } input[type=checkbox] { width: auto; }
button { font: inherit; padding: 7px 9px; border-radius: 8px; border: 1px solid var(--line); background: var(--panel); color: var(--text); cursor: pointer; }
button:disabled { opacity: .5; cursor: default; }
.actions { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
small { display: block; color: var(--muted); margin: 6px 0; }
details { border: 1px solid var(--line); border-radius: 8px; padding: 9px; margin: 9px 0; }
summary { cursor: pointer; font-weight: 600; } ul { padding-left: 18px; }
article { border-top: 1px solid var(--line); margin-top: 10px; padding-top: 10px; }
</style>
