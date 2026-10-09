<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { optimizationApi as api, metric, gib, score, comparisonWarnings, type BenchmarkCase, type Experiment, type ExperimentRequest, type Settings, type Summary } from './api'
import './optimization.css'
import { HttpFailure } from '../../api'
const emit = defineEmits<{ busy: [value: boolean] }>()
const settings = ref<Settings | null>(null), cases = ref<BenchmarkCase[]>([]), history = ref<Summary[]>([])
const current = ref<Experiment | null>(null), compared = ref<Experiment | null>(null)
const error = ref(''), transportError = ref(''), pending = ref(false), note = ref(''), savedNote = ref(false)
const caseMode = ref('calibration'), selectedItemId = ref(''), comparisonId = ref(''), submitted = ref<ExperimentRequest | null>(null)
const form = reactive({ provider: 'LOCAL' as 'LOCAL' | 'DEEPSEEK', cloudModel: 'deepseek-v4-pro', promptProfile: 'BASELINE' as 'BASELINE' | 'SPECIALIZED', skillEnabled: false,
  parameters: { temperature: .7, maxTokens: 1024, contextSize: 8192, thinking: false, topP: .8, topK: 20, seed: 42 }, caseIds: [] as string[], input: '', repetitions: 1 })
const running = computed(() => pending.value || current.value?.status === 'RUNNING')
const item = computed(() => current.value?.items.find(i => i.id === selectedItemId.value) ?? current.value?.items[0] ?? null)
const comparedItem = computed(() => compared.value?.items.find(i => i.caseId === item.value?.caseId && i.repetition === item.value?.repetition && i.input === item.value?.input) ?? null)
const visibleCases = computed(() => cases.value.filter(c => c.split === caseMode.value))
const canStart = computed(() => !running.value && !submitted.value && !!settings.value && (form.provider === 'LOCAL' ? settings.value.installed : settings.value.cloudReady)
  && (form.caseIds.length > 0 || form.input.trim().length > 0))
const warnings = computed(() => current.value && compared.value ? comparisonWarnings(current.value, compared.value) : [])
let timer: ReturnType<typeof setTimeout> | undefined, disposed = false
watch(running, value => { emit('busy', value); if (!value) syncResource() })
watch(() => current.value?.id, () => { note.value = current.value?.note ?? ''; savedNote.value = false; selectedItemId.value = current.value?.items[0]?.id ?? '' })
function message(e: unknown) { return e instanceof Error ? e.message : String(e) }
function runStamp(e: Summary) { return `${new Date(e.createdAt).toLocaleString('ru-RU')} · ${e.id.slice(0, 8)}` }
async function refresh() {
  try { [settings.value, cases.value, history.value] = await Promise.all([api.settings(), api.cases(), api.list()]); error.value = '' }
  catch (e) { error.value = message(e) }
}
function schedule() { clearTimeout(timer); if (!disposed && current.value?.status === 'RUNNING') timer = setTimeout(poll, 900) }
async function poll() {
  if (!current.value || disposed) return
  try { current.value = await api.get(current.value.id); transportError.value = ''; history.value = await api.list() }
  catch (e) { transportError.value = `Не удалось обновить статус: ${message(e)}. Генерация не повторяется.` }
  finally { schedule() }
}
async function open(id: string) {
  if (running.value) return
  try {
    current.value = await api.get(id)
    const r = current.value.request
    Object.assign(form, { provider: r.provider, cloudModel: r.cloudModel, promptProfile: r.promptProfile, skillEnabled: r.skillEnabled,
      parameters: { ...r.parameters }, caseIds: [...r.caseIds], input: r.input, repetitions: r.repetitions })
    caseMode.value = cases.value.find(c => c.id === r.caseIds[0])?.split ?? 'calibration'
    error.value = ''; transportError.value = ''; window.location.hash = `optimization/${id}`; schedule()
  }
  catch (e) { error.value = message(e) }
}
function preset(specialized: boolean) {
  form.promptProfile = specialized ? 'SPECIALIZED' : 'BASELINE'; form.skillEnabled = specialized
  form.parameters = { temperature: .7, maxTokens: specialized ? 512 : 1024, contextSize: specialized ? 6144 : 8192, thinking: false, topP: .8, topK: 20, seed: 42 }
}
function chooseAll() { form.caseIds = visibleCases.value.map(c => c.id); form.input = '' }
function selectCase(id: string, checked: boolean) { form.input = ''; form.caseIds = checked ? [...form.caseIds, id] : form.caseIds.filter(value => value !== id) }
function customInput() { form.caseIds = [] }
function newExperiment() { if (!running.value) { current.value = null; compared.value = null; comparisonId.value = ''; error.value = ''; window.location.hash = 'optimization' } }
async function reconcile() {
  if (!submitted.value) return
  try { current.value = await api.get(submitted.value.id); submitted.value = null; localStorage.removeItem('day29-receipt'); transportError.value = ''; window.location.hash = `optimization/${current.value.id}`; schedule() }
  catch (e) { transportError.value = `Исход отправки пока неизвестен. Проверить статус можно повторно без нового LLM-вызова: ${message(e)}` }
}
async function start() {
  if (!canStart.value) return
  const receipt: ExperimentRequest = { ...JSON.parse(JSON.stringify(form)), id: crypto.randomUUID() }
  try { localStorage.setItem('day29-receipt', JSON.stringify(receipt)) } catch { error.value = 'Не удалось сохранить квитанцию. Отправка не выполнена.'; return }
  submitted.value = receipt; pending.value = true; error.value = ''; transportError.value = ''
  try { current.value = await api.start(receipt); submitted.value = null; localStorage.removeItem('day29-receipt'); window.location.hash = `optimization/${current.value.id}`; history.value = await api.list(); schedule() }
  catch (e) {
    if (e instanceof HttpFailure && [400, 409, 422].includes(e.status)) { error.value = message(e); submitted.value = null; localStorage.removeItem('day29-receipt') }
    else { transportError.value = message(e); await reconcile() }
  }
  finally { pending.value = false }
}
async function retrySameReceipt() {
  if (!submitted.value || pending.value) return
  pending.value = true
  try { current.value = await api.start(submitted.value); submitted.value = null; localStorage.removeItem('day29-receipt'); transportError.value = ''; window.location.hash = `optimization/${current.value.id}`; schedule() }
  catch (e) {
    if (e instanceof HttpFailure && [400, 409, 422].includes(e.status)) { error.value = message(e); submitted.value = null; localStorage.removeItem('day29-receipt') }
    else transportError.value = message(e)
  }
  finally { pending.value = false }
}
async function compare() { try { compared.value = comparisonId.value ? await api.get(comparisonId.value) : null; error.value = '' } catch (e) { error.value = message(e) } }
async function saveNote() {
  if (!current.value || running.value) return
  try { current.value = await api.note(current.value.id, note.value); savedNote.value = true; error.value = '' } catch (e) { error.value = message(e) }
}
/** Own navigation already changed current; history/direct links only re-read a different UUID. */
function syncResource() {
  if (running.value || submitted.value) return
  const hash = window.location.hash
  if (hash.startsWith('#optimization/')) {
    try { const id = decodeURIComponent(hash.slice(14)); if (id && id !== current.value?.id) void open(id) }
    catch { error.value = 'Некорректная ссылка на эксперимент.' }
  } else if (hash === '#optimization' && current.value) newExperiment()
}
onMounted(async () => {
  window.addEventListener('hashchange', syncResource)
  await refresh()
  const saved = localStorage.getItem('day29-receipt')
  if (saved) { try { submitted.value = JSON.parse(saved); await reconcile() } catch { error.value = 'Не удалось прочитать квитанцию.' } }
  const id = window.location.hash.split('/')[1]
  if (!current.value && id) await open(id)
  if (!current.value && !submitted.value) { const active = history.value.find(e => e.status === 'RUNNING'); if (active) await open(active.id) }
})
onUnmounted(() => { disposed = true; clearTimeout(timer); window.removeEventListener('hashchange', syncResource); emit('busy', false) })
</script>
<template>
  <div class="opt-page">
    <aside class="opt-history">
      <div><span class="eyebrow">День 29</span><h2>Эксперименты</h2></div>
      <button :disabled="running" @click="newExperiment">+ Новый эксперимент</button>
      <div class="opt-history-list">
        <button v-for="e in history" :key="e.id" :class="{ selected: current?.id === e.id }" :disabled="running" @click="open(e.id)">
          <strong>{{ e.model }}</strong><span>{{ e.profile === 'BASELINE' ? 'Базовый' : 'Специализированный' }} · skill {{ e.skillEnabled ? '✓' : '—' }}</span><small>{{ e.status }} · {{ e.finished }}/{{ e.total }}</small>
          <small>{{ runStamp(e) }}</small>
        </button>
        <p v-if="!history.length" class="muted">Результаты появятся после запуска и сохранятся на backend.</p>
      </div>
      <p class="muted">LOCAL не обращается к облаку. Старые результаты доступны после смены модели.</p>
    </aside>
    <main class="opt-main">
      <header class="opt-heading"><div><span class="eyebrow">Одинаковая задача · разные настройки</span><h1>Лаборатория оптимизации</h1><p>Разбор технических обращений без RAG. Проверяем факты, а не красоту ответа.</p></div><button :disabled="running" @click="refresh">Обновить</button></header>
      <p v-if="error" class="notice error" role="alert">{{ error }}</p>
      <div v-if="transportError" class="notice error" role="alert">{{ transportError }}<div v-if="submitted"><button :disabled="pending" @click="reconcile">Проверить квитанцию</button><button :disabled="pending" @click="retrySameReceipt">Повторить отправку того же UUID</button></div></div>
      <form class="opt-composer" @submit.prevent="start">
        <fieldset :disabled="running || !!submitted">
          <div class="opt-presets"><button type="button" @click="preset(false)">Базовый профиль</button><button type="button" @click="preset(true)">Кандидат оптимизации</button><span>Кандидат — гипотеза, не гарантированное улучшение.</span></div>
          <label class="opt-input-label">Своё обращение<textarea v-model="form.input" maxlength="16000" placeholder="Введите обращение либо выберите тесты ниже…" @input="customInput" /></label>
          <details class="opt-case-picker" open><summary>Тестовый набор · выбрано {{ form.caseIds.length }}</summary>
            <div class="opt-case-toolbar"><label>Набор<select v-model="caseMode" @change="form.caseIds = []"><option value="calibration">Для настройки · 10</option><option value="holdout">Контрольный · 10</option></select></label><button type="button" @click="chooseAll">Выбрать весь набор</button><button type="button" @click="form.caseIds = []">Снять выбор</button></div>
            <div class="opt-cases"><label v-for="c in visibleCases" :key="c.id"><input type="checkbox" :checked="form.caseIds.includes(c.id)" @change="selectCase(c.id, ($event.target as HTMLInputElement).checked)">{{ c.title }}</label></div>
          </details>
          <div class="opt-submit"><span>{{ form.provider === 'LOCAL' ? settings?.localModel : form.cloudModel }} · {{ form.caseIds.length || 1 }} × {{ form.repetitions }} вызовов</span><button type="submit" class="primary" :disabled="!canStart">{{ running ? 'Выполняется…' : 'Запустить эксперимент' }}</button></div>
          <p v-if="form.provider === 'DEEPSEEK'" class="opt-cloud-note">Платный API: отправятся выбранные обращения или ваш текст и инструкции. Ключ остаётся на backend.</p>
        </fieldset>
      </form>
      <section v-if="current" class="opt-result">
        <header><div><h2>{{ current.model }}</h2><p>{{ current.status }} · {{ current.identity?.quantization ?? 'облачная модель' }} · {{ current.request.promptProfile }} · skill {{ current.skill ? 'включён' : 'выключен' }}</p></div><a class="text-button" :href="`/api/v1/optimization/experiments/${current.id}/report`">Скачать MD</a></header>
        <p class="muted">COMPLETED — завершение вызова, не правильность. Неудачные случаи учитываются в качестве; резюме проверяется человеком.</p>
        <label class="opt-comparison-label">Сравнить с сохранённым экспериментом<select v-model="comparisonId" aria-label="Сравнить с экспериментом" @change="compare"><option value="">Без сравнения</option><option v-for="e in history.filter(e => e.id !== current?.id)" :key="e.id" :value="e.id">{{ e.model }} · {{ e.profile }} · {{ runStamp(e) }}</option></select></label>
        <p v-for="warning in warnings" :key="warning" class="notice">{{ warning }}</p>
        <div class="opt-scoreboard"><article v-for="e in [current, compared].filter((e): e is Experiment => !!e)" :key="e.id"><h3>{{ e.model }}</h3><dl><dt>Завершено</dt><dd>{{ score(e).successful }}/{{ score(e).total }}</dd><dt>Точные карточки</dt><dd>{{ score(e).goldTotal ? `${score(e).exact}/${score(e).goldTotal}` : 'нет эталона' }}</dd><dt>Верные поля</dt><dd>{{ score(e).fieldTotal ? `${score(e).fields}/${score(e).fieldTotal}` : 'нет эталона' }}</dd><dt>Валидная схема</dt><dd>{{ score(e).schema }}/{{ score(e).total }}</dd><dt>Медиана успешных вызовов</dt><dd>{{ metric(score(e).medianMs) }} мс</dd><dt>Sampled max VRAM модели</dt><dd>{{ gib(score(e).maxVram) }}</dd></dl></article></div>
        <div class="opt-item-tabs"><button v-for="i in current.items" :key="i.id" :class="{ selected: item?.id === i.id }" @click="selectedItemId = i.id">{{ i.caseId ?? 'свой' }} · {{ i.repetition }} <span>{{ i.status === 'COMPLETED' ? i.evaluation?.exactFields === false ? '⚠' : '✓' : i.status === 'RUNNING' ? '…' : i.status === 'PENDING' ? '·' : '!' }}</span></button></div>
        <div v-if="item" class="opt-item-input"><h3>{{ item.title }}</h3><details><summary>Входное обращение · {{ item.input.length }} символов</summary><pre>{{ item.input }}</pre></details></div>
        <div class="opt-answer-grid" :class="{ comparing: !!compared }">
          <article v-for="(i, position) in [item, ...(compared ? [comparedItem] : [])]" :key="position" class="opt-answer">
            <template v-if="i"><h3>{{ position === 0 ? current.model : compared?.model }} · {{ i.status }}</h3><p v-if="i.error" class="notice error">{{ i.error }}</p><pre v-if="i.generation" class="opt-answer-text">{{ i.generation.answer }}</pre><p v-else class="muted">{{ i.status === 'RUNNING' ? 'Модель отвечает…' : 'Ответ ещё не получен.' }}</p>
              <p v-if="i.status === 'TRUNCATED' && !i.generation?.answer.trim()" class="notice">Лимит генерации или окно контекста закончились до итогового ответа. Токены и Thinking сохранены; увеличьте лимит либо отключите Thinking для нового запуска.</p>
              <details v-if="i.generation?.thinking" class="opt-thinking"><summary>Thinking модели до итогового ответа</summary><pre>{{ i.generation.thinking }}</pre></details>
              <div v-if="i.evaluation" class="opt-quality" :class="{ bad: !i.evaluation.validSchema || i.evaluation.exactFields === false }"><strong>JSON {{ i.evaluation.validJson ? '✓' : '✗' }} · схема {{ i.evaluation.validSchema ? '✓' : '✗' }} · поля {{ i.evaluation.totalFields ? `${i.evaluation.matchedFields}/${i.evaluation.totalFields}` : 'без эталона' }}</strong><p v-for="problem in i.evaluation.mismatches" :key="problem">{{ problem }}</p></div>
              <dl class="opt-metrics"><dt>Полное время</dt><dd>{{ metric(i.elapsedMilliseconds) }} мс</dd><dt>Загрузка модели</dt><dd>{{ metric(i.generation?.loadMilliseconds) }} мс</dd><dt>Токены вход / выход</dt><dd>{{ metric(i.generation?.inputTokens) }} / {{ metric(i.generation?.outputTokens) }}</dd><dt>Генерация</dt><dd>{{ metric(i.generation?.tokensPerSecond, 1) }} ток/с</dd><dt>Выделено runtime</dt><dd>{{ gib(i.resources?.peakAllocatedBytes) }}</dd><dt>Из них VRAM</dt><dd>{{ gib(i.resources?.peakVramBytes) }}</dd><dt>Размещение</dt><dd>{{ i.resources?.placement ?? 'неизвестно' }}</dd><dt>Фактическое окно</dt><dd>{{ metric(i.resources?.observedContextSize) }}</dd></dl>
              <details><summary>Эталон и точный промпт</summary><pre v-if="i.expected">{{ JSON.stringify(i.expected, null, 2) }}</pre><pre v-for="m in i.messages" :key="m.role">{{ m.role }}: {{ m.content }}</pre><p class="opt-hash">SHA256 {{ i.promptSha256 }}</p></details>
            </template><p v-else class="muted">В выбранном эксперименте нет такого же обращения и повтора.</p>
          </article>
        </div>
        <p class="muted">Память — sampled allocation модели из Ollama /api/ps, не RAM/RSS процесса. Ресурсы DeepSeek недоступны. Токенизаторы различаются.</p>
        <details><summary>Сохранённая конфигурация</summary><pre>{{ JSON.stringify(current.request, null, 2) }}</pre><p class="opt-hash">Model digest: {{ current.identity?.digest ?? 'не предоставлен облаком' }} · Ollama {{ current.runtimeVersion ?? '—' }}</p></details>
        <label class="opt-note">Мой вывод<textarea v-model="note" maxlength="3000" placeholder="Что улучшилось, что ухудшилось, какой профиль подходит задаче…" /></label><button :disabled="running" @click="saveNote">Сохранить вывод</button><span v-if="savedNote" class="muted"> Сохранено</span>
      </section>
    </main>
    <aside class="opt-settings"><h2>Настройки нового запуска</h2><fieldset :disabled="running || !!submitted">
      <label>Провайдер<select v-model="form.provider" aria-label="Провайдер"><option value="LOCAL">Локальная модель</option><option value="DEEPSEEK">DeepSeek API</option></select></label>
      <div v-if="form.provider === 'LOCAL'" class="opt-model"><small>Активная локальная модель</small><strong>{{ settings?.localModel ?? 'Загрузка…' }}</strong><p>{{ settings?.installed ? `${settings.identity?.quantization} · ${settings.identity?.parameterSize}` : 'Модель не установлена / runtime недоступен' }}</p><small>Модель выбирается при запуске. В памяти — одна модель.</small></div>
      <label v-else>Модель DeepSeek<select v-model="form.cloudModel" aria-label="Модель DeepSeek"><option v-for="name in settings?.cloudModels" :key="name">{{ name }}</option></select><small v-if="!settings?.cloudReady">Облако отключено или ключ не настроен.</small></label>
      <label>Шаблон задания<select v-model="form.promptProfile" aria-label="Шаблон задания"><option value="BASELINE">Базовый · тот же контракт</option><option value="SPECIALIZED">Специализированный</option></select></label>
      <label class="opt-check"><input v-model="form.skillEnabled" type="checkbox">Skill incident-triage</label><small>Правила и пример, не обучение весов.</small>
      <label>Temperature · {{ form.parameters.temperature }}<input v-model.number="form.parameters.temperature" type="range" min="0" max="2" step="0.1" :disabled="form.provider === 'DEEPSEEK' && form.parameters.thinking"></label>
      <label>Максимум выходных токенов<input v-model.number="form.parameters.maxTokens" aria-label="Максимум выходных токенов" type="number" min="64" max="8192" required></label>
      <label>Окно контекста · локальная модель<select v-model.number="form.parameters.contextSize" aria-label="Окно контекста" :disabled="form.provider === 'DEEPSEEK'"><option :value="1024">1 024</option><option :value="2048">2 048</option><option :value="4096">4 096</option><option :value="6144">6 144</option><option :value="8192">8 192</option><option :value="16384">16 384</option></select></label>
      <label class="opt-check"><input v-model="form.parameters.thinking" type="checkbox">Thinking</label>
      <details><summary>Дополнительные параметры</summary><label>Top P<input v-model.number="form.parameters.topP" type="number" min="0.01" max="1" step="0.01" :disabled="form.provider === 'DEEPSEEK' && !form.parameters.thinking"></label><label>Top K · только локально<input v-model.number="form.parameters.topK" type="number" min="1" max="100" :disabled="form.provider === 'DEEPSEEK'"></label><label>Seed · только локально<input v-model.number="form.parameters.seed" type="number" step="1" :disabled="form.provider === 'DEEPSEEK'"></label></details>
      <label>Повторов каждого обращения<select v-model.number="form.repetitions"><option :value="1">1</option><option :value="2">2</option><option :value="3">3</option></select></label>
    </fieldset><div class="opt-settings-note"><p>Малое окно не обрезает вход молча: переполнение отображается ошибкой.</p><p>DeepSeek: окно, top K и seed не настраиваются. Temperature действует только без thinking; top P без thinking равен 1, с thinking не ниже 0,95.</p><p>При проверке квантования не меняйте промпт, skill, параметры и набор обращений.</p></div></aside>
  </div>
</template>
