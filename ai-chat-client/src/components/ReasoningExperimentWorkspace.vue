<script setup>
import { computed, ref } from 'vue'
import { judgeReasoningExperiment, runReasoningExperiment } from '../api/chat.js'
import {
  combineMetrics,
  createExperimentSnapshot,
  downloadExperimentFile,
} from '../utils/experimentReport.js'

const props = defineProps({
  profile: {
    type: Object,
    required: true,
  },
  backendState: {
    type: String,
    default: 'checking',
  },
  backendStatusLabel: {
    type: String,
    default: 'Подключаемся',
  },
})

const strategyDefinitions = [
  {
    strategy: 'direct',
    title: 'Прямой ответ',
    description: 'Без дополнительных инструкций',
  },
  {
    strategy: 'step-by-step',
    title: 'Пошаговое решение',
    description: 'С инструкцией «решай пошагово»',
  },
  {
    strategy: 'meta-prompt',
    title: 'Промпт для решения',
    description: 'Сначала промпт, затем решение',
  },
  {
    strategy: 'expert-panel',
    title: 'Группа экспертов',
    description: 'Аналитик, инженер и критик',
  },
]

const evaluationCriteria = [
  { id: 'correctness', name: 'Правильность' },
  { id: 'clarity', name: 'Понятность' },
  { id: 'completeness', name: 'Полнота' },
  { id: 'efficiency', name: 'Эффективность' },
  { id: 'edgeCases', name: 'Граничные случаи' },
]

const judgeCriteria = [
  { id: 'correctness', shortName: 'Правильность' },
  { id: 'clarity', shortName: 'Понятность' },
  { id: 'completeness', shortName: 'Полнота' },
  { id: 'efficiency', shortName: 'Эффективность' },
  { id: 'edgeCases', shortName: 'Крайние случаи' },
]

const task = ref('')
const submittedTask = ref('')
const experimentId = ref(null)
const results = ref([])
const runningStrategies = ref(new Set())
const ratings = ref({})
const winner = ref(null)
const evaluationComment = ref('')
const judgeResult = ref(null)
const judgeLoading = ref(false)
const judgeError = ref('')
const exportNotice = ref('')

const isRunningAll = computed(() => (
  runningStrategies.value.size === strategyDefinitions.length
))

const cards = computed(() => strategyDefinitions.map((definition) => {
  if (runningStrategies.value.has(definition.strategy)) {
    return { ...definition, status: 'loading', experts: [] }
  }
  return results.value.find((result) => result.strategy === definition.strategy)
    || { ...definition, status: 'idle', experts: [] }
}))

const successfulCards = computed(() => (
  cards.value.filter((card) => card.status === 'success')
))

const winnerCard = computed(() => (
  successfulCards.value.find((card) => card.strategy === winner.value)
))

const canRunJudge = computed(() => (
  successfulCards.value.length === strategyDefinitions.length
  && runningStrategies.value.size === 0
  && !judgeLoading.value
))

const solutionMetrics = computed(() => (
  combineMetrics(successfulCards.value.map((card) => card.metrics))
))

const overallMetrics = computed(() => (
  combineMetrics([solutionMetrics.value, judgeResult.value?.metrics])
))

function setRunning(strategyIds, running) {
  const next = new Set(runningStrategies.value)
  for (const strategyId of strategyIds) {
    if (running) next.add(strategyId)
    else next.delete(strategyId)
  }
  runningStrategies.value = next
}

function replaceResult(result) {
  results.value = [
    ...results.value.filter((item) => item.strategy !== result.strategy),
    result,
  ]
}

function failureResult(strategyId, error) {
  const definition = strategyDefinitions.find((item) => item.strategy === strategyId)
  return {
    ...definition,
    status: 'error',
    error: error instanceof Error ? error.message : 'Не удалось получить ответ',
    experts: [],
  }
}

function confidenceLabel(value) {
  return ({
    high: 'Высокая уверенность',
    medium: 'Средняя уверенность',
    low: 'Низкая уверенность',
  })[value] || value
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
  if (value === null || value === undefined) return 'Стоимость недоступна'
  const cost = Number(value) || 0
  if (cost > 0 && cost < 0.000001) return '< $0.000001'
  return `$${cost.toFixed(6)}`
}

function exportReport(format) {
  const snapshot = createExperimentSnapshot({
    experimentId: experimentId.value,
    profile: props.profile,
    task: submittedTask.value,
    results: successfulCards.value,
    ratings: ratings.value,
    winner: winner.value,
    evaluationComment: evaluationComment.value,
    judgeResult: judgeResult.value,
  })
  downloadExperimentFile(snapshot, format)
  exportNotice.value = format === 'json'
    ? 'JSON-снимок скачан'
    : 'Markdown-отчёт скачан'
}

function setRating(strategyId, criterionId, value) {
  ratings.value[strategyId] = {
    ...(ratings.value[strategyId] || {}),
    [criterionId]: value,
  }
}

function ratingValue(strategyId, criterionId) {
  return ratings.value[strategyId]?.[criterionId] || 0
}

function averageRating(strategyId) {
  const values = evaluationCriteria
    .map((criterion) => ratingValue(strategyId, criterion.id))
    .filter((value) => value > 0)
  if (!values.length) return null
  return (values.reduce((sum, value) => sum + value, 0) / values.length).toFixed(1)
}

function resetStrategyEvaluation(strategyId) {
  const nextRatings = { ...ratings.value }
  delete nextRatings[strategyId]
  ratings.value = nextRatings
  if (winner.value === strategyId) winner.value = null
}

async function runJudge() {
  if (!canRunJudge.value) return

  judgeLoading.value = true
  judgeError.value = ''
  judgeResult.value = null
  try {
    judgeResult.value = await judgeReasoningExperiment({
      task: submittedTask.value,
      profileId: props.profile.id,
      candidates: successfulCards.value.map((card) => ({
        strategy: card.strategy,
        answer: card.answer,
      })),
    })
  } catch (error) {
    judgeError.value = error instanceof Error
      ? error.message
      : 'Не удалось получить оценку DeepSeek'
  } finally {
    judgeLoading.value = false
  }
}

async function execute(strategyIds) {
  setRunning(strategyIds, true)
  try {
    const response = await runReasoningExperiment({
      task: submittedTask.value,
      profileId: props.profile.id,
      strategies: strategyIds.length === strategyDefinitions.length ? [] : strategyIds,
    })
    experimentId.value = response.experimentId
    for (const result of response.results || []) {
      replaceResult(result)
    }
  } catch (error) {
    for (const strategyId of strategyIds) {
      replaceResult(failureResult(strategyId, error))
    }
  } finally {
    setRunning(strategyIds, false)
  }
}

async function runAll() {
  const value = task.value.trim()
  if (!value || runningStrategies.value.size > 0) return

  submittedTask.value = value
  results.value = []
  experimentId.value = null
  ratings.value = {}
  winner.value = null
  evaluationComment.value = ''
  judgeResult.value = null
  judgeError.value = ''
  exportNotice.value = ''
  await execute(strategyDefinitions.map((item) => item.strategy))
}

async function retry(strategyId) {
  if (!submittedTask.value || runningStrategies.value.has(strategyId)) return
  resetStrategyEvaluation(strategyId)
  judgeResult.value = null
  judgeError.value = ''
  exportNotice.value = ''
  await execute([strategyId])
}
</script>

<template>
  <section class="experiment-workspace" aria-label="Эксперимент со способами рассуждения">
    <header class="conversation-header experiment-header">
      <div class="conversation-identity">
        <div class="profile-glyph profile-glyph--experiment" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <path d="M6 5h12M6 19h12M8 5v4l4 3 4-3V5M8 19v-4l4-3 4 3v4" />
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

    <div class="experiment-scroll">
      <section class="experiment-intro">
        <div>
          <span class="experiment-eyebrow">Одна задача · четыре подхода</span>
          <h3>Посмотрим, как промпт меняет решение</h3>
          <p>
            Введите логическую, алгоритмическую или аналитическую задачу.
            Каждый подход получит одно и то же исходное условие без истории чата.
          </p>
        </div>

        <form class="experiment-form" @submit.prevent="runAll">
          <label for="reasoning-task">Условие задачи</label>
          <textarea
            id="reasoning-task"
            v-model="task"
            maxlength="10000"
            rows="4"
            :disabled="runningStrategies.size > 0"
            placeholder="Например: найдите самый быстрый алгоритм и объясните его сложность…"
          ></textarea>
          <div class="experiment-form__footer">
            <span>{{ task.length }} / 10 000</span>
            <button type="submit" :disabled="!task.trim() || runningStrategies.size > 0">
              <span v-if="isRunningAll" class="button-spinner" aria-hidden="true"></span>
              {{ isRunningAll ? 'Сравниваем…' : 'Получить 4 решения' }}
            </button>
          </div>
        </form>
      </section>

      <section v-if="cards.some((card) => card.status !== 'idle')" class="experiment-results">
        <div class="results-heading">
          <div>
            <span class="experiment-eyebrow">Результаты эксперимента</span>
            <h3>Четыре взгляда на одну задачу</h3>
          </div>
          <div v-if="experimentId" class="results-heading__meta">
            <span class="experiment-id">Запуск {{ experimentId.slice(0, 8) }}</span>
            <span>Данные не сохраняются</span>
          </div>
        </div>

        <section v-if="solutionMetrics.apiCalls" class="experiment-metrics" aria-label="Общие метрики решений">
          <div>
            <span>API-вызовы</span>
            <strong>{{ solutionMetrics.apiCalls }}</strong>
          </div>
          <div>
            <span>Токены</span>
            <strong>{{ formatTokens(solutionMetrics.totalTokens) }}</strong>
          </div>
          <div>
            <span>Сумма времени стратегий</span>
            <strong>{{ formatDuration(solutionMetrics.elapsedMs) }}</strong>
          </div>
          <div>
            <span>Расчётная стоимость</span>
            <strong>{{ formatCost(solutionMetrics.estimatedCostUsd) }}</strong>
          </div>
        </section>

        <div class="strategy-grid">
          <article
            v-for="(card, index) in cards"
            :key="card.strategy"
            class="strategy-card"
            :class="`strategy-card--${card.status}`"
          >
            <header class="strategy-card__header">
              <span class="strategy-number">0{{ index + 1 }}</span>
              <div>
                <h4>{{ card.title }}</h4>
                <p>{{ card.description }}</p>
              </div>
              <span v-if="card.status === 'success'" class="result-state result-state--success">
                Готово
              </span>
              <span v-else-if="card.status === 'error'" class="result-state result-state--error">
                Ошибка
              </span>
            </header>

            <div v-if="card.status === 'loading'" class="strategy-loading" aria-live="polite">
              <span></span><span></span><span></span>
              <p>DeepSeek рассматривает задачу…</p>
            </div>

            <div v-else-if="card.status === 'error'" class="strategy-error">
              <p>{{ card.error }}</p>
              <button type="button" @click="retry(card.strategy)">Повторить этот способ</button>
            </div>

              <div v-else-if="card.status === 'success'" class="strategy-content">
              <details v-if="card.generatedPrompt" class="generated-prompt">
                <summary>Показать созданный промпт</summary>
                <p>{{ card.generatedPrompt }}</p>
              </details>

              <div v-if="card.experts?.length" class="expert-list">
                <section v-for="expert in card.experts" :key="expert.role" class="expert-view">
                  <span>{{ expert.role }}</span>
                  <p>{{ expert.solution }}</p>
                </section>
                <section class="expert-consensus">
                  <div class="expert-consensus__heading">
                    <span>Общий итог</span>
                    <em v-if="card.confidence">{{ confidenceLabel(card.confidence) }}</em>
                  </div>
                  <p>{{ card.consensus }}</p>
                </section>
                <section v-if="card.comparison" class="expert-comparison">
                  <span>Сравнение экспертов</span>
                  <p>{{ card.comparison }}</p>
                </section>
              </div>

              <p v-else class="strategy-answer">{{ card.answer }}</p>

              <details v-if="card.metrics" class="strategy-metrics">
                <summary>
                  <span>{{ card.metrics.apiCalls }} выз. · {{ formatTokens(card.metrics.totalTokens) }} токенов</span>
                  <strong>{{ formatDuration(card.metrics.elapsedMs) }}</strong>
                </summary>
                <div class="strategy-metrics__grid">
                  <span>Вход <strong>{{ formatTokens(card.metrics.promptTokens) }}</strong></span>
                  <span>Выход <strong>{{ formatTokens(card.metrics.completionTokens) }}</strong></span>
                  <span>Cache hit <strong>{{ formatTokens(card.metrics.promptCacheHitTokens) }}</strong></span>
                  <span>Cache miss <strong>{{ formatTokens(card.metrics.promptCacheMissTokens) }}</strong></span>
                  <span>Reasoning <strong>{{ formatTokens(card.metrics.reasoningTokens) }}</strong></span>
                  <span>API-время <strong>{{ formatDuration(card.metrics.apiDurationMs) }}</strong></span>
                </div>
                <p>{{ formatCost(card.metrics.estimatedCostUsd) }}</p>
              </details>

              <details class="strategy-evaluation">
                <summary>
                  <span>Оценить решение</span>
                  <strong v-if="averageRating(card.strategy)">
                    {{ averageRating(card.strategy) }} / 5
                  </strong>
                </summary>
                <div class="criteria-list">
                  <div
                    v-for="criterion in evaluationCriteria"
                    :key="criterion.id"
                    class="criterion-row"
                  >
                    <span>{{ criterion.name }}</span>
                    <div
                      class="rating-scale"
                      role="group"
                      :aria-label="criterion.name + ' для ' + card.title"
                    >
                      <button
                        v-for="score in 5"
                        :key="score"
                        type="button"
                        :class="{ 'is-active': score <= ratingValue(card.strategy, criterion.id) }"
                        :aria-label="criterion.name + ': ' + score + ' из 5 для ' + card.title"
                        :aria-pressed="score === ratingValue(card.strategy, criterion.id)"
                        @click="setRating(card.strategy, criterion.id, score)"
                      >
                        {{ score }}
                      </button>
                    </div>
                  </div>
                </div>
              </details>

              <footer class="strategy-card__footer">
                <span>{{ card.model || 'DeepSeek' }}</span>
                <button type="button" @click="retry(card.strategy)">Повторить</button>
              </footer>
            </div>
          </article>
        </div>

        <section v-if="successfulCards.length" class="manual-verdict">
          <header>
            <div>
              <span class="experiment-eyebrow">Ваше сравнение</span>
              <h3>Какой способ дал лучший результат?</h3>
            </div>
            <span class="manual-verdict__state">
              {{ winnerCard ? 'Победитель выбран' : 'Выберите один ответ' }}
            </span>
          </header>

          <div class="winner-options" role="radiogroup" aria-label="Лучший способ решения">
            <button
              v-for="card in successfulCards"
              :key="card.strategy"
              type="button"
              class="winner-option"
              :class="{ 'is-selected': winner === card.strategy }"
              :data-strategy="card.strategy"
              role="radio"
              :aria-checked="winner === card.strategy"
              @click="winner = card.strategy"
            >
              <span>{{ card.title }}</span>
              <strong>
                {{ averageRating(card.strategy) ? averageRating(card.strategy) + ' / 5' : 'Не оценено' }}
              </strong>
            </button>
          </div>

          <label class="evaluation-comment">
            <span>Почему этот способ оказался лучше?</span>
            <textarea
              v-model="evaluationComment"
              rows="3"
              maxlength="1000"
              placeholder="Например: правильный результат, понятное объяснение и оптимальная сложность…"
            ></textarea>
          </label>

          <p v-if="winnerCard" class="verdict-summary" aria-live="polite">
            Лучшим выбран способ «{{ winnerCard.title }}».
            Оценка относится только к текущему запуску эксперимента.
          </p>

          <section class="ai-judge">
            <header>
              <div>
                <span class="experiment-eyebrow">Независимая проверка</span>
                <h4>Автоматический судья DeepSeek</h4>
                <p>Сравнит обезличенные ответы по тем же пяти критериям.</p>
              </div>
              <button
                type="button"
                class="judge-button"
                :disabled="!canRunJudge"
                @click="runJudge"
              >
                <span v-if="judgeLoading" class="button-spinner" aria-hidden="true"></span>
                {{ judgeLoading ? 'Оцениваем…' : judgeResult ? 'Оценить заново' : 'Запустить AI-судью' }}
              </button>
            </header>

            <div v-if="judgeLoading" class="judge-loading" aria-live="polite">
              DeepSeek перепроверяет решения и сравнивает аргументы…
            </div>

            <div v-else-if="judgeError" class="judge-error" role="alert">
              <span>{{ judgeError }}</span>
              <button type="button" @click="runJudge">Повторить</button>
            </div>

            <div v-else-if="judgeResult" class="judge-result" aria-live="polite">
              <div class="judge-winner">
                <div>
                  <span>Рекомендация модели</span>
                  <strong>{{ judgeResult.winnerTitle }}</strong>
                </div>
                <em v-if="winner">
                  {{ winner === judgeResult.winnerStrategy
                    ? 'Совпало с вашим выбором'
                    : 'Отличается от вашего выбора' }}
                </em>
              </div>

              <p class="judge-explanation">{{ judgeResult.explanation }}</p>

              <div v-if="judgeResult.metrics" class="judge-metrics">
                <span>Судья: {{ judgeResult.model }}</span>
                <span>{{ judgeResult.metrics.apiCalls }} API-выз.</span>
                <span>{{ formatTokens(judgeResult.metrics.totalTokens) }} токенов</span>
                <span>{{ formatDuration(judgeResult.metrics.elapsedMs) }}</span>
                <span>{{ formatCost(judgeResult.metrics.estimatedCostUsd) }}</span>
              </div>

              <div class="judge-evaluations">
                <article
                  v-for="evaluation in judgeResult.evaluations"
                  :key="evaluation.strategy"
                  :class="{ 'is-winner': evaluation.strategy === judgeResult.winnerStrategy }"
                >
                  <header>
                    <span>{{ evaluation.title }}</span>
                    <strong>{{ evaluation.average }} / 10</strong>
                  </header>
                  <div class="judge-scores">
                    <span v-for="criterion in judgeCriteria" :key="criterion.id">
                      {{ criterion.shortName }}
                      <strong>{{ evaluation.scores[criterion.id] }}</strong>
                    </span>
                  </div>
                  <p><b>Сильные стороны:</b> {{ evaluation.strengths }}</p>
                  <p><b>Слабые стороны:</b> {{ evaluation.weaknesses }}</p>
                </article>
              </div>
            </div>

            <p v-else class="judge-note">
              Это отдельный API-вызов. Оценка модели является рекомендацией,
              а ваш выбор остаётся самостоятельным.
            </p>
          </section>
        </section>

        <section v-if="successfulCards.length" class="experiment-export">
          <div>
            <span class="experiment-eyebrow">Снимок результата</span>
            <h3>Скачать текущий эксперимент</h3>
            <p>
              В файл попадут задача, решения, метрики, ваши оценки и результат AI-судьи.
              На сервере история экспериментов не создаётся.
            </p>
          </div>
          <div class="experiment-export__actions">
            <button type="button" @click="exportReport('md')">Markdown</button>
            <button type="button" @click="exportReport('json')">JSON</button>
            <span v-if="exportNotice" aria-live="polite">{{ exportNotice }}</span>
          </div>
          <dl class="experiment-export__totals">
            <div><dt>Всего обращений</dt><dd>{{ overallMetrics.apiCalls }}</dd></div>
            <div><dt>Всего токенов</dt><dd>{{ formatTokens(overallMetrics.totalTokens) }}</dd></div>
            <div><dt>Общая стоимость</dt><dd>{{ formatCost(overallMetrics.estimatedCostUsd) }}</dd></div>
          </dl>
        </section>
      </section>
    </div>
  </section>
</template>

<style scoped>
.experiment-workspace {
  min-width: 0;
  min-height: 0;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  color: var(--theme-body);
  background:
    radial-gradient(circle at 88% 4%, color-mix(in srgb, var(--theme-accent-soft) 74%, transparent), transparent 24rem),
    linear-gradient(180deg, color-mix(in srgb, var(--theme-workspace) 96%, transparent), var(--theme-workspace));
}

.profile-glyph--experiment {
  background: linear-gradient(145deg, var(--theme-accent-soft), var(--theme-card));
}

.experiment-scroll {
  min-height: 0;
  overflow-y: auto;
  padding: clamp(22px, 3.4vw, 42px);
  scrollbar-color: color-mix(in srgb, var(--theme-accent) 24%, transparent) transparent;
  scrollbar-width: thin;
}

.experiment-intro {
  display: grid;
  grid-template-columns: minmax(220px, 0.82fr) minmax(360px, 1.18fr);
  gap: clamp(24px, 4vw, 52px);
  align-items: center;
  max-width: 940px;
  margin: 0 auto;
  padding: clamp(4px, 1vw, 12px) 0 32px;
}

.experiment-eyebrow {
  display: block;
  margin-bottom: 9px;
  color: var(--theme-accent);
  font-size: 9px;
  font-weight: 760;
  letter-spacing: 0.13em;
  text-transform: uppercase;
}

.experiment-intro h3,
.results-heading h3 {
  margin: 0;
  color: var(--theme-text);
  letter-spacing: -0.035em;
}

.experiment-intro h3 {
  max-width: 360px;
  font-family: Georgia, "Times New Roman", serif;
  font-size: clamp(28px, 3.3vw, 42px);
  font-weight: 500;
  line-height: 1.08;
}

.experiment-intro > div > p {
  max-width: 390px;
  margin: 16px 0 0;
  color: var(--theme-muted);
  font-size: 13px;
  line-height: 1.65;
}

.experiment-form {
  display: grid;
  gap: 11px;
  padding: 19px;
  background: color-mix(in srgb, var(--theme-card) 90%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 22px;
  box-shadow:
    0 20px 50px color-mix(in srgb, var(--theme-shadow) 50%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.experiment-form label {
  color: var(--theme-muted);
  font-size: 10px;
  font-weight: 720;
  letter-spacing: 0.09em;
  text-transform: uppercase;
}

.experiment-form textarea {
  width: 100%;
  min-height: 126px;
  resize: vertical;
  padding: 15px 16px;
  color: var(--theme-text);
  background: color-mix(in srgb, var(--theme-workspace) 92%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 15px;
  outline: none;
  font-size: 13px;
  line-height: 1.55;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.experiment-form textarea:focus {
  border-color: color-mix(in srgb, var(--theme-accent) 55%, transparent);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--theme-accent) 10%, transparent);
}

.experiment-form textarea::placeholder {
  color: var(--theme-faint);
}

.experiment-form__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.experiment-form__footer > span {
  color: var(--theme-faint);
  font-size: 9px;
}

.experiment-form button,
.strategy-error button,
.strategy-card__footer button {
  border: 0;
  cursor: pointer;
  transition: transform 150ms ease, opacity 150ms ease, box-shadow 150ms ease;
}

.experiment-form button {
  min-width: 164px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 17px;
  color: #fff;
  background: linear-gradient(145deg, var(--theme-accent), var(--theme-accent-strong));
  border-radius: 13px;
  box-shadow: 0 9px 22px color-mix(in srgb, var(--theme-accent) 25%, transparent);
  font-size: 12px;
  font-weight: 680;
}

.experiment-form button:hover:not(:disabled) {
  transform: translateY(-2px);
}

.experiment-form button:disabled {
  cursor: not-allowed;
  opacity: 0.42;
  box-shadow: none;
}

.button-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 700ms infinite linear;
}

.experiment-results {
  max-width: 940px;
  margin: 0 auto;
  padding-top: 27px;
  border-top: 1px solid var(--theme-border);
}

.results-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}

.results-heading h3 {
  font-size: 21px;
  font-weight: 700;
}

.experiment-id {
  padding: 6px 9px;
  color: var(--theme-faint);
  background: color-mix(in srgb, var(--theme-card) 86%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 999px;
  font: 600 8px/1 ui-monospace, SFMono-Regular, Consolas, monospace;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.results-heading__meta {
  display: grid;
  justify-items: end;
  gap: 6px;
}

.results-heading__meta > span:last-child {
  color: var(--theme-faint);
  font-size: 8px;
}

.experiment-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-bottom: 14px;
}

.experiment-metrics > div {
  min-width: 0;
  display: grid;
  gap: 4px;
  padding: 11px 12px;
  background: color-mix(in srgb, var(--theme-card) 84%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 12px;
}

.experiment-metrics span {
  overflow: hidden;
  color: var(--theme-faint);
  font-size: 8px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.experiment-metrics strong {
  overflow: hidden;
  color: var(--theme-text);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.strategy-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.strategy-card {
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 260px;
  overflow: hidden;
  background: color-mix(in srgb, var(--theme-card) 91%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 20px;
  box-shadow: 0 12px 36px color-mix(in srgb, var(--theme-shadow) 42%, transparent);
  animation: card-in 260ms ease-out both;
}

.strategy-card:nth-child(2) { animation-delay: 45ms; }
.strategy-card:nth-child(3) { animation-delay: 90ms; }
.strategy-card:nth-child(4) { animation-delay: 135ms; }

.strategy-card__header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 11px;
  align-items: start;
  padding: 17px 18px;
  border-bottom: 1px solid var(--theme-border);
}

.strategy-number {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  color: var(--theme-accent-strong);
  background: var(--theme-accent-soft);
  border-radius: 10px;
  font: 750 9px/1 ui-monospace, SFMono-Regular, Consolas, monospace;
}

.strategy-card__header h4 {
  margin: 1px 0 3px;
  color: var(--theme-text);
  font-size: 13px;
  font-weight: 720;
}

.strategy-card__header p {
  margin: 0;
  color: var(--theme-faint);
  font-size: 9px;
  line-height: 1.4;
}

.result-state {
  padding: 5px 7px;
  border-radius: 999px;
  font-size: 8px;
  font-weight: 750;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.result-state--success {
  color: var(--theme-accent-strong);
  background: var(--theme-accent-soft);
}

.result-state--error {
  color: #946a63;
  background: #f4e9e5;
}

.strategy-loading,
.strategy-error {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.strategy-loading {
  gap: 5px;
  flex-wrap: wrap;
  padding: 28px;
  color: var(--theme-faint);
}

.strategy-loading span {
  width: 6px;
  height: 6px;
  background: var(--theme-accent);
  border-radius: 50%;
  animation: thinking 1.1s infinite ease-in-out;
}

.strategy-loading span:nth-child(2) { animation-delay: 140ms; }
.strategy-loading span:nth-child(3) { animation-delay: 280ms; }

.strategy-loading p {
  flex-basis: 100%;
  margin: 7px 0 0;
  text-align: center;
  font-size: 10px;
}

.strategy-error {
  flex-direction: column;
  gap: 13px;
  padding: 24px;
  text-align: center;
}

.strategy-error p {
  margin: 0;
  color: #946a63;
  font-size: 11px;
  line-height: 1.55;
}

.strategy-error button,
.strategy-card__footer button {
  padding: 7px 10px;
  color: var(--theme-accent-strong);
  background: var(--theme-accent-soft);
  border-radius: 9px;
  font-size: 9px;
  font-weight: 680;
}

.strategy-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 17px 18px 14px;
}

.strategy-answer,
.generated-prompt p,
.expert-view p,
.expert-consensus p,
.expert-comparison p {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.strategy-answer {
  flex: 1;
  margin: 0;
  color: var(--theme-body);
  font-size: 11px;
  line-height: 1.65;
}

.generated-prompt {
  margin: 0 0 14px;
  padding: 10px 12px;
  background: color-mix(in srgb, var(--theme-accent-soft) 62%, transparent);
  border-radius: 11px;
}

.generated-prompt summary {
  color: var(--theme-accent-strong);
  cursor: pointer;
  font-size: 9px;
  font-weight: 700;
}

.generated-prompt p {
  margin: 10px 0 0;
  color: var(--theme-muted);
  font-size: 9px;
  line-height: 1.55;
}

.expert-list {
  display: grid;
  gap: 9px;
}

.expert-view,
.expert-consensus,
.expert-comparison {
  padding: 10px 11px;
  background: color-mix(in srgb, var(--theme-workspace) 75%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 11px;
}

.expert-view > span,
.expert-consensus__heading > span,
.expert-comparison > span {
  display: block;
  margin-bottom: 5px;
  color: var(--theme-accent-strong);
  font-size: 8px;
  font-weight: 760;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.expert-consensus__heading > span {
  margin-bottom: 0;
}

.expert-view p,
.expert-consensus p,
.expert-comparison p {
  margin: 0;
  color: var(--theme-body);
  font-size: 10px;
  line-height: 1.55;
}

.expert-consensus {
  background: color-mix(in srgb, var(--theme-accent-soft) 66%, transparent);
}

.expert-consensus__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.expert-consensus__heading em {
  padding: 4px 6px;
  color: var(--theme-accent-strong);
  background: color-mix(in srgb, var(--theme-workspace) 65%, transparent);
  border-radius: 999px;
  font-size: 7px;
  font-style: normal;
  font-weight: 700;
}

.expert-comparison {
  background: transparent;
  border-style: dashed;
}

.strategy-evaluation {
  margin-top: 16px;
  padding-top: 11px;
  border-top: 1px solid var(--theme-border);
}

.strategy-metrics {
  margin-top: 15px;
  padding: 10px 11px;
  background: color-mix(in srgb, var(--theme-workspace) 70%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 11px;
}

.strategy-metrics summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--theme-muted);
  cursor: pointer;
  list-style: none;
  font-size: 8px;
  font-weight: 680;
}

.strategy-metrics summary::-webkit-details-marker {
  display: none;
}

.strategy-metrics summary::after {
  content: "⌄";
  color: var(--theme-accent);
  font-size: 11px;
}

.strategy-metrics[open] summary::after {
  transform: rotate(180deg);
}

.strategy-metrics summary > strong {
  margin-left: auto;
  color: var(--theme-accent-strong);
  font-size: 8px;
}

.strategy-metrics__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
  margin-top: 10px;
}

.strategy-metrics__grid span,
.strategy-metrics > p {
  color: var(--theme-faint);
  font-size: 8px;
}

.strategy-metrics__grid strong {
  color: var(--theme-body);
}

.strategy-metrics > p {
  margin: 9px 0 0;
  padding-top: 8px;
  border-top: 1px solid var(--theme-border);
}

.strategy-evaluation summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--theme-accent-strong);
  cursor: pointer;
  list-style: none;
  font-size: 9px;
  font-weight: 700;
}

.strategy-evaluation summary::-webkit-details-marker {
  display: none;
}

.strategy-evaluation summary::before {
  content: "+";
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  margin-right: -5px;
  color: var(--theme-accent);
  background: var(--theme-accent-soft);
  border-radius: 6px;
  font-size: 13px;
  line-height: 1;
}

.strategy-evaluation[open] summary::before {
  content: "−";
}

.strategy-evaluation summary > span {
  margin-right: auto;
}

.strategy-evaluation summary > strong {
  padding: 4px 7px;
  color: var(--theme-accent-strong);
  background: var(--theme-accent-soft);
  border-radius: 999px;
  font-size: 8px;
}

.criteria-list {
  display: grid;
  gap: 9px;
  margin-top: 13px;
  padding: 12px;
  background: color-mix(in srgb, var(--theme-workspace) 72%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 12px;
}

.criterion-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.criterion-row > span {
  color: var(--theme-muted);
  font-size: 9px;
}

.rating-scale {
  display: inline-flex;
  gap: 3px;
}

.rating-scale button {
  width: 23px;
  height: 23px;
  display: grid;
  place-items: center;
  padding: 0;
  color: var(--theme-faint);
  background: color-mix(in srgb, var(--theme-card) 78%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 7px;
  cursor: pointer;
  font-size: 8px;
  font-weight: 700;
  transition: color 130ms ease, background 130ms ease, border-color 130ms ease, transform 130ms ease;
}

.rating-scale button:hover {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--theme-accent) 42%, transparent);
}

.rating-scale button.is-active {
  color: #fff;
  background: var(--theme-accent);
  border-color: var(--theme-accent);
}

.strategy-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-top: 16px;
  padding-top: 11px;
  border-top: 1px solid var(--theme-border);
}

.manual-verdict {
  margin-top: 18px;
  padding: clamp(18px, 2.8vw, 26px);
  background:
    radial-gradient(circle at 94% 0, color-mix(in srgb, var(--theme-accent-soft) 86%, transparent), transparent 18rem),
    color-mix(in srgb, var(--theme-card) 92%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 22px;
  box-shadow: 0 15px 42px color-mix(in srgb, var(--theme-shadow) 42%, transparent);
}

.manual-verdict > header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 16px;
}

.manual-verdict h3 {
  margin: 0;
  color: var(--theme-text);
  font-size: 18px;
  letter-spacing: -0.025em;
}

.manual-verdict__state {
  padding: 6px 9px;
  color: var(--theme-muted);
  background: color-mix(in srgb, var(--theme-workspace) 75%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 999px;
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}

.winner-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
}

.winner-option {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 11px 12px;
  color: var(--theme-body);
  background: color-mix(in srgb, var(--theme-workspace) 78%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 12px;
  cursor: pointer;
  text-align: left;
  transition: transform 140ms ease, border-color 140ms ease, background 140ms ease;
}

.winner-option:hover {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--theme-accent) 40%, transparent);
}

.winner-option.is-selected {
  color: var(--theme-accent-strong);
  background: var(--theme-accent-soft);
  border-color: color-mix(in srgb, var(--theme-accent) 45%, transparent);
  box-shadow: inset 3px 0 0 var(--theme-accent);
}

.winner-option > span {
  overflow: hidden;
  font-size: 10px;
  font-weight: 680;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.winner-option > strong {
  flex: 0 0 auto;
  color: var(--theme-faint);
  font-size: 8px;
  font-weight: 650;
}

.winner-option.is-selected > strong {
  color: var(--theme-accent-strong);
}

.evaluation-comment {
  display: grid;
  gap: 7px;
  margin-top: 15px;
}

.evaluation-comment > span {
  color: var(--theme-muted);
  font-size: 9px;
  font-weight: 700;
}

.evaluation-comment textarea {
  width: 100%;
  min-height: 76px;
  resize: vertical;
  padding: 11px 12px;
  color: var(--theme-text);
  background: color-mix(in srgb, var(--theme-workspace) 82%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 12px;
  outline: none;
  font-size: 10px;
  line-height: 1.55;
}

.evaluation-comment textarea:focus {
  border-color: color-mix(in srgb, var(--theme-accent) 50%, transparent);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--theme-accent) 9%, transparent);
}

.evaluation-comment textarea::placeholder {
  color: var(--theme-faint);
}

.verdict-summary {
  margin: 12px 0 0;
  padding: 9px 11px;
  color: var(--theme-accent-strong);
  background: color-mix(in srgb, var(--theme-accent-soft) 66%, transparent);
  border-radius: 10px;
  font-size: 9px;
  line-height: 1.5;
}

.ai-judge {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--theme-border);
}

.ai-judge > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.ai-judge h4 {
  margin: 0;
  color: var(--theme-text);
  font-size: 14px;
}

.ai-judge header p {
  margin: 4px 0 0;
  color: var(--theme-faint);
  font-size: 9px;
  line-height: 1.45;
}

.judge-button {
  min-width: 154px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 10px 13px;
  color: #fff;
  background: linear-gradient(145deg, var(--theme-accent), var(--theme-accent-strong));
  border: 0;
  border-radius: 11px;
  box-shadow: 0 7px 18px color-mix(in srgb, var(--theme-accent) 22%, transparent);
  cursor: pointer;
  font-size: 9px;
  font-weight: 700;
}

.judge-button:disabled {
  cursor: not-allowed;
  opacity: 0.45;
  box-shadow: none;
}

.judge-note,
.judge-loading {
  margin: 12px 0 0;
  padding: 10px 12px;
  color: var(--theme-muted);
  background: color-mix(in srgb, var(--theme-workspace) 62%, transparent);
  border: 1px dashed var(--theme-border);
  border-radius: 10px;
  font-size: 9px;
  line-height: 1.5;
}

.judge-loading {
  color: var(--theme-accent-strong);
  animation: breathe 1.4s infinite ease-in-out;
}

.judge-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding: 10px 12px;
  color: #946a63;
  background: #f8f2ef;
  border: 1px solid rgba(148, 106, 99, 0.13);
  border-radius: 10px;
  font-size: 9px;
}

.judge-error button {
  padding: 6px 9px;
  color: #8b5e57;
  background: #f0dfda;
  border: 0;
  border-radius: 8px;
  cursor: pointer;
  font-size: 8px;
  font-weight: 700;
}

.judge-result {
  display: grid;
  gap: 12px;
  margin-top: 14px;
}

.judge-winner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 13px 14px;
  color: var(--theme-accent-strong);
  background: var(--theme-accent-soft);
  border: 1px solid color-mix(in srgb, var(--theme-accent) 20%, transparent);
  border-radius: 13px;
}

.judge-winner > div {
  display: grid;
  gap: 3px;
}

.judge-winner span {
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.07em;
  text-transform: uppercase;
}

.judge-winner strong {
  color: var(--theme-text);
  font-size: 13px;
}

.judge-winner em {
  padding: 5px 7px;
  background: color-mix(in srgb, var(--theme-workspace) 62%, transparent);
  border-radius: 999px;
  font-size: 8px;
  font-style: normal;
  font-weight: 700;
}

.judge-explanation {
  margin: 0;
  color: var(--theme-body);
  font-size: 10px;
  line-height: 1.6;
}

.judge-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.judge-metrics span {
  padding: 5px 7px;
  color: var(--theme-accent-strong);
  background: var(--theme-accent-soft);
  border-radius: 8px;
  font-size: 8px;
  font-weight: 650;
}

.judge-evaluations {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 9px;
}

.judge-evaluations > article {
  min-width: 0;
  padding: 12px;
  background: color-mix(in srgb, var(--theme-workspace) 72%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 12px;
}

.judge-evaluations > article.is-winner {
  border-color: color-mix(in srgb, var(--theme-accent) 42%, transparent);
  box-shadow: inset 3px 0 0 var(--theme-accent);
}

.judge-evaluations article > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  color: var(--theme-text);
  font-size: 10px;
  font-weight: 700;
}

.judge-evaluations article > header strong {
  color: var(--theme-accent-strong);
  font-size: 9px;
}

.judge-scores {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin: 9px 0;
}

.judge-scores span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px;
  color: var(--theme-muted);
  background: color-mix(in srgb, var(--theme-card) 82%, transparent);
  border-radius: 7px;
  font-size: 7px;
}

.judge-scores strong {
  color: var(--theme-accent-strong);
}

.judge-evaluations article > p {
  margin: 5px 0 0;
  color: var(--theme-muted);
  font-size: 8px;
  line-height: 1.5;
}

.judge-evaluations article > p b {
  color: var(--theme-body);
}

.strategy-card__footer > span {
  color: var(--theme-faint);
  font-size: 8px;
}

.strategy-card__footer button:hover,
.strategy-error button:hover {
  transform: translateY(-1px);
}

.experiment-export {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px 24px;
  align-items: center;
  margin-top: 18px;
  padding: clamp(18px, 2.8vw, 26px);
  background: color-mix(in srgb, var(--theme-card) 91%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 22px;
  box-shadow: 0 15px 42px color-mix(in srgb, var(--theme-shadow) 36%, transparent);
}

.experiment-export h3 {
  margin: 0;
  color: var(--theme-text);
  font-size: 17px;
}

.experiment-export p {
  max-width: 590px;
  margin: 7px 0 0;
  color: var(--theme-muted);
  font-size: 9px;
  line-height: 1.55;
}

.experiment-export__actions {
  display: grid;
  grid-template-columns: repeat(2, auto);
  gap: 7px;
  justify-items: end;
}

.experiment-export__actions button {
  min-width: 86px;
  padding: 9px 12px;
  color: var(--theme-accent-strong);
  background: var(--theme-accent-soft);
  border: 1px solid color-mix(in srgb, var(--theme-accent) 22%, transparent);
  border-radius: 10px;
  cursor: pointer;
  font-size: 9px;
  font-weight: 700;
}

.experiment-export__actions button:hover {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--theme-accent) 48%, transparent);
}

.experiment-export__actions > span {
  grid-column: 1 / -1;
  color: var(--theme-accent-strong);
  font-size: 8px;
}

.experiment-export__totals {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin: 0;
}

.experiment-export__totals > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  background: color-mix(in srgb, var(--theme-workspace) 72%, transparent);
  border-radius: 10px;
}

.experiment-export__totals dt {
  color: var(--theme-faint);
  font-size: 8px;
}

.experiment-export__totals dd {
  margin: 0;
  color: var(--theme-text);
  font-size: 9px;
  font-weight: 700;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@keyframes thinking {
  0%, 70%, 100% { opacity: 0.3; transform: translateY(0); }
  35% { opacity: 1; transform: translateY(-3px); }
}

@keyframes card-in {
  from { opacity: 0; transform: translateY(7px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 1000px) {
  .experiment-intro {
    grid-template-columns: 1fr;
    gap: 22px;
  }

  .experiment-intro h3,
  .experiment-intro > div > p {
    max-width: 620px;
  }
}

@media (max-width: 760px) {
  .experiment-scroll {
    padding: 20px 15px 28px;
  }

  .experiment-intro {
    padding-bottom: 24px;
  }

  .experiment-intro h3 {
    font-size: 28px;
  }

  .strategy-grid {
    grid-template-columns: 1fr;
  }

  .results-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .results-heading__meta {
    justify-items: start;
  }

  .experiment-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .winner-options {
    grid-template-columns: 1fr;
  }

  .judge-evaluations {
    grid-template-columns: 1fr;
  }

  .experiment-export {
    grid-template-columns: 1fr;
  }

  .experiment-export__actions {
    justify-content: start;
    justify-items: start;
  }
}

@media (max-width: 460px) {
  .experiment-form {
    padding: 14px;
  }

  .experiment-form__footer {
    align-items: stretch;
    flex-direction: column;
  }

  .experiment-form__footer > span {
    align-self: flex-end;
  }

  .experiment-form button {
    width: 100%;
  }

  .manual-verdict > header {
    align-items: flex-start;
    flex-direction: column;
  }

  .ai-judge > header,
  .judge-winner {
    align-items: flex-start;
    flex-direction: column;
  }

  .judge-button {
    width: 100%;
  }

  .criterion-row {
    align-items: flex-start;
    flex-direction: column;
  }

  .experiment-metrics,
  .experiment-export__totals {
    grid-template-columns: 1fr;
  }

  .experiment-export__actions {
    grid-template-columns: 1fr 1fr;
  }

  .experiment-export__actions button {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .strategy-card,
  .strategy-loading span,
  .button-spinner {
    animation: none;
  }
}

/* Readability pass shared by desktop and compact layouts. */
.experiment-intro,
.experiment-results {
  max-width: 1160px;
}

.experiment-eyebrow {
  font-size: 10px;
}

.experiment-intro > div > p {
  max-width: 440px;
  font-size: 15px;
}

.experiment-form {
  padding: 22px;
}

.experiment-form label {
  font-size: 11px;
}

.experiment-form textarea {
  font-size: 15px;
  line-height: 1.6;
}

.experiment-form__footer {
  font-size: 10px;
}

.experiment-form button {
  font-size: 13px;
}

.results-heading h3 {
  font-size: 24px;
}

.experiment-id,
.results-heading__meta > span:last-child {
  font-size: 9px;
}

.experiment-metrics span {
  font-size: 9px;
}

.experiment-metrics strong {
  font-size: 14px;
}

.strategy-card {
  min-height: 290px;
}

.strategy-card__header {
  padding: 19px 20px;
}

.strategy-card__header h4 {
  font-size: 15px;
}

.strategy-card__header p {
  font-size: 11px;
}

.strategy-number,
.result-state {
  font-size: 9px;
}

.strategy-content {
  padding: 19px 20px 16px;
}

.strategy-answer {
  font-size: 13px;
  line-height: 1.68;
}

.generated-prompt summary,
.strategy-evaluation summary {
  font-size: 11px;
}

.generated-prompt p {
  font-size: 11px;
}

.expert-view > span,
.expert-consensus__heading > span,
.expert-comparison > span {
  font-size: 9px;
}

.expert-view p,
.expert-consensus p,
.expert-comparison p {
  font-size: 12px;
  line-height: 1.62;
}

.expert-consensus__heading em {
  font-size: 8px;
}

.strategy-metrics summary,
.strategy-metrics summary > strong,
.strategy-metrics__grid span,
.strategy-metrics > p {
  font-size: 10px;
}

.criterion-row > span {
  font-size: 11px;
}

.rating-scale button {
  width: 27px;
  height: 27px;
  font-size: 10px;
}

.strategy-card__footer > span,
.strategy-card__footer button {
  font-size: 10px;
}

.manual-verdict h3 {
  font-size: 21px;
}

.manual-verdict__state {
  font-size: 9px;
}

.winner-option > span {
  font-size: 12px;
}

.winner-option > strong {
  font-size: 10px;
}

.evaluation-comment > span {
  font-size: 11px;
}

.evaluation-comment textarea {
  font-size: 13px;
}

.verdict-summary,
.ai-judge header p,
.judge-note,
.judge-loading,
.judge-error {
  font-size: 11px;
}

.ai-judge h4 {
  font-size: 16px;
}

.judge-button {
  font-size: 11px;
}

.judge-winner span,
.judge-winner em,
.judge-metrics span {
  font-size: 9px;
}

.judge-winner strong {
  font-size: 15px;
}

.judge-explanation {
  font-size: 12px;
}

.judge-evaluations article > header {
  font-size: 12px;
}

.judge-evaluations article > header strong {
  font-size: 11px;
}

.judge-scores span,
.judge-evaluations article > p {
  font-size: 9px;
}

.experiment-export h3 {
  font-size: 19px;
}

.experiment-export p,
.experiment-export__actions button {
  font-size: 11px;
}

.experiment-export__totals dt,
.experiment-export__totals dd {
  font-size: 10px;
}

@media (max-width: 720px) {
  .experiment-intro > div > p {
    font-size: 14px;
  }

  .strategy-answer {
    font-size: 13px;
  }
}
</style>
