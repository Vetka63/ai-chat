<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, type Schema } from '../../api/client'
import { useLabStore } from './store'
import { comparisonMarkdown, download } from './report'
import AnswerLab from '../answering/AnswerLab.vue'
import ExperimentLab from '../experiments/ExperimentLab.vue'
import GroundingLab from '../grounding/GroundingLab.vue'
import ConversationLab from '../conversations/ConversationLab.vue'

const lab = useLabStore()
const tab = ref<'corpus' | 'chunks' | 'compare' | 'search' | 'answers' | 'experiments' | 'grounding' | 'chat'>('corpus')
const answersVisited = ref(false)
const experimentsVisited = ref(false)
const groundingVisited = ref(false)
const chatVisited = ref(false)
const config = ref<Schema<'ChunkConfig'>>({ strategy: 'FIXED', maxCharacters: 3000, overlapCharacters: 300 })
const busy = ref(false)
const loading = ref(true)
const error = ref('')
const preview = ref<Schema<'ChunkPreview'> | null>(null)
const chunks = ref<Schema<'Chunk'>[]>([])
const chunkOrigin = ref('')
const document = ref<Schema<'Document'> | null>(null)
const selectedIndex = ref('')
const compareIds = ref<string[]>([])
const comparison = ref<Schema<'IndexComparison'> | null>(null)
const query = ref('Как отменить последний коммит, но сохранить изменения в рабочем каталоге?')
const searches = ref<Schema<'SearchResult'>[]>([])
const searchIndexIds = ref<string[]>([])
const documentFilter = ref('')
const chunkPage = ref(0)
const activeJob = computed(() => lab.jobs.find(j => j.status === 'RUNNING' || j.status === 'QUEUED'))
const visibleChunks = computed(() => chunks.value.filter(c => !documentFilter.value || c.documentId === documentFilter.value))
const pagedChunks = computed(() => visibleChunks.value.slice(chunkPage.value * 12, (chunkPage.value + 1) * 12))
const strategyLabel = (strategy: string) => strategy === 'FIXED' ? 'Фиксированный размер' : 'По структуре'
const indexLabel = (index: Schema<'IndexInfo'>) => `${strategyLabel(index.config.strategy)} · ${index.metrics.chunkCount} чанков · ${new Date(index.createdAt).toLocaleTimeString('ru-RU')}`
const number = (value: number) => value.toLocaleString('ru-RU')
let timer: ReturnType<typeof setInterval> | undefined
let refreshing = false
let disposed = false

async function action(operation: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { await operation() } catch (e) { error.value = e instanceof Error ? e.message : 'Не удалось выполнить действие.' } finally { busy.value = false }
}
async function load() {
  await action(async () => { await lab.load(); if (lab.indexes[0]) selectedIndex.value = lab.indexes[0].id })
  loading.value = false
}
async function refresh() {
  if (refreshing || disposed) return
  refreshing = true
  try {
    const previous = activeJob.value?.id
    await lab.refresh()
    if (previous && !activeJob.value) {
      const completed = lab.jobs.find(j => j.id === previous)
      if (completed?.status === 'FAILED') error.value = completed.error ?? 'Ошибка построения.'
      if (completed?.indexId) selectedIndex.value = completed.indexId
    }
  } catch (e) { error.value = e instanceof Error ? e.message : 'Backend недоступен.' } finally { refreshing = false }
}
async function makePreview() {
  await action(async () => { preview.value = await api.preview(config.value); chunks.value = preview.value.chunks; chunkOrigin.value = `Предпросмотр: ${strategyLabel(preview.value.config.strategy)} · ${preview.value.config.maxCharacters} / ${preview.value.config.overlapCharacters}`; chunkPage.value = 0; documentFilter.value = '' })
}
async function startIndex() {
  await action(async () => { const job = await api.start(config.value); lab.jobs.unshift(job); preview.value = null })
}
async function openIndex() {
  const index = lab.indexes.find(i => i.id === selectedIndex.value)
  if (!index) return
  await action(async () => { chunks.value = await api.chunks(index.id); chunkOrigin.value = `Сохранённый индекс: ${indexLabel(index)}`; preview.value = null; chunkPage.value = 0; documentFilter.value = '' })
}
async function openDocument(id: string) { await action(async () => { document.value = await api.document(id) }) }
async function openSource(indexId: string, id: string) { await action(async () => { document.value = await api.indexDocument(indexId, id) }) }
function closeOnEscape(event: KeyboardEvent) { if (event.key === 'Escape') document.value = null }
function toggleCompare(id: string) {
  if (compareIds.value.includes(id)) compareIds.value = compareIds.value.filter(value => value !== id)
  else if (compareIds.value.length < 2) compareIds.value.push(id)
  comparison.value = null
}
async function compare() {
  const ids = [...compareIds.value]
  await action(async () => {
    const result = await api.compare(ids)
    if (ids.join('|') === compareIds.value.join('|')) comparison.value = result
  })
}
async function search() {
  const ids = searchIndexIds.value.length ? [...searchIndexIds.value] : selectedIndex.value ? [selectedIndex.value] : []
  const submittedQuery = query.value.trim()
  if (!submittedQuery || !ids.length) return
  await action(async () => {
    searches.value = []
    for (const id of ids) searches.value.push(await api.search(id, submittedQuery))
  })
}
const metricRows: [string, (i: Schema<'IndexInfo'>) => string | number][] = [
  ['Чанков', i => i.metrics.chunkCount], ['Размер / перекрытие', i => `${i.config.maxCharacters} / ${i.config.overlapCharacters}`],
  ['Минимум / медиана / p95', i => `${i.metrics.minCharacters} / ${i.metrics.medianCharacters} / ${i.metrics.p95Characters}`],
  ['Покрытие текста', i => `${i.metrics.coveragePercent}%`], ['Чанки через границы разделов', i => i.metrics.crossSectionChunks],
  ['Разрезанные блоки команд', i => i.metrics.splitCodeBlocks], ['Построение', i => `${(i.buildMilliseconds / 1000).toFixed(1)} с`],
  ['Объём векторов', i => `${(i.vectorBytes / 1024).toFixed(0)} КиБ`], ['Токены embeddings по runtime', i => i.embeddingInputTokens ?? 'не предоставлены'],
]
onMounted(async () => { window.addEventListener('keydown', closeOnEscape); await load(); timer = setInterval(refresh, 2000) })
onUnmounted(() => { disposed = true; window.removeEventListener('keydown', closeOnEscape); if (timer) clearInterval(timer) })
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <a class="brand" href="/" aria-label="Лаборатория RAG"><span class="brand-icon">R</span><span>RAG<span class="brand-caption">Лаборатория знаний</span></span></a>
      <div class="sidebar-label">НЕДЕЛЯ 5 · ДНИ 21–25</div>
      <nav aria-label="Разделы лаборатории">
        <button :class="{ active: tab === 'corpus' }" @click="tab = 'corpus'">▤ <span>Корпус документов</span></button>
        <button :class="{ active: tab === 'chunks' }" @click="tab = 'chunks'">▦ <span>Разбиение и индексы</span></button>
        <button :class="{ active: tab === 'compare' }" @click="tab = 'compare'">⇄ <span>Сравнение стратегий</span></button>
        <button :class="{ active: tab === 'search' }" @click="tab = 'search'">⌕ <span>Диагностический поиск</span></button>
        <button :class="{ active: tab === 'answers' }" @click="answersVisited = true; tab = 'answers'">◈ <span>Ответы с RAG / без RAG</span></button>
        <button :class="{ active: tab === 'experiments' }" @click="experimentsVisited = true; tab = 'experiments'">◇ <span>Фильтр и rewrite</span></button>
        <button :class="{ active: tab === 'grounding' }" @click="groundingVisited = true; tab = 'grounding'">❞ <span>Источники и цитаты</span></button>
        <button :class="{ active: tab === 'chat' }" @click="chatVisited = true; tab = 'chat'">☏ <span>Чат с RAG и памятью</span></button>
      </nav>
      <div class="sidebar-bottom"><span class="status-dot"></span> Локальная база знаний<p>Эмбеддинги: Ollama.<br>Генерация: DeepSeek API по кнопке.</p></div>
    </aside>
    <main>
      <header class="page-header"><div><span class="eyebrow">PRO GIT · РУССКОЕ ИЗДАНИЕ</span><h1>{{ tab === 'chat' ? 'Диалог, который помнит задачу' : tab === 'grounding' ? 'Ответы с проверяемыми цитатами' : tab === 'experiments' ? 'Как отбирается контекст' : tab === 'answers' ? 'Что меняет найденный контекст' : tab === 'corpus' ? 'База знаний начинается здесь' : tab === 'chunks' ? 'Как текст становится индексом' : tab === 'compare' ? 'Две стратегии. Один корпус.' : 'Проверим, что находится' }}</h1><p>{{ tab === 'chat' ? 'Обсуждайте цель, уточняйте ситуацию и проверяйте ответы по книге.' : tab === 'grounding' ? 'Сопоставьте каждый пункт ответа с точным фрагментом источника.' : tab === 'experiments' ? 'Сравните обычный поиск, cosine-фильтр и переформулирование запроса.' : tab === 'answers' ? 'Сравните знания модели и ответ с материалами книги — на одном вопросе.' : tab === 'search' ? 'Вопрос превращается в вектор. Здесь показываем найденный текст — без генерации ответа.' : 'Изучайте источники, стройте индексы и наблюдайте каждый шаг обработки.' }}</p></div><span class="day-badge">{{ tab === 'chat' ? 'День 25' : tab === 'grounding' ? 'День 24' : tab === 'experiments' ? 'День 23' : tab === 'answers' ? 'День 22' : 'День 21' }}</span></header>
      <div v-if="error" class="notice error" role="alert"><span>{{ error }}</span><button class="icon-button" @click="error = ''" aria-label="Закрыть ошибку">×</button></div>
      <div v-if="loading" class="empty">Загружаем локальную базу…</div>
      <div v-else-if="!lab.corpus" class="empty"><h2>Корпус пока недоступен</h2><p>Подготовьте snapshot по инструкции Windows и проверьте backend.</p><button @click="load">Повторить</button></div>
      <template v-else>
        <div v-if="tab !== 'chat'" class="stats-row"><div><strong>{{ lab.corpus.documentCount }}</strong><span>документов</span></div><div><strong>{{ lab.corpus.sectionCount }}</strong><span>разделов</span></div><div><strong>{{ number(lab.corpus.words) }}</strong><span>слов в корпусе</span></div><div><strong>{{ lab.indexes.length }}</strong><span>готовых индексов</span></div></div>
        <div v-if="activeJob" class="job-panel" role="status"><div class="job-title"><strong>Строим {{ strategyLabel(activeJob.config.strategy).toLowerCase() }}</strong><span>{{ activeJob.processed }} / {{ activeJob.total }} чанков</span></div><progress :max="activeJob.total || 1" :value="activeJob.processed"></progress><p>Готовый индекс появится после проверки всех векторов. Страницу можно перезагрузить.</p></div>

        <AnswerLab v-if="answersVisited" v-show="tab === 'answers'" :indexes="lab.indexes" />
        <ExperimentLab v-if="experimentsVisited" v-show="tab === 'experiments'" :indexes="lab.indexes" />
        <GroundingLab v-if="groundingVisited" v-show="tab === 'grounding'" :indexes="lab.indexes" />
        <ConversationLab v-if="chatVisited" v-show="tab === 'chat'" :indexes="lab.indexes" />
        <section v-if="tab === 'corpus'" class="content-panel">
          <div class="section-heading"><div><h2>Книга, которую можно проверить</h2><p>Главы 2, 3, 7 и 10. Текст и примеры команд сохранены; исходники закреплены по commit.</p></div><button @click="tab = 'chunks'">Перейти к разбиению →</button></div>
          <div class="provenance"><span>Версия <code>{{ lab.corpus.manifest.revision.slice(0, 12) }}</code></span><span>{{ lab.corpus.manifest.license }}</span><span>≈ {{ lab.corpus.estimatedPages }} условных страниц</span></div>
          <details class="quiet-details"><summary>Что означает объём и откуда взяты данные</summary><p>{{ lab.corpus.pageFormula }}. Это не фактическая пагинация книги.</p><p>{{ lab.corpus.manifest.attribution }}</p><p>Snapshot: <code>{{ lab.corpus.snapshotId }}</code></p><a :href="lab.corpus.manifest.repository" target="_blank" rel="noreferrer">Открыть источник книги ↗</a></details>
          <div class="document-list"><button v-for="doc in lab.documents" :key="doc.id" class="document-item" @click="openDocument(doc.id)"><span class="document-icon">▤</span><span><strong>{{ doc.title }}</strong><small>{{ doc.chapter }} · {{ number(doc.words) }} слов · {{ doc.sections }} разделов</small></span><span class="document-arrow">↗</span></button></div>
        </section>

        <section v-if="tab === 'chunks'" class="content-panel">
          <div class="section-heading"><div><h2>Построить индекс</h2><p>Preview показывает только разбиение. Построение дополнительно вычисляет настоящие векторы.</p></div></div>
          <div class="strategy-picker"><button :class="{ selected: config.strategy === 'FIXED' }" @click="config.strategy = 'FIXED'"><strong>Фиксированный размер</strong><span>Одинаковые окна текста, независимо от разделов.</span></button><button :class="{ selected: config.strategy === 'STRUCTURAL' }" @click="config.strategy = 'STRUCTURAL'"><strong>По структуре</strong><span>Разделы и абзацы, с ограничением размера.</span></button></div>
          <div class="config-row"><label>Максимум символов<input v-model.number="config.maxCharacters" type="number" min="300" max="10000"></label><label>Перекрытие символов<input v-model.number="config.overlapCharacters" type="number" min="0" :max="Math.floor(config.maxCharacters / 2) - 1"></label><div class="actions"><button :disabled="busy" @click="makePreview">Посмотреть чанки</button><button class="primary" :disabled="busy || !!activeJob" @click="startIndex">Построить индекс</button></div></div>
          <p class="hint">Размеры считаются в символах, не токенах. Структурное разбиение может разрезать очень длинный абзац или блок команд — такие случаи считаем отдельно.</p>
          <div v-if="lab.indexes.length" class="saved-index"><label>Сохранённый индекс<select v-model="selectedIndex"><option v-for="index in lab.indexes" :key="index.id" :value="index.id">{{ indexLabel(index) }}</option></select></label><button :disabled="busy" @click="openIndex">Открыть чанки</button></div>
          <div v-if="preview" class="notice"><strong>Preview:</strong> {{ preview.metrics.chunkCount }} чанков · покрытие {{ preview.metrics.coveragePercent }}% · медиана {{ preview.metrics.medianCharacters }} символов. Векторы ещё не созданы.</div>
          <p v-if="chunks.length" class="hint">{{ chunkOrigin }}</p>
          <div v-if="chunks.length" class="chunk-toolbar"><strong>{{ visibleChunks.length }} чанков</strong><select v-model="documentFilter" @change="chunkPage = 0" aria-label="Фильтр документов"><option value="">Все документы</option><option v-for="doc in lab.documents" :key="doc.id" :value="doc.id">{{ doc.title }}</option></select></div>
          <article v-for="chunk in pagedChunks" :key="chunk.chunkId" class="chunk-card"><div class="chunk-title"><h3>{{ chunk.title }}</h3><span>{{ chunk.text.length }} символов</span></div><p class="chunk-section">{{ chunk.section }}</p><pre>{{ chunk.text }}</pre><details><summary>Метаданные фрагмента</summary><dl><dt>source</dt><dd>{{ chunk.source }}</dd><dt>chunk_id</dt><dd>{{ chunk.chunkId }}</dd><dt>Диапазон текста</dt><dd>[{{ chunk.start }}, {{ chunk.endExclusive }})</dd><dt>Начальные строки блоков</dt><dd>{{ chunk.sourceLineStart }}–{{ chunk.sourceLineEnd }}</dd></dl></details></article>
          <div v-if="visibleChunks.length > 12" class="pagination"><button :disabled="chunkPage === 0" @click="chunkPage--">← Назад</button><span>{{ chunkPage + 1 }} / {{ Math.ceil(visibleChunks.length / 12) }}</span><button :disabled="(chunkPage + 1) * 12 >= visibleChunks.length" @click="chunkPage++">Дальше →</button></div>
          <details class="quiet-details" v-if="lab.jobs.length"><summary>История заданий</summary><div v-for="job in lab.jobs" :key="job.id" class="job-history"><strong>{{ strategyLabel(job.config.strategy) }} · {{ job.status }}</strong><span>{{ job.processed }} / {{ job.total }}</span><p v-if="job.error" class="error-text">{{ job.error }}</p></div></details>
        </section>

        <section v-if="tab === 'compare'" class="content-panel">
          <div class="section-heading"><div><h2>Выберите два готовых индекса</h2><p>Для чистого эксперимента: одинаковый корпус, модель, размер и перекрытие.</p></div><button class="primary" :disabled="compareIds.length !== 2 || busy" @click="compare">Сравнить индексы</button></div>
          <div v-if="lab.indexes.length < 2" class="empty"><h3>Нужны два индекса</h3><p>Постройте фиксированный и структурный варианты в разделе разбиения.</p></div>
          <label v-for="index in lab.indexes" :key="index.id" class="index-option"><input type="checkbox" :checked="compareIds.includes(index.id)" :disabled="compareIds.length === 2 && !compareIds.includes(index.id)" @change="toggleCompare(index.id)"><span><strong>{{ indexLabel(index) }}</strong><small>{{ index.embedding.model }} · {{ index.embedding.dimension }}D · {{ index.config.maxCharacters }} / {{ index.config.overlapCharacters }} символов</small></span></label>
          <template v-if="comparison"><div class="notice" :class="{ error: !comparison.comparable }">{{ comparison.comparable ? 'Одинаковый корпус и embedding space — сравнение допустимо.' : 'Корпус или модель отличаются. Это не чистое сравнение стратегий.' }}</div><div class="table-wrap"><table><thead><tr><th>Показатель</th><th v-for="index in comparison.indexes" :key="index.id">{{ strategyLabel(index.config.strategy) }}</th></tr></thead><tbody><tr v-for="[label, value] in metricRows" :key="label"><td>{{ label }}</td><td v-for="index in comparison.indexes" :key="index.id">{{ value(index) }}</td></tr></tbody></table></div><ul class="comparison-notes"><li v-for="note in comparison.notes" :key="note">{{ note }}</li></ul><div class="actions"><button @click="download('day21-chunking-comparison.md', comparisonMarkdown(comparison!, searches))">Скачать Markdown</button><button @click="download('day21-chunking-comparison.json', JSON.stringify(comparison, null, 2), 'application/json')">Скачать JSON</button><button @click="searchIndexIds = [...compareIds]; tab = 'search'">Проверить поиск →</button></div></template>
        </section>

        <section v-if="tab === 'search'" class="content-panel">
          <div class="section-heading"><div><h2>Найдите фрагменты книги</h2><p>Cosine similarity — сходство векторов, не уверенность в правильности ответа.</p></div></div>
          <div v-if="!lab.indexes.length" class="empty">Сначала постройте хотя бы один индекс.</div>
          <template v-else><div class="search-indexes"><label v-for="index in lab.indexes" :key="index.id"><input v-model="searchIndexIds" type="checkbox" :value="index.id">{{ indexLabel(index) }}</label></div><form @submit.prevent="search"><label class="query-label">Ваш поисковый вопрос<textarea v-model="query" rows="3" maxlength="2000"></textarea></label><button class="primary" :disabled="busy || !query.trim() || (!searchIndexIds.length && !selectedIndex)" type="submit">{{ busy ? 'Ищем фрагменты…' : 'Найти в книге' }}</button></form><div class="search-columns"><div v-for="result in searches" :key="result.indexId"><h3>{{ strategyLabel(lab.indexes.find(i => i.id === result.indexId)?.config.strategy ?? '') }}</h3><p class="hint">{{ (result.milliseconds / 1000).toFixed(2) }} с · {{ result.hits.length }} фрагментов</p><article v-for="hit in result.hits" :key="hit.chunk.chunkId" class="search-hit"><span class="score">#{{ hit.rank }} · cosine {{ hit.similarity.toFixed(4) }}</span><h3>{{ hit.chunk.title }}</h3><p>{{ hit.chunk.section }}</p><pre>{{ hit.chunk.text }}</pre><small>source: {{ hit.chunk.source }}</small><small>chunk_id: {{ hit.chunk.chunkId }}</small><button :disabled="busy" @click="openSource(result.indexId, hit.chunk.documentId)">Открыть сохранённый источник</button></article></div></div></template>
        </section>
      </template>
    </main>
    <div v-if="document" class="modal-backdrop" @click.self="document = null"><section class="document-modal" role="dialog" aria-modal="true" aria-labelledby="document-title"><header><div><h2 id="document-title">{{ document.title }}</h2><a :href="document.sourceUrl" target="_blank" rel="noreferrer">Закреплённый оригинал ↗</a></div><button class="icon-button" @click="document = null" aria-label="Закрыть документ">×</button></header><pre>{{ document.text }}</pre></section></div>
  </div>
</template>
