<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, type Schema } from '../../api/client'
import { download } from '../lab/report'
import { usageText, reasonLabels } from '../experiments/report'
import { groundedMarkdown } from './report'
import GroundingRepairTrace from './GroundingRepairTrace.vue'
import '../answering/answering.css'
import './grounding.css'

const props = defineProps<{ indexes: Schema<'IndexInfo'>[] }>()
const indexId = ref(props.indexes.find(i => i.config.strategy === 'STRUCTURAL')?.id ?? props.indexes[0]?.id ?? '')
const question = ref('Как git stash сохранить untracked-файлы? Включает ли -u файлы из .gitignore?')
const questions = ref<Schema<'ControlQuestion'>[]>([])
const selectedCase = ref('')
const expected = computed(() => questions.value.find(q => q.id === selectedCase.value))
const settings = ref<Schema<'AnswerSettings'> | null>(null)
const candidateK = ref(10), finalK = ref(5), threshold = ref(.65), budget = ref(16000)
const useRewrite = ref(false), maxOutput = ref<number | null>(null)
const busy = ref(false), error = ref('')
const result = ref<Schema<'GroundedResult'> | null>(null)
const opened = ref<{ document: Schema<'Document'>; citation: Schema<'VerifiedCitation'> } | null>(null)
const invalid = computed(() => !question.value.trim() || !indexId.value || finalK.value > candidateK.value)
const statusLabels = { ANSWERED: 'Цитаты проверены', UNKNOWN: 'Не знаю по найденным материалам', INVALID_EVIDENCE: 'Ответ не прошёл проверку', ERROR: 'Ошибка стадии' }
function close(e: KeyboardEvent) { if (e.key === 'Escape') opened.value = null }
onMounted(async () => { try { [settings.value, questions.value] = await Promise.all([api.answerSettings(), api.questions()]) } catch (e) { error.value = String(e) }; window.addEventListener('keydown', close) })
onUnmounted(() => window.removeEventListener('keydown', close))
async function run() {
  if (busy.value || invalid.value) return
  busy.value = true; error.value = ''; result.value = null; opened.value = null
  const body: Schema<'GroundingRequest'> = { question: question.value.trim(), indexId: indexId.value, candidateTopK: candidateK.value, finalTopK: finalK.value, similarityThreshold: threshold.value, contextMaxCharacters: budget.value, useRewrite: useRewrite.value, maxOutputTokens: typeof maxOutput.value === 'number' ? maxOutput.value : null }
  try { result.value = await api.groundedAnswer(body) } catch (e) { error.value = e instanceof Error ? e.message : String(e) } finally { busy.value = false }
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
    <button class="primary" :disabled="busy || invalid" @click="run">{{ busy ? 'Ищем и проверяем…' : `Ответить с цитатами · до ${useRewrite ? 5 : 4} API-вызовов` }}</button>
    <p v-if="settings && !settings.configured" class="hint">Ключ не настроен. Поиск и отказ по порогу доступны; непустой контекст потребует серверный ключ.</p>
    <article v-if="result" class="grounded-result" :data-status="result.status">
      <header><h3>{{ statusLabels[result.status] }}</h3><small>{{ result.status }} · {{ result.totalMilliseconds }} мс</small></header>
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
      <details v-if="result.supportCheck" class="unverified-diagnostics"><summary>Проверка смысловой поддержки · {{ result.supportCheck.status }}</summary><p>Дополнительный LLM-вызов: {{ usageText(result.supportCheck.generation.usage) }}. Объяснения проверяющей модели — диагностика, не новые факты.</p><p v-for="c in result.supportCheck.claims" :key="c.claimIndex">Пункт {{ c.claimIndex + 1 }} · {{ c.verdict }}: {{ c.reason }}</p></details>
      <details v-if="result.retrieval"><summary>Поиск и реально переданный контекст</summary><p>{{ result.retrieval.searchQuery }} · найдено {{ result.retrieval.rawCandidates.length }} → отобрано {{ result.retrieval.selectedCandidates.length }} → передано {{ result.retrieval.included.length }}</p><p>Не поместилось по бюджету: {{ result.retrieval.omittedChunkIds.length }}</p><ul><li v-for="d in result.retrieval.decisions" :key="d.hit.chunk.chunkId">{{ d.hit.similarity.toFixed(4) }} · {{ reasonLabels[d.reason] }} · {{ d.hit.chunk.section }} · {{ d.hit.chunk.chunkId }}</li></ul></details>
      <details v-if="result.generation" class="unverified-diagnostics"><summary>Диагностика LLM · исходный JSON не является проверенным ответом</summary><p>{{ result.generation.model }} · {{ result.generation.finishReason }} · {{ result.generation.milliseconds }} мс</p><pre v-for="(m, n) in result.generation.messages" :key="n">{{ m.role }}: {{ m.content }}</pre><pre>{{ result.generation.rawJson }}</pre></details>
      <ul class="answer-warnings"><li v-for="w in result.warnings" :key="w">{{ w }}</li></ul>
      <div class="actions"><button @click="download('day24-grounded.md', groundedMarkdown(result))">Скачать ответ с цитатами MD</button><button @click="download('day24-grounded.json', JSON.stringify(result, null, 2), 'application/json')">Скачать ответ с цитатами JSON</button></div>
    </article>
    <div v-if="opened" class="modal-backdrop" @click.self="opened = null"><section class="document-modal" role="dialog" aria-modal="true" aria-labelledby="citation-title"><header><h2 id="citation-title">{{ opened.document.title }}</h2><button class="icon-button" aria-label="Закрыть цитату" @click="opened = null">×</button></header><p>Цитата совпала с snapshot: {{ result?.snapshotId }}. Координаты UTF-16: {{ opened.citation.canonicalStart }}–{{ opened.citation.canonicalEndExclusive }}.</p><details><summary>Документ целиком с подсветкой цитаты</summary><pre>{{ opened.document.text.slice(0, opened.citation.canonicalStart) }}<mark>{{ opened.citation.quote }}</mark>{{ opened.document.text.slice(opened.citation.canonicalEndExclusive) }}</pre></details><blockquote class="evidence-quote">{{ opened.citation.quote }}</blockquote></section></div>
  </section>
</template>
