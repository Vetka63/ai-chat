<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, type Schema } from '../../api/client'
import { download } from '../lab/report'
import { usageText, reasonLabels } from '../experiments/report'
import { groundedMarkdown, supportGenerations } from './report'
import GroundingRepairTrace from './GroundingRepairTrace.vue'
import { listGroundingHistory, loadGroundingResult, saveGroundingResult, parseGroundingImport, MAX_IMPORT_BYTES, type GroundingHistoryEntry } from './history'
import '../answering/answering.css'
import './grounding.css'

const props = defineProps<{ indexes: Schema<'IndexInfo'>[] }>()
const indexId = ref(props.indexes.find(i => i.config.strategy === 'STRUCTURAL')?.id ?? props.indexes[0]?.id ?? '')
const question = ref('Как git stash сохранить untracked-файлы? Включает ли -u файлы из .gitignore?')
const questions = ref<Schema<'ControlQuestion'>[]>([])
const selectedCase = ref('')
const expected = computed(() => questions.value.find(q => q.id === selectedCase.value))
const settings = ref<Schema<'AnswerSettings'> | null>(null)
const candidateK = ref(20), finalK = ref(10), threshold = ref(.60), budget = ref(32000)
const useRewrite = ref(false), maxOutput = ref<number | null>(null)
const busy = ref(false), error = ref('')
const result = ref<Schema<'GroundedResult'> | null>(null)
const history = ref<GroundingHistoryEntry[]>([]), selectedHistory = ref('')
const savedEntry = ref<GroundingHistoryEntry | null>(null), historyError = ref(''), historyBusy = ref(false)
const importSummary = ref(''), importFailures = ref<string[]>([])
const maxImportFiles = 20
const opened = ref<{ document: Schema<'Document'>; citation: Schema<'VerifiedCitation'> } | null>(null)
const invalid = computed(() => !question.value.trim() || !indexId.value || finalK.value > candidateK.value)
const statusLabels = { ANSWERED: 'Цитаты проверены', UNKNOWN: 'Не знаю по найденным материалам', INVALID_EVIDENCE: 'Ответ не прошёл проверку', ERROR: 'Ошибка стадии' }
function close(e: KeyboardEvent) { if (e.key === 'Escape') opened.value = null }
onMounted(async () => {
  try { [settings.value, questions.value] = await Promise.all([api.answerSettings(), api.questions()]) } catch (e) { error.value = String(e) }
  try {
    const loaded = await listGroundingHistory()
    history.value = Array.from(new Map([...loaded, ...history.value].map(entry => [entry.id, entry])).values()).sort((a, b) => b.savedAt.localeCompare(a.savedAt))
    if (history.value[0] && !result.value && !busy.value) { selectedHistory.value = history.value[0].id; await openSaved() }
  } catch (e) { historyError.value = String(e) }
  window.addEventListener('keydown', close)
})
onUnmounted(() => window.removeEventListener('keydown', close))
async function run() {
  if (busy.value || invalid.value) return
  busy.value = true; error.value = ''; result.value = null; opened.value = null; savedEntry.value = null; selectedHistory.value = ''
  const body: Schema<'GroundingRequest'> = { question: question.value.trim(), indexId: indexId.value, candidateTopK: candidateK.value, finalTopK: finalK.value, similarityThreshold: threshold.value, contextMaxCharacters: budget.value, useRewrite: useRewrite.value, maxOutputTokens: typeof maxOutput.value === 'number' ? maxOutput.value : null }
  try { result.value = await api.groundedAnswer(body); await remember(result.value, 'request') } catch (e) { error.value = e instanceof Error ? e.message : String(e) } finally { busy.value = false }
}
async function remember(value: Schema<'GroundedResult'>, origin: GroundingHistoryEntry['origin'], fileName?: string, quiet = false): Promise<string | null> {
  try {
    const entry = await saveGroundingResult(value, origin, fileName)
    savedEntry.value = entry; selectedHistory.value = entry.id; history.value = [entry, ...history.value]
    if (!quiet) historyError.value = ''
    return null
  } catch (e) {
    const message = `Результат показан, но не сохранён в браузере: ${String(e)}. Скачайте JSON.`
    if (!quiet) historyError.value = message
    return message
  }
}
async function openSaved() {
  if (busy.value || historyBusy.value || !selectedHistory.value) return
  historyBusy.value = true; historyError.value = ''; opened.value = null
  try { result.value = await loadGroundingResult(selectedHistory.value); savedEntry.value = history.value.find(e => e.id === selectedHistory.value) ?? null; useSavedSettings(result.value) }
  catch (e) { historyError.value = String(e) } finally { historyBusy.value = false }
}
async function importResult(event: Event) {
  const input = event.target as HTMLInputElement, files = Array.from(input.files ?? [])
  if (!files.length || busy.value || historyBusy.value) return
  importFailures.value = []; importSummary.value = ''; historyError.value = ''
  if (files.length > maxImportFiles) {
    importFailures.value = [`Выбрано ${files.length} файлов. Можно не более ${maxImportFiles} за один импорт. Ни один файл из этого выбора не импортирован.`]
    input.value = ''; return
  }
  historyBusy.value = true
  let saved = 0
  try {
    for (const file of files) {
      try {
        if (file.size > MAX_IMPORT_BYTES) throw new Error('Файл больше 20 МБ.')
        const imported = parseGroundingImport(await file.text())
        result.value = imported; savedEntry.value = { id: '', savedAt: new Date().toISOString(), origin: 'import', fileName: file.name, question: imported.request.question, indexId: imported.request.indexId, status: imported.status }; selectedHistory.value = ''; opened.value = null
        useSavedSettings(imported)
        const saveError = await remember(imported, 'import', file.name, true)
        if (saveError) importFailures.value.push(`${file.name}: ${saveError}`)
        else saved++
      } catch (e) { importFailures.value.push(`${file.name}: ${e instanceof Error ? e.message : String(e)}`) }
    }
    importSummary.value = `Импорт завершён: сохранено ${saved} из ${files.length}. Успешные результаты доступны в списке; ошибки не удаляют их.`
  } finally { historyBusy.value = false; input.value = '' }
}
function savedLabel(entry: GroundingHistoryEntry) { return `${new Date(entry.savedAt).toLocaleString('ru-RU')} · ${entry.origin === 'import' ? 'импорт' : entry.status} · ${entry.question}` }
function useSavedSettings(value: Schema<'GroundedResult'>) {
  const r = value.request
  question.value = r.question; indexId.value = r.indexId; candidateK.value = r.candidateTopK; finalK.value = r.finalTopK
  threshold.value = r.similarityThreshold; budget.value = r.contextMaxCharacters; useRewrite.value = r.useRewrite; maxOutput.value = r.maxOutputTokens ?? null; selectedCase.value = ''
}
async function openCitation(citation: Schema<'VerifiedCitation'>) {
  try {
    if (!result.value) return
    const doc = await api.indexDocument(result.value.request.indexId, citation.source.documentId)
    if (doc.text.slice(citation.canonicalStart, citation.canonicalEndExclusive) !== citation.quote) throw new Error('Цитата не совпала с документом snapshot. Не показываем неверную подсветку.')
    opened.value = { document: doc, citation }
  } catch (e) { error.value = String(e) }
}
</script>

<template>
  <section class="content-panel grounding-lab">
    <h2>Ответ, который можно сопоставить с книгой</h2>
    <p>Сначала сервер проверяет точность цитат, затем отдельный LLM-вызов — поддержку каждого утверждения. Неподтверждённый ответ не публикуется. Проверка снижает риск ошибки, но не гарантирует истинность.</p>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <section class="grounding-history" aria-label="Сохранённые результаты дня 24">
      <h3>Сохранённые результаты</h3>
      <p class="hint">Ответы сохраняются в этом браузере, включая отказы и диагностику. Это отдельные запросы, не диалог. Перезапуск сервера не удаляет их; другой браузер и очистка данных сайта не перенесут историю. Для резервной копии скачайте JSON.</p>
      <label>Выбрать сохранённый результат<select v-model="selectedHistory" :disabled="busy || historyBusy" @change="openSaved"><option value="">{{ history.length ? 'Выберите результат' : 'Пока нет сохранённых результатов' }}</option><option v-for="entry in history" :key="entry.id" :value="entry.id">{{ savedLabel(entry) }}</option></select></label>
      <details><summary>Открыть ранее скачанный JSON без нового запроса к LLM</summary><label>Импорт JSON-ответов<input type="file" multiple accept=".json,application/json" :disabled="busy || historyBusy" @change="importResult"></label><p class="hint">До 20 файлов за один выбор, до 20 МБ каждый. В каждом файле — один ответ. Проверяется формат, но не происхождение и не истинность. Импорт всегда помечается отдельно; это не повторная проверка текущей версией приложения.</p></details>
      <p v-if="importSummary" class="import-summary" role="status">{{ importSummary }}</p>
      <ul v-if="importFailures.length" class="import-errors notice error" role="alert"><li v-for="(failure, i) in importFailures" :key="i">{{ failure }}</li></ul>
      <p v-if="historyError" class="notice error" role="alert">{{ historyError }}</p>
    </section>
    <fieldset class="answer-controls" :disabled="busy">
      <label>Контрольный вопрос дня 24<select v-model="selectedCase" @change="expected && (question = expected.question)"><option value="">Свой вопрос</option><option v-for="q in questions" :key="q.id" :value="q.id">{{ q.id }} — {{ q.question }}</option></select></label>
      <details v-if="expected"><summary>Ожидание для проверки</summary><p>{{ expected.expected }}</p><small>{{ expected.expectedSourceSuffix }}</small></details>
      <label>Вопрос с источниками<textarea v-model="question" rows="3" maxlength="2000" /></label>
      <div class="answer-config">
        <label>Индекс источников<select v-model="indexId"><option value="">Выберите индекс</option><option v-for="i in indexes" :key="i.id" :value="i.id">{{ i.config.strategy }} · {{ i.metrics.chunkCount }} чанков · {{ i.config.maxCharacters }}/{{ i.config.overlapCharacters }}</option></select></label>
        <label>Кандидатов поиска<input v-model.number="candidateK" type="number" min="1" max="20"></label>
        <label>Максимум отобранных<input v-model.number="finalK" type="number" min="1" :max="Math.min(10, candidateK)"></label>
        <label>Порог релевантности<input v-model.number="threshold" type="number" min="-1" max="1" step="0.01"></label>
        <label>Бюджет текста источников<input v-model.number="budget" type="number" min="300" max="60000" step="100"></label>
        <label>Лимит structured ответа<input v-model.number="maxOutput" type="number" min="1" max="32768" placeholder="Не задан"></label>
      </div>
      <label class="rewrite-toggle"><input v-model="useRewrite" type="checkbox">Переформулировать запрос поиска · один дополнительный LLM-вызов</label>
    </fieldset>
    <p class="hint">Низкий score → «не знаю», без генерации. После смыслового отклонения возможна одна попытка исправления черновика. Неверная цитата, обрезанный JSON и ошибки связи не запускают повтор. Пустой лимит не передаёт max_tokens.</p>
    <button class="primary" :disabled="busy || historyBusy || invalid" @click="run">{{ busy ? 'Ищем и проверяем…' : `Ответить с цитатами · до ${useRewrite ? 51 : 50} API-вызовов` }}</button>
    <p v-if="settings && !settings.configured" class="hint">Ключ не настроен. Поиск и отказ по порогу доступны; непустой контекст потребует серверный ключ.</p>
    <article v-if="result" class="grounded-result" :data-status="result.status">
      <p v-if="savedEntry" class="saved-result-label">{{ savedEntry.origin === 'import' ? 'Импортированный результат' : 'Сохранённый результат' }} · {{ new Date(savedEntry.savedAt).toLocaleString('ru-RU') }} · индекс {{ savedEntry.indexId }}</p>
      <p v-if="savedEntry?.origin === 'import'" class="notice">Файл {{ savedEntry.fileName }}. Дата выше — время импорта, не выполнения. Статус и проверки взяты из файла и не подтверждены этим приложением. Открытие цитаты сверяет только точный текст с доступным snapshot, не смысл ответа.</p>
      <header><h3>{{ savedEntry?.origin === 'import' ? `Статус в файле: ${result.status}` : statusLabels[result.status] }}</h3><small>{{ result.status }} · {{ result.totalMilliseconds }} мс</small></header>
      <p class="saved-question">{{ result.request.question }}</p>
      <template v-if="result.status === 'ANSWERED'">
        <section v-for="(claim, n) in result.claims" :key="n" class="grounded-claim">
          <p class="claim-text">{{ claim.text }}</p>
          <blockquote v-for="(c, j) in claim.citations" :key="j" class="evidence-quote"><p>{{ c.quote }}</p><footer>{{ c.source.source }}<br>{{ c.source.section }} · <code>{{ c.source.chunkId }}</code><br><button @click="openCitation(c)">Открыть цитату в источнике</button></footer></blockquote>
        </section>
        <details class="verified-sources"><summary>Все источники ответа · {{ result.sources.length }}</summary><ul><li v-for="s in result.sources" :key="s.chunkId">{{ s.source }} / {{ s.section }} · {{ s.chunkId }}</li></ul></details>
      </template>
      <template v-else><p class="notice">{{ result.answer }}</p><p v-if="result.clarification" class="clarification">{{ result.clarification }}</p></template>
      <ul v-if="result.issues.length" class="notice error"><li v-for="(issue, n) in result.issues" :key="n">{{ issue.message }} · {{ issue.code }}</li></ul>
      <p class="hint">LLM-стадий: {{ result.llmStagesAttempted }} · API input/output/total: {{ result.llmStagesAttempted ? usageText(result.totalUsage) : 'LLM не вызывалась' }}</p>
      <p v-if="result.estimatedCost" class="hint">Оценка USD: {{ result.estimatedCost.minimumUsd.toFixed(6) }}–{{ result.estimatedCost.maximumUsd.toFixed(6) }}, не списание.</p>
      <p v-if="result.rewrite" class="hint">Rewrite только для поиска: {{ result.rewrite.query }}</p>
      <GroundingRepairTrace v-if="result.repair" :repair="result.repair" />
      <details v-if="result.supportCheck" class="unverified-diagnostics"><summary>Проверка смысловой поддержки · {{ result.supportCheck.status }}</summary><p>Изолированных проверок: {{ supportGenerations(result.supportCheck).length }}. Объяснения — диагностика, не новые факты.</p><p v-for="(g, i) in supportGenerations(result.supportCheck)" :key="i">Вызов {{ i + 1 }} · {{ g.model }} · {{ g.finishReason }} · {{ usageText(g.usage) }}</p><p v-for="c in result.supportCheck.claims" :key="c.claimIndex">Пункт {{ c.claimIndex + 1 }} · {{ c.verdict }}: {{ c.reason }}</p></details>
      <details v-if="result.retrieval"><summary>Поиск и реально переданный контекст</summary><p>{{ result.retrieval.searchQuery }} · найдено {{ result.retrieval.rawCandidates.length }} → отобрано {{ result.retrieval.selectedCandidates.length }} → передано {{ result.retrieval.included.length }}</p><p>Не поместилось по бюджету: {{ result.retrieval.omittedChunkIds.length }}</p><ul><li v-for="d in result.retrieval.decisions" :key="d.hit.chunk.chunkId">{{ d.hit.similarity.toFixed(4) }} · {{ reasonLabels[d.reason] }} · {{ d.hit.chunk.section }} · {{ d.hit.chunk.chunkId }}</li></ul></details>
      <details v-if="result.generation" class="unverified-diagnostics"><summary>Диагностика LLM · исходный JSON не является проверенным ответом</summary><p>{{ result.generation.model }} · {{ result.generation.finishReason }} · {{ result.generation.milliseconds }} мс</p><pre v-for="(m, n) in result.generation.messages" :key="n">{{ m.role }}: {{ m.content }}</pre><pre>{{ result.generation.rawJson }}</pre></details>
      <ul class="answer-warnings"><li v-for="w in result.warnings" :key="w">{{ w }}</li></ul>
      <div class="actions"><button @click="download('day24-grounded.md', groundedMarkdown(result))">Скачать ответ с цитатами MD</button><button @click="download('day24-grounded.json', JSON.stringify(result, null, 2), 'application/json')">Скачать ответ с цитатами JSON</button></div>
    </article>
    <div v-if="opened" class="modal-backdrop" @click.self="opened = null"><section class="document-modal" role="dialog" aria-modal="true" aria-labelledby="citation-title"><header><h2 id="citation-title">{{ opened.document.title }}</h2><button class="icon-button" aria-label="Закрыть цитату" @click="opened = null">×</button></header><p>Цитата совпала с snapshot: {{ result?.snapshotId }}. Координаты UTF-16: {{ opened.citation.canonicalStart }}–{{ opened.citation.canonicalEndExclusive }}.</p><details><summary>Документ целиком с подсветкой цитаты</summary><pre>{{ opened.document.text.slice(0, opened.citation.canonicalStart) }}<mark>{{ opened.citation.quote }}</mark>{{ opened.document.text.slice(opened.citation.canonicalEndExclusive) }}</pre></details><blockquote class="evidence-quote">{{ opened.citation.quote }}</blockquote></section></div>
  </section>
</template>

<style scoped>
.grounding-history { border: 1px solid #dbe3d8; border-radius: 12px; padding: 16px; margin: 20px 0; min-width: 0; }
.grounding-history label { display: grid; gap: 8px; min-width: 0; }
.grounding-history select, .grounding-history input { width: 100%; min-width: 0; max-width: 100%; box-sizing: border-box; }
.saved-result-label { font-size: 13px; color: #4b6250; overflow-wrap: anywhere; }
</style>
