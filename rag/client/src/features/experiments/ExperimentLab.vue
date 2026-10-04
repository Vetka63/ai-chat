<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, type Schema } from '../../api/client'
import { download } from '../lab/report'
import { experimentMarkdown, modeLabels, reasonLabels, usageText } from './report'
import '../answering/answering.css'
import './experiments.css'

const props = defineProps<{ indexes: Schema<'IndexInfo'>[] }>()
const settings = ref<Schema<'AnswerSettings'> | null>(null)
const questions = ref<Schema<'ControlQuestion'>[]>([])
const selectedCase = ref('')
const expected = computed(() => questions.value.find(q => q.id === selectedCase.value))
const submittedExpected = ref<Schema<'ControlQuestion'>>()
const question = ref('Как сохранить новые файлы, которых Git ещё не отслеживает, при временном откладывании изменений?')
const indexId = ref(props.indexes.find(i => i.config.strategy === 'STRUCTURAL')?.id ?? props.indexes[0]?.id ?? '')
const modes = ref<Schema<'RetrievalMode'>[]>(['RAW', 'FILTERED', 'REWRITE_FILTERED'])
const candidateTopK = ref(10)
const finalTopK = ref(5)
const threshold = ref(0.65)
const budget = ref(16000)
const maxOutput = ref<number | null>(null)
const busy = ref(false)
const error = ref('')
const result = ref<Schema<'ExperimentComparison'> | null>(null)
const source = ref<Schema<'Document'> | null>(null)
const modeKeys = Object.keys(modeLabels) as Schema<'RetrievalMode'>[]
const needsRewrite = computed(() => modes.value.some(m => m === 'REWRITE' || m === 'REWRITE_FILTERED'))
const invalid = computed(() => !question.value.trim() || !indexId.value || !modes.value.length || finalTopK.value > candidateTopK.value)
const paidMax = computed(() => modes.value.length + (needsRewrite.value ? 1 : 0))

onMounted(async () => { try { [settings.value, questions.value] = await Promise.all([api.answerSettings(), api.questions()]) } catch (e) { error.value = String(e) }; window.addEventListener('keydown', closeSource) })
onUnmounted(() => window.removeEventListener('keydown', closeSource))
function closeSource(e: KeyboardEvent) { if (e.key === 'Escape') source.value = null }
function chooseQuestion() { if (expected.value) question.value = expected.value.question }
async function run(generateAnswers: boolean) {
  if (busy.value || invalid.value) return
  busy.value = true; error.value = ''; result.value = null
  submittedExpected.value = expected.value?.question === question.value.trim() ? expected.value : undefined
  const body: Schema<'ExperimentRequest'> = { question: question.value.trim(), indexId: indexId.value, modes: [...modes.value], candidateTopK: candidateTopK.value, finalTopK: finalTopK.value, similarityThreshold: threshold.value, contextMaxCharacters: budget.value, maxOutputTokens: typeof maxOutput.value === 'number' ? maxOutput.value : null, generateAnswers }
  try { result.value = await api.experiment(body) } catch (e) { error.value = e instanceof Error ? e.message : 'Эксперимент не выполнен' } finally { busy.value = false }
}
async function openSource(id: string) { try { if (result.value) source.value = await api.indexDocument(result.value.request.indexId, id) } catch (e) { error.value = String(e) } }
</script>

<template>
  <section class="content-panel experiment-lab">
    <h2>Что меняется после поиска</h2>
    <p>Один вопрос, общий индекс и одинаковый промпт ответа. Rewrite меняет только поисковый запрос; фильтр отбирает кандидатов по cosine.</p>
    <p v-if="settings" class="hint">{{ settings.model }} · temperature={{ settings.temperature }} · thinking={{ settings.thinking }}</p>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <fieldset :disabled="busy" class="answer-controls">
      <label>Контрольный вопрос дня 23<select v-model="selectedCase" @change="chooseQuestion"><option value="">Свой вопрос</option><option v-for="q in questions" :key="q.id" :value="q.id">{{ q.id }} — {{ q.question }}</option></select></label>
      <details v-if="expected"><summary>Ожидание для проверки</summary><p>{{ expected.expected }}</p><small>{{ expected.expectedSourceSuffix }}</small></details>
      <label>Вопрос эксперимента<textarea v-model="question" maxlength="2000" rows="3" /></label>
      <div class="mode-picker"><label v-for="mode in modeKeys" :key="mode"><input v-model="modes" type="checkbox" :value="mode">{{ modeLabels[mode] }}</label></div>
      <div class="answer-config">
        <label class="answer-index">Индекс эксперимента<select v-model="indexId"><option value="">Выберите индекс</option><option v-for="i in indexes" :key="i.id" :value="i.id">{{ i.config.strategy }} · {{ i.metrics.chunkCount }} чанков · {{ i.config.maxCharacters }}/{{ i.config.overlapCharacters }}</option></select></label>
        <label>Кандидатов до отбора<input v-model.number="candidateTopK" type="number" min="1" max="20"></label>
        <label>Максимум после отбора<input v-model.number="finalTopK" type="number" min="1" :max="Math.min(10, candidateTopK)"></label>
        <label>Порог cosine<input v-model.number="threshold" type="number" min="-1" max="1" step="0.01"></label>
        <label>Бюджет текста чанков<input v-model.number="budget" type="number" min="300" max="60000" step="100"></label>
        <label>Лимит ответа, токены<input v-model.number="maxOutput" type="number" min="1" max="32768" placeholder="Не задан"></label>
      </div>
    </fieldset>
    <p v-if="finalTopK > candidateTopK" class="notice error">Максимум после отбора не может быть больше числа кандидатов.</p>
    <p class="hint">Порог — не вероятность. Сначала threshold, затем final top-K, затем бюджет целых чанков. Пустой лимит ответа не передаёт max_tokens. Rewrite имеет отдельный технический лимит 512.</p>
    <div class="actions"><button class="primary" :disabled="busy || invalid || !settings?.configured" @click="run(true)">{{ busy ? 'Выполняем эксперимент…' : `Сравнить ответы · до ${paidMax} API-вызовов` }}</button><button :disabled="busy || invalid || needsRewrite && !settings?.configured" @click="run(false)">Только поиск · {{ needsRewrite ? '1 платный rewrite' : 'без LLM' }}</button></div>
    <p class="hint">Один общий rewrite и один поиск на каждый поисковый запрос. Ответы независимы; без отобранного контекста генерация не выполняется. Автоматических повторов нет.</p>
    <template v-if="result">
      <div class="experiment-summary"><strong>Эксперимент на сохранённом вопросе</strong><p>{{ result.request.question }}</p><p>Candidate K={{ result.request.candidateTopK }} · final K={{ result.request.finalTopK }} · threshold={{ result.request.similarityThreshold }} · {{ result.totalMilliseconds }} мс</p><p>LLM-стадий: {{ result.llmStagesAttempted }} · API input/output/total: {{ result.llmStagesAttempted ? usageText(result.totalUsage) : 'LLM не вызывалась' }}</p><p v-if="result.estimatedCost">Общая оценка USD: {{ result.estimatedCost.minimumUsd.toFixed(6) }}–{{ result.estimatedCost.maximumUsd.toFixed(6) }}; rewrite учтён один раз.</p></div>
      <details v-if="result.rewrite" class="rewrite-panel" open><summary>Общий rewrite · только для поиска</summary><p><strong>Было:</strong> {{ result.request.question }}</p><p><strong>Стало:</strong> {{ result.rewrite.query }}</p><p class="hint">{{ result.rewrite.model }} · {{ result.rewrite.milliseconds }} мс · API {{ usageText(result.rewrite.usage) }}</p><details><summary>Промпт и JSON rewrite</summary><pre v-for="(m, n) in result.rewrite.messages" :key="n" class="prompt-text">{{ m.role }}: {{ m.content }}</pre><pre>{{ result.rewrite.rawResponse }}</pre></details></details>
      <div v-if="result.rewriteError" class="notice error" role="alert">Rewrite: {{ result.rewriteError.message }}. Контрольные режимы без rewrite сохранены.</div>
      <div class="experiment-results"><article v-for="item in result.results" :key="item.mode" class="answer-card" :data-mode="item.mode">
        <h3>{{ modeLabels[item.mode] }}</h3><small>{{ item.status }}</small>
        <p v-if="item.message" class="notice">{{ item.message }}</p><div v-if="item.error" class="notice error" role="alert">{{ item.error.message }} · {{ item.error.code }}</div>
        <template v-if="item.pipeline"><p class="query-trace"><strong>Поиск:</strong> {{ item.pipeline.searchQuery }}</p><p class="hint">Найдено {{ item.pipeline.rawCandidates.length }} → отобрано {{ item.pipeline.selectedCandidates.length }} → передано LLM {{ item.answer?.context.included.length ?? 0 }}</p>
          <details><summary>Кандидаты до и после отбора</summary><div class="table-scroll"><table><thead><tr><th>Rank / cosine</th><th>Источник и раздел</th><th>Решение</th></tr></thead><tbody><tr v-for="d in item.pipeline.decisions" :key="d.hit.chunk.chunkId"><td>{{ d.hit.rank }} / {{ d.hit.similarity.toFixed(4) }}</td><td>{{ d.hit.chunk.source }}<br>{{ d.hit.chunk.section }}<br><small>{{ d.hit.chunk.chunkId }}</small></td><td>{{ reasonLabels[d.reason] }}</td></tr></tbody></table></div><details v-for="h in item.pipeline.selectedCandidates" :key="h.chunk.chunkId"><summary>{{ h.chunk.section }}</summary><pre>{{ h.chunk.text }}</pre><button @click="openSource(h.chunk.documentId)">Открыть источник эксперимента</button></details></details>
        </template>
        <template v-if="item.answer"><div v-if="item.answer.truncated" class="notice error">Ответ обрезан: finish_reason=length.</div><div class="answer-text">{{ item.answer.answer }}</div><p class="hint">{{ item.answer.model }} · {{ item.answer.generationMilliseconds }} мс · {{ item.answer.finishReason }}</p><p class="hint">API input/output/total: {{ usageText(item.answer.usage) }}</p><details><summary>Реальный контекст и messages</summary><p>Передано {{ item.answer.context.included.length }} целых чанков; не поместилось {{ item.answer.context.omittedChunkIds.length }}; {{ item.answer.context.textCharacters }} символов.</p><pre v-for="(m, n) in item.answer.messages" :key="n" class="prompt-text">{{ m.role }}: {{ m.content }}</pre></details></template>
      </article></div>
      <ul class="answer-warnings"><li v-for="w in result.warnings" :key="w">{{ w }}</li></ul>
      <div class="actions"><button @click="download('day23-comparison.md', experimentMarkdown(result, submittedExpected))">Скачать эксперимент MD</button><button @click="download('day23-comparison.json', JSON.stringify({ expected: submittedExpected, comparison: result }, null, 2), 'application/json')">Скачать эксперимент JSON</button></div>
    </template>
    <div v-if="source" class="modal-backdrop" @click.self="source = null"><section class="document-modal" role="dialog" aria-modal="true" aria-labelledby="experiment-source-title"><header><h2 id="experiment-source-title">{{ source.title }}</h2><button class="icon-button" @click="source = null" aria-label="Закрыть источник эксперимента">×</button></header><pre>{{ source.text }}</pre></section></div>
  </section>
</template>
