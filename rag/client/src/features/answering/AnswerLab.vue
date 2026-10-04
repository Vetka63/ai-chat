<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, type Schema } from '../../api/client'
import { download } from '../lab/report'
import { answersMarkdown } from './report'
import './answering.css'

const props = defineProps<{ indexes: Schema<'IndexInfo'>[] }>()
type Mode = Schema<'AnswerRequest'>['mode']
type Slot = { mode: Mode; pending: boolean; result?: Schema<'AnswerResult'>; error?: string }
const settings = ref<Schema<'AnswerSettings'> | null>(null)
const questions = ref<Schema<'ControlQuestion'>[]>([])
const selectedCase = ref('')
const question = ref('Что изменяют git reset --soft, --mixed и --hard? Объясни риски.')
const indexId = ref(props.indexes.find(i => i.config.strategy === 'STRUCTURAL')?.id ?? props.indexes[0]?.id ?? '')
const topK = ref(5)
const contextMaxCharacters = ref(16000)
const maxOutputTokens = ref<number | null>(null)
const running = ref(false)
const loadError = ref('')
const slots = ref<Slot[]>([])
const resultCase = ref<Schema<'ControlQuestion'> | undefined>()
const source = ref<Schema<'Document'> | null>(null)
const sourceError = ref('')
const expected = computed(() => questions.value.find(q => q.id === selectedCase.value))
const results = computed(() => slots.value.flatMap(s => s.result ? [s.result] : []))

onMounted(async () => {
  try { [settings.value, questions.value] = await Promise.all([api.answerSettings(), api.questions()]) }
  catch (error) { loadError.value = error instanceof Error ? error.message : 'Настройки недоступны.' }
})
function chooseQuestion() { if (expected.value) question.value = expected.value.question }
/** Оба режима получают один снимок параметров; ошибка одной колонки не стирает другую. */
async function run(modes: Mode[]) {
  if (running.value || !question.value.trim()) return
  running.value = true
  resultCase.value = expected.value?.question === question.value.trim() ? expected.value : undefined
  const base = { question: question.value.trim(), indexId: indexId.value || null, topK: topK.value, contextMaxCharacters: contextMaxCharacters.value, maxOutputTokens: typeof maxOutputTokens.value === 'number' ? maxOutputTokens.value : null }
  slots.value = modes.map(mode => ({ mode, pending: true }))
  await Promise.all(modes.map(async (mode, position) => {
    try { slots.value[position] = { mode, pending: false, result: await api.answer({ ...base, mode }) } }
    catch (error) { slots.value[position] = { mode, pending: false, error: error instanceof Error ? error.message : 'Запрос не выполнен.' } }
  }))
  running.value = false
}
async function openSource(index: string | null, documentId: string) {
  if (!index) return
  sourceError.value = ''
  try { source.value = await api.indexDocument(index, documentId) }
  catch (error) { sourceError.value = error instanceof Error ? error.message : 'Источник недоступен.' }
}
</script>

<template>
  <section class="content-panel answer-lab">
    <h2>Один вопрос. Два способа ответа.</h2>
    <p>Без RAG модель использует свои знания. С RAG — получает найденные фрагменты Pro Git. Истории диалога здесь пока нет.</p>
    <div v-if="loadError" class="notice error" role="alert">{{ loadError }}</div>
    <div v-if="settings && !settings.configured" class="notice error" role="alert">Добавьте DEEPSEEK_API_KEY только на backend и пересоздайте контейнер. Ключ не вводится в браузере.</div>
    <p v-if="settings" class="hint">Модель: {{ settings.model }} · temperature={{ settings.temperature }} · thinking={{ settings.thinking }}. Настройки одинаковые для двух режимов.</p>
    <fieldset :disabled="running" class="answer-controls">
      <label>Контрольный вопрос<select v-model="selectedCase" @change="chooseQuestion"><option value="">Свой вопрос</option><option v-for="item in questions" :key="item.id" :value="item.id">{{ item.id }} — {{ item.question }}</option></select></label>
      <details v-if="expected" class="quiet-details"><summary>Ожидание и источник для проверки</summary><p>{{ expected.expected }}</p><small>{{ expected.expectedSourceSuffix }}</small><blockquote>{{ expected.evidenceQuote }}</blockquote></details>
      <label class="query-label">Вопрос для сравнения<textarea v-model="question" rows="3" maxlength="2000" /></label>
      <div class="answer-config">
        <label class="answer-index">Индекс для RAG<select v-model="indexId"><option value="">Выберите индекс</option><option v-for="index in indexes" :key="index.id" :value="index.id">{{ index.config.strategy }} · {{ index.metrics.chunkCount }} чанков · {{ index.config.maxCharacters }}/{{ index.config.overlapCharacters }}</option></select></label>
        <label>Top-K<input v-model.number="topK" type="number" min="1" max="10"></label>
        <label>Бюджет текста чанков<input v-model.number="contextMaxCharacters" type="number" min="300" max="60000" step="100"></label>
        <label>Лимит ответа, токены<input v-model.number="maxOutputTokens" type="number" min="1" max="32768" placeholder="Не задан"></label>
      </div>
    </fieldset>
    <p class="hint">Бюджет — символы целых чанков, не токены всей заявки. Пустой лимит ответа: приложение не передаёт max_tokens; остаются ограничения провайдера.</p>
    <div class="actions">
      <button class="primary" :disabled="running || !settings?.configured || !question.trim() || !indexId" @click="run(['BASELINE', 'RAG'])">{{ running ? 'Получаем ответы…' : 'Сравнить ответы · 2 API-вызова' }}</button>
      <button :disabled="running || !settings?.configured || !question.trim()" @click="run(['BASELINE'])">Только без RAG</button>
      <button :disabled="running || !settings?.configured || !question.trim() || !indexId" @click="run(['RAG'])">Только с RAG</button>
    </div>
    <p class="hint">Вызовы DeepSeek платные. Нет автоматических повторов. Сравнение отправляет два независимых запроса.</p>
    <div class="answer-columns">
      <article v-for="slot in slots" :key="slot.mode" class="answer-card" :data-mode="slot.mode">
        <h3>{{ slot.mode === 'RAG' ? 'С RAG · книга в контексте' : 'Без RAG · знания модели' }}</h3>
        <p v-if="slot.pending" role="status">Ждём финальный ответ DeepSeek…</p>
        <div v-if="slot.error" class="notice error" role="alert">{{ slot.error }}</div>
        <template v-if="slot.result">
          <p class="hint">{{ slot.result.model }} · {{ (slot.result.totalMilliseconds / 1000).toFixed(2) }} с · {{ slot.result.finishReason }}</p>
          <div v-if="slot.result.truncated" class="notice error">Ответ неполный: провайдер сообщил finish_reason=length.</div>
          <div class="answer-text">{{ slot.result.answer }}</div>
          <dl class="answer-usage"><dt>Input / output / total API</dt><dd>{{ slot.result.usage ? `${slot.result.usage.promptTokens} / ${slot.result.usage.completionTokens} / ${slot.result.usage.totalTokens}` : 'Usage отсутствует' }}</dd><dt>Время LLM / retrieval</dt><dd>{{ slot.result.generationMilliseconds }} / {{ slot.result.context.retrievalMilliseconds }} мс</dd><dt>Cache hit / miss API</dt><dd>{{ slot.result.usage?.cacheHitTokens ?? 'неизвестно' }} / {{ slot.result.usage?.cacheMissTokens ?? 'неизвестно' }}</dd></dl>
          <p v-if="slot.result.estimatedCost" class="hint">Оценка USD: {{ slot.result.estimatedCost.minimumUsd.toFixed(6) }}–{{ slot.result.estimatedCost.maximumUsd.toFixed(6) }}. <a :href="slot.result.estimatedCost.source" target="_blank" rel="noreferrer">Тариф</a> · {{ slot.result.estimatedCost.verifiedOn }}</p>
          <details class="quiet-details"><summary>Переданный контекст · {{ slot.result.context.included.length }} чанков</summary><p class="hint">{{ slot.result.context.textCharacters }} / {{ slot.result.context.maxCharacters }} символов; исключено {{ slot.result.context.omittedChunkIds.length }}.</p><article v-for="hit in slot.result.context.included" :key="hit.chunk.chunkId" class="search-hit"><small>{{ hit.chunk.source }} · {{ hit.chunk.section }} · cosine={{ hit.similarity.toFixed(4) }}</small><small>chunk_id={{ hit.chunk.chunkId }}</small><pre>{{ hit.chunk.text }}</pre><button @click="openSource(slot.result.context.indexId, hit.chunk.documentId)">Открыть источник ответа</button></article><p v-if="!slot.result.context.included.length">Индекс и Ollama не вызывались.</p></details>
          <details class="quiet-details"><summary>Что отправлено в LLM</summary><div v-for="(message, position) in slot.result.messages" :key="position"><strong>{{ message.role }}</strong><pre class="prompt-text">{{ message.content }}</pre></div></details>
          <ul class="answer-warnings"><li v-for="warning in slot.result.warnings" :key="warning">{{ warning }}</li></ul>
        </template>
      </article>
    </div>
    <div v-if="results.length && !running" class="actions"><button @click="download('day22-rag-comparison.md', answersMarkdown(results, resultCase))">Скачать сравнение MD</button><button @click="download('day22-rag-comparison.json', JSON.stringify({ expected: resultCase, results }, null, 2), 'application/json')">Скачать результаты JSON</button></div>
    <div v-if="sourceError" class="notice error" role="alert">{{ sourceError }}</div>
    <div v-if="source" class="modal-backdrop" @click.self="source = null"><section class="document-modal" role="dialog" aria-modal="true" aria-labelledby="answer-source-title"><header><h2 id="answer-source-title">{{ source.title }}</h2><button class="icon-button" @click="source = null" aria-label="Закрыть источник ответа">×</button></header><pre>{{ source.text }}</pre></section></div>
  </section>
</template>
