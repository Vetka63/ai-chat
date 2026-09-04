<script setup>
import { computed, ref } from 'vue'
import { judgeModelComparison, runModelComparison } from '../api/chat.js'
import {
  downloadModelComparisonReport,
  summarizeModelComparison,
} from '../utils/modelComparisonReport.js'

const props = defineProps({
  profile: { type: Object, required: true },
  backendState: { type: String, default: 'checking' },
  backendStatusLabel: { type: String, default: 'Подключаемся' },
})

const defaultModels = [
  { id: 'weak', title: 'Слабая модель', description: 'Компактная модель Mistral', provider: 'mistral', model: 'ministral-3b-2512', status: 'idle' },
  { id: 'medium', title: 'Средняя модель', description: 'Быстрая модель DeepSeek', provider: 'deepseek', model: 'deepseek-v4-flash', status: 'idle' },
  { id: 'strong', title: 'Сильная модель', description: 'Мощная модель DeepSeek', provider: 'deepseek', model: 'deepseek-v4-pro', status: 'idle' },
]

const task = ref('Объясни, почему бинарный поиск работает быстрее линейного. Приведи простой пример и укажи временную сложность обоих алгоритмов. Ответ — не более 180 слов.')
const submittedTask = ref('')
const experimentId = ref('')
const results = ref([])
const metrics = ref(null)
const ratings = ref({})
const winner = ref('')
const conclusion = ref('')
const judgeResult = ref(null)
const judgeLoading = ref(false)
const judgeError = ref('')
const isRunning = ref(false)
const error = ref('')
const exportNotice = ref('')

const cards = computed(() => results.value.length ? results.value : defaultModels)
const successfulCards = computed(() => cards.value.filter((card) => card.status === 'success'))
const leaders = computed(() => summarizeModelComparison(cards.value))
const canRunJudge = computed(() => (
  successfulCards.value.length >= 2 && !isRunning.value && !judgeLoading.value
))

function setRating(modelId, value) {
  ratings.value = { ...ratings.value, [modelId]: value }
}

function formatDuration(value) {
  const milliseconds = Number(value) || 0
  if (milliseconds < 1000) return `${milliseconds} мс`
  return `${(milliseconds / 1000).toFixed(milliseconds < 10000 ? 1 : 0)} с`
}

function formatTokens(value) {
  return new Intl.NumberFormat('ru-RU').format(Number(value) || 0)
}

function formatCost(value) {
  if (value === null || value === undefined) return 'Не рассчитана'
  const cost = Number(value) || 0
  if (cost > 0 && cost < 0.000001) return '< $0.000001'
  return `$${cost.toFixed(8)}`
}

async function runExperiment() {
  const value = task.value.trim()
  if (!value || isRunning.value) return

  submittedTask.value = value
  experimentId.value = ''
  results.value = []
  metrics.value = null
  ratings.value = {}
  winner.value = ''
  conclusion.value = ''
  judgeResult.value = null
  judgeError.value = ''
  error.value = ''
  exportNotice.value = ''
  isRunning.value = true
  try {
    const response = await runModelComparison({
      task: value,
      profileId: props.profile.id,
    })
    experimentId.value = response.experimentId
    results.value = response.results || []
    metrics.value = response.metrics || null
  } catch (requestError) {
    error.value = requestError instanceof Error
      ? requestError.message
      : 'Не удалось выполнить сравнение моделей'
  } finally {
    isRunning.value = false
  }
}

async function runJudge() {
  if (!canRunJudge.value) return

  judgeLoading.value = true
  judgeError.value = ''
  try {
    judgeResult.value = await judgeModelComparison({
      task: submittedTask.value,
      profileId: props.profile.id,
      candidates: successfulCards.value.map((card) => ({
        modelId: card.id,
        answer: card.answer,
      })),
    })
    if (!conclusion.value.trim()) {
      conclusion.value = judgeResult.value.summary || ''
    }
  } catch (requestError) {
    judgeError.value = requestError instanceof Error
      ? requestError.message
      : 'Не удалось получить оценку сравнения моделей'
  } finally {
    judgeLoading.value = false
  }
}

function downloadReport() {
  downloadModelComparisonReport({
    experimentId: experimentId.value,
    task: submittedTask.value,
    results: cards.value,
    ratings: ratings.value,
    winner: winner.value,
    conclusion: conclusion.value,
    judgeResult: judgeResult.value,
  })
  exportNotice.value = 'Markdown-отчёт скачан'
}
</script>

<template>
  <section class="experiment-workspace model-workspace" aria-label="Сравнение версий моделей">
    <header class="conversation-header experiment-header">
      <div class="conversation-identity">
        <div class="profile-glyph model-glyph" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <path d="M5 17V9M12 17V5M19 17v-4" />
            <path d="M3 19h18" />
          </svg>
        </div>
        <div>
          <p class="conversation-kicker">Учебный эксперимент</p>
          <h2>{{ profile.name }}</h2>
          <p>{{ profile.description }}</p>
        </div>
      </div>
      <div class="status-pill" :class="`status-pill--${backendState}`">
        <span class="status-dot" aria-hidden="true"></span>
        {{ backendStatusLabel }}
      </div>
    </header>

    <div class="model-scroll">
      <section class="model-hero">
        <div>
          <span class="model-hero__eyebrow">Один запрос · три уровня мощности</span>
          <h3>Что меняется вместе с моделью?</h3>
          <p>Промпт и параметры одинаковы. Сравни качество, задержку, токены и расчётную стоимость.</p>
        </div>
        <div class="model-hero__legend" aria-label="Условия эксперимента">
          <span>temperature 0</span><span>top_p 1</span><span>без истории</span>
        </div>
      </section>

      <form class="model-form" @submit.prevent="runExperiment">
        <label for="model-comparison-task">Одинаковая задача для всех моделей</label>
        <textarea
          id="model-comparison-task"
          v-model="task"
          :disabled="isRunning"
          maxlength="10000"
          rows="4"
        ></textarea>
        <div class="model-form__footer">
          <span>{{ task.length }} / 10 000</span>
          <button type="submit" :disabled="!task.trim() || isRunning">
            {{ isRunning ? 'Сравниваем…' : 'Запустить сравнение' }}
          </button>
        </div>
      </form>

      <p v-if="error" class="model-error" role="alert">{{ error }}</p>

      <section v-if="metrics" class="model-total" aria-label="Общие метрики">
        <div><span>Весь эксперимент</span><strong>{{ formatDuration(metrics.elapsedMs) }}</strong></div>
        <div><span>API-вызовы</span><strong>{{ metrics.apiCalls }}</strong></div>
        <div><span>Все токены</span><strong>{{ formatTokens(metrics.totalTokens) }}</strong></div>
        <div><span>Общая стоимость</span><strong>{{ formatCost(metrics.estimatedCostUsd) }}</strong></div>
      </section>

      <section class="model-grid" aria-label="Ответы моделей">
        <article
          v-for="card in cards"
          :key="card.id"
          class="model-card"
          :class="[`model-card--${card.id}`, { 'model-card--winner': winner === card.id }]"
        >
          <header>
            <span class="model-level">{{ card.title }}</span>
            <strong>{{ card.model }}</strong>
            <p>{{ card.description }}</p>
            <span class="model-provider">{{ card.provider }}</span>
          </header>

          <div v-if="isRunning || card.status === 'loading'" class="model-loading">
            <span></span><span></span><span></span>
            Получаем ответ
          </div>
          <div v-else-if="card.status === 'success'" class="model-answer">{{ card.answer }}</div>
          <div v-else-if="card.status === 'error'" class="model-card-error">{{ card.error }}</div>
          <div v-else class="model-placeholder">Ответ появится после запуска эксперимента.</div>

          <template v-if="card.status === 'success'">
            <dl class="model-metrics">
              <div><dt>Время</dt><dd>{{ formatDuration(card.metrics?.elapsedMs) }}</dd></div>
              <div><dt>Токены</dt><dd>{{ formatTokens(card.metrics?.totalTokens) }}</dd></div>
              <div><dt>Вход / выход</dt><dd>{{ formatTokens(card.metrics?.promptTokens) }} / {{ formatTokens(card.metrics?.completionTokens) }}</dd></div>
              <div><dt>Стоимость</dt><dd>{{ formatCost(card.metrics?.estimatedCostUsd) }}</dd></div>
            </dl>

            <div class="model-rating">
              <span>Качество ответа</span>
              <div>
                <button
                  v-for="value in 5"
                  :key="value"
                  type="button"
                  :aria-label="`Качество: ${value} из 5 для ${card.title}`"
                  :aria-pressed="ratings[card.id] === value"
                  @click="setRating(card.id, value)"
                >{{ value }}</button>
              </div>
            </div>

            <button
              type="button"
              class="model-winner"
              :aria-pressed="winner === card.id"
              @click="winner = winner === card.id ? '' : card.id"
            >{{ winner === card.id ? 'Выбрана лучшей' : 'Выбрать за качество' }}</button>

            <footer>
              <a :href="card.modelUrl" target="_blank" rel="noreferrer">О модели</a>
              <a :href="card.pricingUrl" target="_blank" rel="noreferrer">Pricing</a>
            </footer>
          </template>
        </article>
      </section>

      <section v-if="experimentId" class="model-analysis">
        <div class="model-leaders">
          <div><span>Быстрее</span><strong>{{ leaders.fastest?.title || 'Нет данных' }}</strong></div>
          <div><span>Меньше токенов</span><strong>{{ leaders.fewestTokens?.title || 'Нет данных' }}</strong></div>
          <div><span>Дешевле</span><strong>{{ leaders.cheapest?.title || 'Нет данных' }}</strong></div>
          <div><span>Лучшее качество</span><strong>{{ cards.find(card => card.id === winner)?.title || 'Выберите сами' }}</strong></div>
        </div>

        <section class="model-judge" aria-label="Автоматический судья">
          <header>
            <div>
              <span>DeepSeek Pro · независимая оценка</span>
              <h4>AI-судья качества</h4>
              <p>Ответы обезличиваются перед оценкой. Метрики скорости и стоимости судье не передаются.</p>
            </div>
            <button type="button" :disabled="!canRunJudge" @click="runJudge">
              {{ judgeLoading ? 'Оцениваем…' : judgeResult ? 'Оценить заново' : 'Запустить AI-судью' }}
            </button>
          </header>

          <div v-if="judgeError" class="model-judge__error" role="alert">
            <span>{{ judgeError }}</span>
            <button type="button" :disabled="!canRunJudge" @click="runJudge">Повторить</button>
          </div>

          <div v-else-if="judgeResult" class="model-judge__result" aria-live="polite">
            <div class="model-judge__winner">
              <span>Выбор судьи</span>
              <strong>{{ judgeResult.winnerTitle }}</strong>
              <em v-if="winner">
                {{ winner === judgeResult.winnerModelId
                  ? 'Совпадает с вашим выбором'
                  : 'Отличается от вашего выбора' }}
              </em>
            </div>
            <p class="model-judge__summary">{{ judgeResult.summary }}</p>
            <div class="model-judge__evaluations">
              <article
                v-for="evaluation in judgeResult.evaluations"
                :key="evaluation.modelId"
                :class="{ 'is-winner': evaluation.modelId === judgeResult.winnerModelId }"
              >
                <header>
                  <strong>{{ evaluation.title }}</strong>
                  <span>{{ evaluation.average }} / 10</span>
                </header>
                <div class="model-judge__scores">
                  <span>Точность <b>{{ evaluation.scores.accuracy }}</b></span>
                  <span>Следование задаче <b>{{ evaluation.scores.instructionFollowing }}</b></span>
                  <span>Полнота <b>{{ evaluation.scores.completeness }}</b></span>
                  <span>Ясность <b>{{ evaluation.scores.clarity }}</b></span>
                </div>
                <p><b>Сильные стороны:</b> {{ evaluation.strengths }}</p>
                <p><b>Слабые стороны:</b> {{ evaluation.weaknesses }}</p>
              </article>
            </div>
            <p class="model-judge__metrics">
              Судья: {{ judgeResult.model }} · {{ judgeResult.metrics.apiCalls }} API-выз. ·
              {{ formatTokens(judgeResult.metrics.totalTokens) }} токенов ·
              {{ formatCost(judgeResult.metrics.estimatedCostUsd) }}
            </p>
          </div>

          <p v-else class="model-judge__note">
            Судья создаёт отдельный платный вызов DeepSeek Pro и оценивает только содержание ответов.
          </p>
        </section>

        <label class="model-conclusion">
          <span>Короткий вывод о различиях</span>
          <textarea v-model="conclusion" rows="3" placeholder="Например: сильная модель дала более точный ответ, но компактная оказалась быстрее и дешевле…"></textarea>
        </label>

        <div class="model-report-actions">
          <button type="button" @click="downloadReport">Скачать Markdown</button>
          <span v-if="exportNotice" role="status">{{ exportNotice }}</span>
        </div>

        <p class="model-method-note">
          Время зависит от сети и нагрузки API. У разных провайдеров разные токенизаторы,
          поэтому количество токенов сравнивается ориентировочно. Стоимость рассчитана по тарифу карточки.
        </p>
      </section>
    </div>
  </section>
</template>

<style scoped>
.model-workspace { min-width: 0; min-height: 0; height: 100%; display: grid; grid-template-rows: auto minmax(0, 1fr); overflow: hidden; color: var(--theme-body); background: radial-gradient(circle at 90% 5%, color-mix(in srgb, var(--theme-accent) 12%, transparent), transparent 28rem), var(--theme-workspace); }
.model-scroll { min-height: 0; overflow-y: auto; padding: 24px clamp(18px, 3vw, 42px) 38px; scrollbar-color: color-mix(in srgb, var(--theme-accent) 24%, transparent) transparent; scrollbar-width: thin; }
.model-glyph { background: linear-gradient(145deg, #d9eadc, #edf5e9); color: #466f50; }
.model-glyph svg { width: 25px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.7; }
.model-hero { display: flex; justify-content: space-between; gap: 24px; padding: 24px; border: 1px solid var(--theme-border); border-radius: 24px; background: linear-gradient(135deg, color-mix(in srgb, var(--theme-card) 94%, #dbeedd), color-mix(in srgb, var(--theme-card-hover) 92%, #e9e1cf)); box-shadow: 0 16px 38px var(--theme-shadow); }
.model-hero h3 { margin: 6px 0 8px; font-family: Georgia, serif; font-size: clamp(22px, 2.8vw, 34px); color: var(--theme-text); }
.model-hero p { max-width: 720px; margin: 0; color: var(--theme-muted); line-height: 1.55; }
.model-hero__eyebrow { color: var(--theme-accent-strong); font-size: 11px; font-weight: 800; letter-spacing: .12em; text-transform: uppercase; }
.model-hero__legend { display: flex; flex-wrap: wrap; justify-content: flex-end; align-content: flex-start; gap: 8px; max-width: 260px; }
.model-hero__legend span, .model-provider { padding: 7px 10px; border: 1px solid var(--theme-border); border-radius: 999px; color: var(--theme-muted); background: var(--theme-card); font-size: 10px; font-weight: 700; }
.model-form { margin-top: 18px; padding: 20px; border: 1px solid var(--theme-border); border-radius: 22px; background: var(--theme-card); }
.model-form > label, .model-conclusion > span { display: block; margin-bottom: 9px; color: var(--theme-text); font-size: 12px; font-weight: 800; }
.model-form textarea, .model-conclusion textarea { width: 100%; resize: vertical; box-sizing: border-box; border: 1px solid var(--theme-border); border-radius: 16px; padding: 14px 16px; color: var(--theme-text); background: var(--theme-card-hover); font: inherit; line-height: 1.55; outline: none; }
.model-form textarea:focus, .model-conclusion textarea:focus { border-color: var(--theme-accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--theme-accent) 16%, transparent); }
.model-form__footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 12px; color: var(--theme-muted); font-size: 10px; }
.model-form__footer button, .model-report-actions button { border: 0; border-radius: 14px; padding: 11px 18px; color: #fff; background: linear-gradient(145deg, var(--theme-accent), var(--theme-accent-strong)); font: inherit; font-weight: 800; cursor: pointer; }
.model-form__footer button:disabled { cursor: wait; opacity: .55; }
.model-error, .model-card-error { padding: 13px 15px; border-radius: 14px; color: #9b3f3f; background: color-mix(in srgb, #c85a5a 10%, var(--theme-card)); }
.model-total, .model-leaders { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 18px; }
.model-total div, .model-leaders div { padding: 14px; border: 1px solid var(--theme-border); border-radius: 16px; background: var(--theme-card); }
.model-total span, .model-leaders span { display: block; color: var(--theme-muted); font-size: 10px; }
.model-total strong, .model-leaders strong { display: block; margin-top: 5px; color: var(--theme-text); font-size: 13px; }
.model-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-top: 18px; }
.model-card { display: flex; flex-direction: column; min-width: 0; padding: 19px; border: 1px solid var(--theme-border); border-radius: 22px; background: var(--theme-card); box-shadow: 0 14px 32px var(--theme-shadow); transition: border-color .2s ease, transform .2s ease; }
.model-card--winner { border-color: var(--theme-accent-strong); transform: translateY(-2px); }
.model-card > header strong { display: block; margin: 7px 0 5px; overflow-wrap: anywhere; color: var(--theme-text); font-size: 16px; }
.model-card > header p { min-height: 34px; margin: 0 0 10px; color: var(--theme-muted); font-size: 11px; line-height: 1.45; }
.model-level { color: var(--theme-accent-strong); font-size: 10px; font-weight: 900; letter-spacing: .1em; text-transform: uppercase; }
.model-answer, .model-placeholder, .model-loading, .model-card-error { min-height: 180px; margin-top: 16px; padding: 15px; border-radius: 16px; background: var(--theme-card-hover); white-space: pre-wrap; overflow-wrap: anywhere; color: var(--theme-text); font-size: 12px; line-height: 1.58; }
.model-placeholder { color: var(--theme-muted); }
.model-loading { display: flex; align-items: center; justify-content: center; gap: 6px; color: var(--theme-muted); }
.model-loading span { width: 6px; height: 6px; border-radius: 50%; background: var(--theme-accent); animation: model-pulse 1s infinite alternate; }
.model-loading span:nth-child(2) { animation-delay: .2s; }.model-loading span:nth-child(3) { animation-delay: .4s; }
.model-metrics { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 14px 0 0; }
.model-metrics div { padding: 10px; border: 1px solid var(--theme-border); border-radius: 12px; }
.model-metrics dt { color: var(--theme-muted); font-size: 9px; }.model-metrics dd { margin: 4px 0 0; color: var(--theme-text); font-size: 11px; font-weight: 800; }
.model-rating { margin-top: 14px; }.model-rating > span { color: var(--theme-muted); font-size: 10px; }.model-rating > div { display: flex; gap: 6px; margin-top: 7px; }
.model-rating button { width: 30px; height: 30px; border: 1px solid var(--theme-border); border-radius: 9px; color: var(--theme-muted); background: var(--theme-card-hover); cursor: pointer; }
.model-rating button[aria-pressed="true"] { border-color: var(--theme-accent-strong); color: #fff; background: var(--theme-accent-strong); }
.model-winner { width: 100%; margin-top: 12px; padding: 9px; border: 1px solid var(--theme-border); border-radius: 12px; color: var(--theme-text); background: transparent; font: inherit; font-size: 10px; font-weight: 800; cursor: pointer; }
.model-winner[aria-pressed="true"] { border-color: var(--theme-accent-strong); color: var(--theme-accent-strong); background: color-mix(in srgb, var(--theme-accent) 10%, transparent); }
.model-card footer { display: flex; gap: 12px; margin-top: 14px; }.model-card footer a { color: var(--theme-accent-strong); font-size: 10px; font-weight: 800; text-decoration: none; }.model-card footer a:hover { text-decoration: underline; }
.model-analysis { margin-top: 18px; padding: 20px; border: 1px solid var(--theme-border); border-radius: 22px; background: var(--theme-card); }
.model-leaders { margin-top: 0; }.model-conclusion { display: block; margin-top: 18px; }
.model-judge { margin-top: 18px; padding: 18px; border: 1px solid var(--theme-border); border-radius: 18px; background: var(--theme-card-hover); }
.model-judge > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.model-judge > header span { color: var(--theme-accent-strong); font-size: 9px; font-weight: 900; letter-spacing: .08em; text-transform: uppercase; }
.model-judge h4 { margin: 5px 0 4px; color: var(--theme-text); font-size: 16px; }
.model-judge header p, .model-judge__note, .model-judge__metrics { margin: 0; color: var(--theme-muted); font-size: 10px; line-height: 1.5; }
.model-judge > header > button, .model-judge__error button { flex: none; border: 0; border-radius: 12px; padding: 10px 14px; color: #fff; background: linear-gradient(145deg, var(--theme-accent), var(--theme-accent-strong)); font: inherit; font-size: 10px; font-weight: 800; cursor: pointer; }
.model-judge button:disabled { cursor: wait; opacity: .55; }
.model-judge__note { margin-top: 14px; }
.model-judge__error { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 14px; padding: 12px; border-radius: 12px; color: #9b3f3f; background: color-mix(in srgb, #c85a5a 10%, var(--theme-card)); font-size: 10px; }
.model-judge__result { margin-top: 16px; }
.model-judge__winner { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; padding: 13px; border-radius: 14px; background: color-mix(in srgb, var(--theme-accent) 10%, var(--theme-card)); }
.model-judge__winner span { color: var(--theme-muted); font-size: 10px; }.model-judge__winner strong { color: var(--theme-text); font-size: 14px; }.model-judge__winner em { color: var(--theme-accent-strong); font-size: 9px; font-style: normal; font-weight: 800; }
.model-judge__summary { margin: 14px 0; color: var(--theme-text); font-size: 12px; line-height: 1.6; }
.model-judge__evaluations { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.model-judge__evaluations > article { padding: 13px; border: 1px solid var(--theme-border); border-radius: 14px; background: var(--theme-card); }
.model-judge__evaluations > article.is-winner { border-color: var(--theme-accent-strong); }
.model-judge__evaluations article > header { display: flex; justify-content: space-between; gap: 8px; color: var(--theme-text); font-size: 11px; }
.model-judge__scores { display: grid; gap: 4px; margin: 10px 0; }.model-judge__scores span { display: flex; justify-content: space-between; gap: 8px; color: var(--theme-muted); font-size: 9px; }.model-judge__scores b { color: var(--theme-text); }
.model-judge__evaluations article > p { margin: 6px 0 0; color: var(--theme-muted); font-size: 9px; line-height: 1.45; }.model-judge__evaluations article > p b { color: var(--theme-text); }
.model-judge__metrics { margin-top: 12px; }
.model-report-actions { display: flex; align-items: center; gap: 12px; margin-top: 14px; }.model-report-actions span { color: var(--theme-accent-strong); font-size: 10px; }
.model-method-note { margin: 16px 0 0; color: var(--theme-muted); font-size: 10px; line-height: 1.5; }
@keyframes model-pulse { to { opacity: .25; transform: translateY(-3px); } }
@media (max-width: 1050px) { .model-grid { grid-template-columns: 1fr; }.model-answer, .model-placeholder, .model-loading, .model-card-error { min-height: 110px; }.model-total, .model-leaders { grid-template-columns: 1fr 1fr; }.model-judge__evaluations { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .model-scroll { padding: 14px 12px 28px; }.model-hero { flex-direction: column; padding: 18px; }.model-hero__legend { justify-content: flex-start; }.model-total, .model-leaders { grid-template-columns: 1fr; }.model-form__footer, .model-judge > header { align-items: stretch; flex-direction: column; }.model-form__footer button, .model-judge > header > button { width: 100%; } }
</style>
