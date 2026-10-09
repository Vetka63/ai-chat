<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { api, HttpFailure, type RuntimeInfo } from '../../api'
import { ragApi, isRunning, metric, type DocumentInfo, type Experiment, type ExperimentInfo, type Mode, type RagIndex, type RagRequest, type Settings } from './api'
import './rag.css'

const emit = defineEmits<{ busy: [boolean] }>()
const settings = ref<Settings>(), runtime = ref<RuntimeInfo>(), documents = ref<DocumentInfo[]>([]), indexes = ref<RagIndex[]>([]), history = ref<ExperimentInfo[]>([])
const current = ref<Experiment>(), error = ref(''), loading = ref(false), sending = ref(false)
const conclusion = ref(''), conclusionSaved = ref(false)
watch(() => current.value?.id, () => { conclusion.value = current.value?.conclusion ?? ''; conclusionSaved.value = false })
const mode = ref<Mode>('LOCAL'), localModel = ref('qwen3:8b'), cloudModel = ref('deepseek-flash'), indexId = ref(''), scope = ref('')
const question = ref(''), topK = ref(5), threshold = ref(0.35), contextCharacters = ref(7000), temperature = ref(0), thinking = ref(false), outputCap = ref<number | null>(null)
const uploadFile = ref<File>(), uploadTitle = ref(''), marker = ref(''), uncertain = ref<RagRequest>()
let timer: ReturnType<typeof setTimeout> | undefined, disposed = false
const building = computed(() => indexes.value.find(i => i.status === 'BUILDING'))
const busy = computed(() => sending.value || !!building.value || isRunning(current.value?.status))
const localReady = computed(() => runtime.value?.models.some(model => model.name === localModel.value) ?? false)
const providerReady = computed(() => (mode.value === 'DEEPSEEK' || localReady.value) && (mode.value === 'LOCAL' || settings.value?.cloudReady === true))
watch(busy, value => { emit('busy', value); if (!value) syncResource() })
const scopeDocuments = computed(() => documents.value.filter(d => indexes.value.find(i => i.id === indexId.value)?.documentIds.includes(d.id)))
const examples = [
  'Что Квара носил на своей броне в рассказе «Кракен» и сколько было имён?',
  'Кто такая Сальватория Грант и каким самолётом она управляла в «Молниеносном рейсе»?',
  'Почему Асторат должен был положить конец мучительным диссонансам в «Искупителе»?',
  'Почему Северина Рейн говорит, что город Термина поёт?',
]
function newQuestion() { if (!busy.value) { current.value = undefined; window.location.hash = 'rag' } }
function syncResource() {
  if (busy.value || loading.value || uncertain.value) return
  const hash = window.location.hash
  if (hash.startsWith('#rag/')) {
    try { const id = decodeURIComponent(hash.slice(5)); if (id && id !== current.value?.id) void open(id) }
    catch { error.value = 'Некорректная ссылка на результат.' }
  } else if (hash === '#rag' && current.value) newQuestion()
}
async function saveConclusion() {
  if (!current.value) return
  try { current.value = await ragApi.conclude(current.value.id, conclusion.value); conclusionSaved.value = true }
  catch (e) { error.value = String(e instanceof Error ? e.message : e) }
}
async function refresh() {
  const [s, r, d, i, h] = await Promise.all([ragApi.settings(), api.runtime(), ragApi.documents(), ragApi.indexes(), ragApi.experiments()])
  settings.value = s; runtime.value = r; documents.value = d; indexes.value = i; history.value = h
  if (!indexId.value) indexId.value = i.find(index => index.status === 'READY')?.id ?? ''
  if (r.models.length && !r.models.some(model => model.name === localModel.value)) localModel.value = r.models[0]!.name
  if (s.cloudModels.length && !s.cloudModels.includes(cloudModel.value)) cloudModel.value = s.cloudModels[0]!
}
async function open(id: string) {
  try {
    current.value = await ragApi.experiment(id); window.location.hash = `rag/${current.value.id}`; error.value = ''
    if (uncertain.value?.requestId === current.value.id) { uncertain.value = undefined; localStorage.removeItem('day28-pending') }
  }
  catch (e) { error.value = String(e instanceof Error ? e.message : e) }
}
async function poll() {
  if (disposed) return
  try {
    if (building.value) { indexes.value = await ragApi.indexes(); if (!building.value) await refresh() }
    if (isRunning(current.value?.status)) {
      current.value = await ragApi.experiment(current.value!.id)
      if (!isRunning(current.value.status)) history.value = await ragApi.experiments()
    }
  } catch (e) { error.value = `Обновление прервано: ${String(e instanceof Error ? e.message : e)}. Результат на сервере не отменён.` }
  finally { if (!disposed) timer = setTimeout(poll, 900) }
}
async function start(retry?: RagRequest) {
  if (busy.value) return
  error.value = ''
  const request: RagRequest = retry ?? { requestId: crypto.randomUUID(), indexId: indexId.value, question: question.value.trim(), mode: mode.value,
    localModel: localModel.value, cloudModel: cloudModel.value, topK: topK.value, threshold: threshold.value,
    contextCharacters: contextCharacters.value, temperature: temperature.value, thinking: thinking.value, maxOutputTokens: outputCap.value || null, documentIds: scope.value ? [scope.value] : [] }
  // Без сохранённой квитанции не отправляем POST: неизвестный исход нельзя безопасно восстановить.
  try { localStorage.setItem('day28-pending', JSON.stringify(request)) }
  catch { error.value = 'Не удалось сохранить квитанцию. Запрос не отправлен.'; return }
  sending.value = true
  try {
    window.location.hash = `rag/${request.requestId}`
    current.value = await ragApi.start(request); uncertain.value = undefined
    localStorage.removeItem('day28-pending'); window.location.hash = `rag/${current.value.id}`; history.value = await ragApi.experiments()
  } catch (e) {
    error.value = String(e instanceof Error ? e.message : e)
    if (e instanceof HttpFailure && [400, 401, 403, 409, 422].includes(e.status)) {
      localStorage.removeItem('day28-pending'); uncertain.value = undefined
      window.location.hash = current.value ? `rag/${current.value.id}` : 'rag'
    } else {
      try { current.value = await ragApi.experiment(request.requestId); localStorage.removeItem('day28-pending'); uncertain.value = undefined; error.value = ''; window.location.hash = `rag/${current.value.id}` }
      catch { uncertain.value = request }
    }
  } finally { sending.value = false }
}
async function upload() {
  if (!uploadFile.value) return
  loading.value = true; error.value = ''
  try { await ragApi.upload(uploadFile.value, uploadTitle.value, marker.value); await refresh(); uploadFile.value = undefined; uploadTitle.value = ''; marker.value = '' }
  catch (e) { error.value = String(e instanceof Error ? e.message : e) } finally { loading.value = false }
}
async function build() {
  error.value = ''; loading.value = true
  try { const index = await ragApi.build(); indexes.value.unshift(index); indexId.value = index.id }
  catch (e) { error.value = String(e instanceof Error ? e.message : e) } finally { loading.value = false }
}
function fileChanged(event: Event) { uploadFile.value = (event.target as HTMLInputElement).files?.[0]; if (!uploadTitle.value) uploadTitle.value = uploadFile.value?.name.replace(/\.txt$/i, '') ?? '' }
onMounted(async () => {
  window.addEventListener('hashchange', syncResource)
  loading.value = true
  try {
    await refresh()
    const id = window.location.hash.match(/^#rag\/(.+)$/)?.[1]
    const pending = localStorage.getItem('day28-pending')
    if (pending) uncertain.value = JSON.parse(pending) as RagRequest
    if (id) await open(id)
    else if (uncertain.value) await open(uncertain.value.requestId)
    else { const active = history.value.find(h => isRunning(h.status)); if (active) await open(active.id) }
  } catch (e) { error.value = String(e instanceof Error ? e.message : e) } finally { loading.value = false; poll() }
})
onUnmounted(() => { disposed = true; if (timer) clearTimeout(timer); window.removeEventListener('hashchange', syncResource); emit('busy', false) })
</script>

<template>
  <div class="rag-layout">
    <aside class="rag-history">
      <div><span class="eyebrow">Warhammer · русские рассказы</span><h2>Локальный RAG</h2></div>
      <button :disabled="busy" @click="newQuestion">＋ Новый вопрос</button>
      <div class="rag-history-list">
        <button v-for="item in history" :key="item.id" :disabled="busy" :class="{ selected: current?.id === item.id }" @click="open(item.id)">
          <span>{{ item.question }}</span><small>{{ item.mode }} · {{ item.status }}</small><small>{{ new Date(item.createdAt).toLocaleString('ru-RU') }} · {{ item.id.slice(0, 8) }}</small>
        </button>
        <p v-if="!history.length" class="muted">Ответы и сравнения сохраняются на сервере.</p>
      </div>
      <p class="muted">LOCAL — только ваш компьютер. COMPARE — найденные фрагменты также уходят в DeepSeek.</p>
    </aside>

    <main class="rag-main">
      <header class="rag-heading"><div><span class="eyebrow">День 28</span><h1>Спросите книгу</h1><p>Один поиск. Один контекст. Два независимых ответа.</p></div>
        <span class="runtime-status" :class="{ offline: !runtime?.connected }">{{ runtime?.connected ? 'Ollama подключена' : 'Ollama недоступна' }}</span></header>
      <div v-if="error" class="notice error" role="alert">{{ error }}</div>
      <div v-if="uncertain" class="notice">Отправка не подтверждена. Повтор использует тот же ID и не дублирует сохранённый запрос.
        <button :disabled="busy" @click="start(uncertain)">Повторить с тем же ID</button></div>
      <form class="rag-composer" @submit.prevent="start()">
        <label for="rag-question">Ваш вопрос</label><textarea id="rag-question" v-model="question" :disabled="busy" maxlength="2000" placeholder="Спросите о событиях или героях загруженных рассказов…" required />
        <div class="rag-composer-actions"><label>Режим <select v-model="mode" aria-label="Режим" :disabled="busy"><option value="LOCAL">Локальная LLM</option><option value="DEEPSEEK" :disabled="!settings?.cloudReady">DeepSeek</option><option value="COMPARE" :disabled="!settings?.cloudReady">Сравнить параллельно</option></select></label>
          <button class="primary" :disabled="busy || loading || !indexId || !question.trim() || !!uncertain || !providerReady">{{ busy ? 'Выполняется…' : mode === 'COMPARE' ? 'Сравнить ответы' : 'Получить ответ' }}</button></div>
        <p v-if="mode !== 'DEEPSEEK' && !localReady" class="notice">Выбранная локальная модель ещё не установлена или runtime недоступен. Завершите загрузку и обновите страницу; автоматической замены на облако нет.</p>
      </form>
      <div class="rag-examples" v-if="!current"><button v-for="example in examples" :key="example" :disabled="busy" @click="question = example">{{ example }}</button></div>
      <section v-if="current" class="rag-result" aria-live="polite">
        <header><div><h2>{{ current.request.question }}</h2><small>{{ current.status }} · {{ current.request.mode }}</small></div><a :href="`/api/v1/rag/experiments/${current.id}/report`" class="text-button">Скачать MD</a></header>
        <p v-if="current.status === 'PREPARING'" class="notice">Выполняется локальный поиск и подготовка общего контекста…</p>
        <p v-if="current.error" class="notice" :class="{ error: current.status !== 'UNKNOWN' }">{{ current.error }}</p>
        <div class="rag-answer-grid" :class="{ compare: current.answers.length === 2 }">
          <article v-for="answer in current.answers" :key="answer.provider" class="rag-answer-card" :data-provider="answer.provider">
            <header><strong>{{ answer.provider === 'LOCAL' ? 'Локальная модель' : 'DeepSeek' }}</strong><span>{{ answer.status }}</span></header>
            <small>{{ answer.actualModel || answer.requestedModel }}</small>
            <p v-if="answer.status === 'RUNNING'" class="rag-wait">Модель составляет ответ… Вторую карточку ждать не нужно.</p>
            <p v-if="answer.error" class="notice error">{{ answer.error }}</p>
            <div v-if="answer.answer" class="rag-answer-text">{{ answer.answer }}</div>
            <p v-if="answer.status === 'TRUNCATED'" class="notice">Ответ обрезан лимитом. Это не завершённый результат.</p>
            <details v-if="answer.thinking"><summary>Thinking модели</summary><pre>{{ answer.thinking }}</pre></details>
            <dl class="rag-metrics"><div><dt>Генерация</dt><dd>{{ metric(answer.milliseconds, ' мс') }}</dd></div><div><dt>Input / output</dt><dd>{{ metric(answer.inputTokens) }} / {{ metric(answer.outputTokens) }}</dd></div><div><dt>Загрузка модели</dt><dd>{{ metric(answer.loadMilliseconds, ' мс') }}</dd></div><div><dt>Токенов/с</dt><dd>{{ metric(answer.tokensPerSecond) }}</dd></div></dl>
            <p class="muted">ID ссылок: {{ answer.citationStatus }} · {{ answer.citedRefs.join(', ') || 'нет' }}. Это не проверка истинности утверждений.</p>
          </article>
        </div>
        <details><summary>Сохранённые параметры эксперимента</summary><pre>{{ JSON.stringify(current.request, null, 2) }}</pre></details>
        <form v-if="!isRunning(current.status)" class="rag-conclusion" @submit.prevent="saveConclusion"><label for="rag-conclusion">Ваш вывод: качество, скорость, стабильность</label><textarea id="rag-conclusion" v-model="conclusion" maxlength="3000" placeholder="Какие факты подтверждены? Какая модель быстрее? Повторяется ли результат?" /><button>Сохранить вывод</button><span v-if="conclusionSaved" class="muted"> Сохранено; вывод включён в MD.</span></form>
        <section v-if="current.retrieval" class="rag-evidence"><h3>Общий контекст для выбранных моделей</h3>
          <p class="muted">Поиск: {{ metric(current.retrieval.milliseconds, ' мс') }} · {{ current.retrieval.included.length }} фрагментов · {{ current.retrieval.contextCharacters }} символов · embedding {{ current.retrieval.embeddingCacheHit ? 'из локального кеша' : 'вычислен локально' }} · всего: {{ metric(current.milliseconds, ' мс') }}</p>
          <details><summary>Диагностика одинакового контекста</summary><p class="rag-hash">Prompt SHA-256: {{ current.retrieval.promptSha256 }}</p><p class="muted">Similarity — не вероятность. Порог и бюджет могут исключить кандидатов. Ниже — именно переданные фрагменты, не доказательство правильности ответа.</p>
            <ul><li v-for="hit in current.retrieval.hits" :key="hit.ref">{{ hit.ref }} · {{ hit.chunk.title }} · {{ hit.origin === 'BOOK_OPENING' ? 'начало указанного рассказа' : 'hybrid' }} · cosine {{ hit.similarity.toFixed(3) }} · dense #{{ hit.denseRank ?? '—' }} / lexical #{{ hit.lexicalRank ?? '—' }} · {{ current.retrieval.included.some(h => h.ref === hit.ref) ? 'передан' : 'отсеян' }}</li></ul></details>
          <details v-for="hit in current.retrieval.included" :key="hit.ref"><summary>[{{ hit.ref }}] {{ hit.chunk.title }} · {{ hit.chunk.section }} · {{ hit.similarity.toFixed(3) }}</summary>
            <p class="muted">{{ hit.chunk.source }} · chunk_id: {{ hit.chunk.id }} · диапазон {{ hit.chunk.start }}–{{ hit.chunk.endExclusive }}</p><p class="rag-source-text">{{ hit.chunk.text }}</p></details>
        </section>
      </section>
    </main>

    <aside class="rag-settings"><h2>Настройки</h2><fieldset :disabled="busy || loading">
      <label>Индекс<select v-model="indexId" @change="scope = ''"><option value="">Выберите готовый индекс</option><option v-for="index in indexes" :key="index.id" :value="index.id" :disabled="index.status !== 'READY'">{{ index.status }} · {{ index.total }} чанков · {{ index.id.slice(0, 8) }}</option></select></label>
      <label>Область поиска<select v-model="scope"><option value="">Все рассказы индекса</option><option v-for="doc in scopeDocuments" :key="doc.id" :value="doc.id">{{ doc.title }}</option></select></label>
      <label v-if="mode !== 'DEEPSEEK'">Локальная модель<select v-model="localModel"><option v-for="model in runtime?.models" :key="model.name" :value="model.name">{{ model.name }} · {{ model.quantization }}</option></select></label>
      <label v-if="mode !== 'LOCAL'">Облачная модель<select v-model="cloudModel"><option v-for="model in settings?.cloudModels" :key="model" :value="model">{{ model }}</option></select></label>
      <label>Top-K · {{ topK }}<input v-model.number="topK" type="range" min="1" max="12" /></label>
      <label>Порог similarity · {{ threshold }}<input v-model.number="threshold" type="range" min="0" max="0.9" step="0.05" /></label>
      <label>Бюджет фрагментов, символы<input v-model.number="contextCharacters" type="number" min="1500" max="12000" step="500" /></label>
      <label>Температура · {{ temperature }}<input v-model.number="temperature" type="range" min="0" max="2" step="0.1" /></label>
      <label>Лимит выхода (пусто — не задавать)<input v-model.number="outputCap" type="number" min="1" max="8192" placeholder="Не задан" /></label>
      <label class="rag-check"><input v-model="thinking" type="checkbox" /> Reasoning</label>
    </fieldset><p class="muted">Окно локальной модели: {{ settings?.contextTokens }} токенов. Бюджет фрагментов измеряется в символах, не токенах. Настройки действуют на новый запрос.</p>
      <details class="rag-corpus" open><summary>Корпус и индексация</summary>
        <ul><li v-for="doc in documents" :key="doc.id">{{ doc.title }} <small>{{ doc.characters.toLocaleString('ru-RU') }} символов</small></li></ul>
        <p v-if="building" class="notice">Индексация: {{ building.processed }} / {{ building.total || '…' }}</p>
        <p v-for="index in indexes.filter(i => i.error)" :key="index.id" class="notice error">{{ index.error }}</p>
        <button :disabled="busy || loading || !documents.length" @click="build">Построить новый индекс</button>
        <details><summary>Добавить UTF-8 TXT</summary><form @submit.prevent="upload"><fieldset :disabled="busy || loading">
          <label>Файл<input type="file" accept=".txt,text/plain" @change="fileChanged" required /></label><label>Название<input v-model="uploadTitle" maxlength="150" required /></label><label>Начальный заголовок (необязательно)<input v-model="marker" placeholder="Например: КРАКЕН" /></label><p class="muted">До указанного заголовка текст будет исключён. Файл не попадает в Git.</p><button :disabled="!uploadFile">Загрузить</button>
        </fieldset></form></details>
      </details><p class="muted rag-privacy">{{ settings?.cloudReady ? 'DeepSeek доступен только при явном выборе облачного режима.' : 'Облачный режим отключён или ключ не задан. Локальный RAG работает без него.' }} Автоматических повторов и fallback нет.</p>
    </aside>
  </div>
</template>
