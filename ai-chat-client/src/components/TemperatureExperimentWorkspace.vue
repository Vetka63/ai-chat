<script setup>
import { computed, ref } from 'vue'
import { judgeTemperatureExperiment, runTemperatureExperiment } from '../api/chat.js'
import { downloadTemperatureReport } from '../utils/temperatureReport.js'

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

const defaultVariants = [
  {
    id: 'precise',
    title: 'Точный',
    description: 'Сфокусированный и наиболее предсказуемый ответ',
    temperature: 0,
  },
  {
    id: 'balanced',
    title: 'Сбалансированный',
    description: 'Баланс точности, естественности и вариативности',
    temperature: 0.7,
  },
  {
    id: 'creative',
    title: 'Творческий',
    description: 'Больше неожиданных идей и формулировок',
    temperature: 1.2,
  },
  {
    id: 'experimental',
    title: 'Экспериментальный',
    description: 'Предельная вариативность и наиболее неожиданные ответы',
    temperature: 2,
  },
]

const criteria = [
  { id: 'accuracy', label: 'Точность' },
  { id: 'creativity', label: 'Креативность' },
]

const task = ref('Придумай концепцию мобильного приложения для подготовки школьников к экзаменам. Укажи название, три основные функции, целевую аудиторию и одно ограничение. Ответ — не более 150 слов.')
const submittedTask = ref('')
const experimentId = ref('')
const results = ref([])
const metrics = ref(null)
const ratings = ref({})
const diversity = ref(0)
const winner = ref('')
const conclusion = ref('')
const isRunning = ref(false)
const error = ref('')
const exportNotice = ref('')
const judgeResult = ref(null)
const judgeLoading = ref(false)
const judgeError = ref('')

const cards = computed(() => {
  if (results.value.length) return results.value
  return defaultVariants.map((variant) => ({
    ...variant,
    status: isRunning.value ? 'loading' : 'idle',
  }))
})

const successfulCards = computed(() => (
  cards.value.filter((card) => card.status === 'success')
))

const canRunJudge = computed(() => (
  successfulCards.value.length >= 2 && !isRunning.value && !judgeLoading.value
))

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

function setRating(variantId, criterion, value) {
  ratings.value = {
    ...ratings.value,
    [variantId]: {
      ...(ratings.value[variantId] || {}),
      [criterion]: value,
    },
  }
}

function ratingValue(variantId, criterion) {
  return ratings.value[variantId]?.[criterion] || 0
}

async function runExperiment() {
  const value = task.value.trim()
  if (!value || isRunning.value) return

  submittedTask.value = value
  experimentId.value = ''
  results.value = []
  metrics.value = null
  ratings.value = {}
  diversity.value = 0
  winner.value = ''
  conclusion.value = ''
  error.value = ''
  exportNotice.value = ''
  judgeResult.value = null
  judgeError.value = ''
  isRunning.value = true
  try {
    const response = await runTemperatureExperiment({
      task: value,
      profileId: props.profile.id,
    })
    experimentId.value = response.experimentId
    results.value = response.results || []
    metrics.value = response.metrics || null
  } catch (requestError) {
    error.value = requestError instanceof Error
      ? requestError.message
      : 'Не удалось выполнить температурный эксперимент'
  } finally {
    isRunning.value = false
  }
}

async function runJudge() {
  if (!canRunJudge.value) return

  judgeLoading.value = true
  judgeError.value = ''
  try {
    judgeResult.value = await judgeTemperatureExperiment({
      task: submittedTask.value,
      profileId: props.profile.id,
      candidates: successfulCards.value.map((card) => ({
        variantId: card.id,
        answer: card.answer,
      })),
    })
  } catch (requestError) {
    judgeError.value = requestError instanceof Error
      ? requestError.message
      : 'Не удалось получить оценку AI-судьи'
  } finally {
    judgeLoading.value = false
  }
}

function downloadReport() {
  downloadTemperatureReport({
    experimentId: experimentId.value,
    task: submittedTask.value,
    results: cards.value,
    ratings: ratings.value,
    diversity: diversity.value,
    winner: winner.value,
    conclusion: conclusion.value,
    judgeResult: judgeResult.value,
  })
  exportNotice.value = 'Markdown-отчёт скачан'
}
</script>

<template>
  <section class="experiment-workspace temperature-workspace" aria-label="Эксперимент с температурой">
    <header class="conversation-header experiment-header">
      <div class="conversation-identity">
        <div class="profile-glyph profile-glyph--temperature" aria-hidden="true">
          <svg viewBox="0 0 24 24">
            <path d="M9 5a3 3 0 0 1 6 0v8.2a5 5 0 1 1-6 0V5Z" />
            <path d="M12 8v7" />
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

    <div class="experiment-scroll temperature-scroll">
      <section class="experiment-intro temperature-intro">
        <div>
          <span class="experiment-eyebrow">Один запрос · четыре температуры</span>
          <h3>Как случайность меняет ответ модели</h3>
          <p>
            DeepSeek получит один и тот же запрос при temperature 0, 0.7, 1.2 и 2.0.
            Модель, промпт и лимиты останутся одинаковыми, история чата не используется.
          </p>
        </div>

        <form class="experiment-form temperature-form" @submit.prevent="runExperiment">
          <label for="temperature-task">Запрос для сравнения</label>
          <textarea
            id="temperature-task"
            v-model="task"
            maxlength="10000"
            rows="5"
            :disabled="isRunning"
            placeholder="Введите задачу, в которой можно сравнить точность и креативность…"
          ></textarea>
          <div class="experiment-form__footer">
            <span>{{ task.length }} / 10 000</span>
            <button type="submit" :disabled="!task.trim() || isRunning">
              <span v-if="isRunning" class="button-spinner" aria-hidden="true"></span>
              {{ isRunning ? 'Получаем ответы…' : 'Сравнить температуры' }}
            </button>
          </div>
        </form>
      </section>

      <section class="temperature-scale" aria-label="Шкала температур">
        <article v-for="variant in defaultVariants" :key="variant.id">
          <div class="temperature-scale__value">{{ variant.temperature }}</div>
          <div>
            <strong>{{ variant.title }}</strong>
            <span>{{ variant.description }}</span>
          </div>
        </article>
      </section>

      <p v-if="error" class="temperature-request-error" role="alert">{{ error }}</p>

      <section
        v-if="isRunning || results.length"
        class="experiment-results temperature-results"
        aria-live="polite"
      >
        <div class="results-heading">
          <div>
            <span class="experiment-eyebrow">Результаты эксперимента</span>
            <h3>Четыре версии одного ответа</h3>
          </div>
          <div v-if="experimentId" class="results-heading__meta">
            <span class="experiment-id">Запуск {{ experimentId.slice(0, 8) }}</span>
            <span>История не сохраняется</span>
          </div>
        </div>

        <section v-if="metrics" class="experiment-metrics" aria-label="Метрики температурного эксперимента">
          <div>
            <span>API-вызовы</span>
            <strong>{{ metrics.apiCalls }}</strong>
          </div>
          <div>
            <span>Токены</span>
            <strong>{{ formatTokens(metrics.totalTokens) }}</strong>
          </div>
          <div>
            <span>Полное время</span>
            <strong>{{ formatDuration(metrics.elapsedMs) }}</strong>
          </div>
          <div>
            <span>Расчётная стоимость</span>
            <strong>{{ formatCost(metrics.estimatedCostUsd) }}</strong>
          </div>
        </section>

        <div class="temperature-grid">
          <article
            v-for="card in cards"
            :key="card.id"
            class="temperature-card"
            :class="[`temperature-card--${card.id}`, `temperature-card--${card.status}`]"
          >
            <header class="temperature-card__header">
              <div class="temperature-orb">
                <strong>{{ card.temperature }}</strong>
                <span>temp</span>
              </div>
              <div>
                <h4>{{ card.title }}</h4>
                <p>{{ card.description }}</p>
              </div>
              <span v-if="card.status === 'success'" class="result-state result-state--success">Готово</span>
              <span v-else-if="card.status === 'error'" class="result-state result-state--error">Ошибка</span>
            </header>

            <div v-if="card.status === 'loading'" class="strategy-loading">
              <span></span><span></span><span></span>
              <p>DeepSeek формирует вариант…</p>
            </div>

            <div v-else-if="card.status === 'error'" class="strategy-error">
              <p>{{ card.error }}</p>
              <span>Другие ответы доступны для сравнения</span>
            </div>

            <template v-else-if="card.status === 'success'">
              <p class="temperature-answer">{{ card.answer }}</p>

              <details v-if="card.metrics" class="strategy-metrics">
                <summary>
                  <span>{{ formatTokens(card.metrics.totalTokens) }} токенов</span>
                  <strong>{{ formatDuration(card.metrics.elapsedMs) }}</strong>
                </summary>
                <div class="strategy-metrics__grid">
                  <span>Вход <strong>{{ formatTokens(card.metrics.promptTokens) }}</strong></span>
                  <span>Выход <strong>{{ formatTokens(card.metrics.completionTokens) }}</strong></span>
                  <span>API-время <strong>{{ formatDuration(card.metrics.apiDurationMs) }}</strong></span>
                  <span>Модель <strong>{{ card.model }}</strong></span>
                </div>
              </details>

              <div class="temperature-ratings">
                <div v-for="criterion in criteria" :key="criterion.id" class="criterion-row">
                  <span>{{ criterion.label }}</span>
                  <div class="rating-scale" role="group" :aria-label="criterion.label + ' для ' + card.title">
                    <button
                      v-for="value in 5"
                      :key="value"
                      type="button"
                      :class="{ 'rating-button--active': ratingValue(card.id, criterion.id) === value }"
                      :aria-label="`${criterion.label}: ${value} из 5 для ${card.title}`"
                      :aria-pressed="ratingValue(card.id, criterion.id) === value"
                      @click="setRating(card.id, criterion.id, value)"
                    >
                      {{ value }}
                    </button>
                  </div>
                </div>
              </div>

              <button
                type="button"
                class="temperature-winner"
                :class="{ 'temperature-winner--selected': winner === card.id }"
                :aria-pressed="winner === card.id"
                @click="winner = card.id"
              >
                {{ winner === card.id ? 'Выбран лучшим' : 'Выбрать лучшим' }}
              </button>
            </template>
          </article>
        </div>

        <section v-if="successfulCards.length" class="temperature-summary">
          <div class="temperature-summary__heading">
            <span class="experiment-eyebrow">Ваши выводы</span>
            <h3>Сравните ответы между собой</h3>
          </div>

          <div class="diversity-rating">
            <div>
              <strong>Разнообразие ответов</strong>
              <span>Насколько заметно различаются идеи и формулировки?</span>
            </div>
            <div class="rating-scale" role="group" aria-label="Разнообразие ответов">
              <button
                v-for="value in 5"
                :key="value"
                type="button"
                :class="{ 'rating-button--active': diversity === value }"
                :aria-label="`Разнообразие: ${value} из 5`"
                :aria-pressed="diversity === value"
                @click="diversity = value"
              >
                {{ value }}
              </button>
            </div>
          </div>

          <label class="temperature-conclusion">
            <span>Итог эксперимента</span>
            <textarea
              v-model="conclusion"
              rows="3"
              placeholder="Например: при 0 ответ точнее соблюдает условия, а при 2.0 предлагает самые неожиданные идеи…"
            ></textarea>
          </label>

          <div class="temperature-guidance">
            <article>
              <strong>0</strong>
              <p>Вычисления, классификация и строгие инструкции.</p>
            </article>
            <article>
              <strong>0.7</strong>
              <p>Обычный чат, объяснения и управляемая генерация идей.</p>
            </article>
            <article>
              <strong>1.2</strong>
              <p>Мозговой штурм, названия, сюжеты и творческие варианты.</p>
            </article>
            <article>
              <strong>2.0</strong>
              <p>Экспериментальные идеи, максимальная вариативность и намеренный риск.</p>
            </article>
          </div>

          <section class="temperature-judge">
            <header>
              <div>
                <span class="experiment-eyebrow">Независимая проверка</span>
                <h4>AI-судья DeepSeek Pro</h4>
                <p>Ответы передаются без названий и температур — только как варианты A–D.</p>
              </div>
              <button
                type="button"
                class="temperature-judge__button"
                :disabled="!canRunJudge"
                @click="runJudge"
              >
                <span v-if="judgeLoading" class="button-spinner" aria-hidden="true"></span>
                {{ judgeLoading ? 'Оцениваем…' : judgeResult ? 'Оценить заново' : 'Запустить AI-судью' }}
              </button>
            </header>

            <div v-if="judgeError" class="temperature-judge__error" role="alert">
              <span>{{ judgeError }}</span>
              <button type="button" :disabled="!canRunJudge" @click="runJudge">Повторить</button>
            </div>

            <div v-else-if="judgeResult" class="temperature-judge__result" aria-live="polite">
              <div class="temperature-judge__winner">
                <span>Рекомендация модели</span>
                <strong>temperature {{ judgeResult.winnerTemperature }} · {{ judgeResult.winnerTitle }}</strong>
                <em v-if="winner">
                  {{ winner === judgeResult.winnerVariantId
                    ? 'Совпадает с вашим выбором'
                    : 'Отличается от вашего выбора' }}
                </em>
              </div>

              <p class="temperature-judge__explanation">{{ judgeResult.explanation }}</p>

              <div class="temperature-judge__diversity">
                <strong>Разнообразие: {{ judgeResult.diversityScore }} / 10</strong>
                <span>{{ judgeResult.diversityExplanation }}</span>
              </div>

              <div class="temperature-judge__evaluations">
                <article
                  v-for="evaluation in judgeResult.evaluations"
                  :key="evaluation.variantId"
                  :class="{ 'is-winner': evaluation.variantId === judgeResult.winnerVariantId }"
                >
                  <header>
                    <strong>{{ evaluation.temperature }} · {{ evaluation.title }}</strong>
                    <span>{{ evaluation.average }} / 10</span>
                  </header>
                  <dl>
                    <div><dt>Точность</dt><dd>{{ evaluation.scores.accuracy }}</dd></div>
                    <div><dt>Креативность</dt><dd>{{ evaluation.scores.creativity }}</dd></div>
                    <div><dt>Следование запросу</dt><dd>{{ evaluation.scores.instructionFollowing }}</dd></div>
                  </dl>
                  <p><b>Сильные стороны:</b> {{ evaluation.strengths }}</p>
                  <p><b>Слабые стороны:</b> {{ evaluation.weaknesses }}</p>
                </article>
              </div>

              <div class="temperature-judge__metrics">
                <span>Судья: {{ judgeResult.model }}</span>
                <span>{{ judgeResult.metrics.apiCalls }} API-выз.</span>
                <span>{{ formatTokens(judgeResult.metrics.totalTokens) }} токенов</span>
                <span>{{ formatDuration(judgeResult.metrics.elapsedMs) }}</span>
                <span>{{ formatCost(judgeResult.metrics.estimatedCostUsd) }}</span>
              </div>
            </div>

            <p v-else class="temperature-judge__note">
              Это отдельный API-вызов. Ручная оценка и выбор пользователя не изменяются.
            </p>
          </section>

          <div class="temperature-report-actions">
            <button type="button" @click="downloadReport">Скачать Markdown</button>
            <span v-if="exportNotice" role="status">{{ exportNotice }}</span>
          </div>
        </section>
      </section>
    </div>
  </section>
</template>

<style scoped>
.temperature-workspace {
  min-width: 0;
  min-height: 0;
  height: 100%;
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  overflow: hidden;
  color: var(--theme-text);
  background:
    radial-gradient(circle at 90% 5%, color-mix(in srgb, var(--theme-accent) 12%, transparent), transparent 28rem),
    var(--theme-workspace);
}

.experiment-header {
  position: relative;
  z-index: 2;
}

.profile-glyph--temperature {
  color: var(--theme-accent-strong);
  background: linear-gradient(145deg, var(--theme-accent-soft), var(--theme-card));
}

.profile-glyph--temperature svg {
  width: 25px;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.7;
}

.temperature-scroll {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  padding: clamp(22px, 3vw, 42px);
}

.temperature-intro {
  display: grid;
  grid-template-columns: minmax(0, 0.82fr) minmax(420px, 1.18fr);
  gap: clamp(24px, 4vw, 58px);
  align-items: center;
  padding: clamp(24px, 3vw, 42px);
  background:
    radial-gradient(circle at 0 0, color-mix(in srgb, var(--theme-accent) 13%, transparent), transparent 19rem),
    color-mix(in srgb, var(--theme-card) 94%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 28px;
  box-shadow: 0 18px 50px var(--theme-shadow);
}

.experiment-eyebrow {
  color: var(--theme-accent-strong);
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.temperature-intro h3,
.results-heading h3,
.temperature-summary h3 {
  margin: 8px 0 10px;
  color: var(--theme-text);
  font-family: Georgia, "Times New Roman", serif;
  font-size: clamp(24px, 2.4vw, 38px);
  font-weight: 600;
  line-height: 1.13;
}

.temperature-intro > div > p {
  max-width: 560px;
  margin: 0;
  color: var(--theme-muted);
  font-size: 14px;
  line-height: 1.7;
}

.temperature-form {
  display: grid;
  gap: 10px;
  padding: 18px;
  background: color-mix(in srgb, var(--theme-workspace) 84%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 21px;
}

.temperature-form label,
.temperature-conclusion > span {
  color: var(--theme-text);
  font-size: 12px;
  font-weight: 750;
}

.temperature-form textarea,
.temperature-conclusion textarea {
  width: 100%;
  resize: vertical;
  color: var(--theme-text);
  background: var(--theme-card);
  border: 1px solid var(--theme-border);
  border-radius: 15px;
  outline: none;
  font: inherit;
  font-size: 14px;
  line-height: 1.55;
  transition: border-color 160ms ease, box-shadow 160ms ease;
}

.temperature-form textarea {
  min-height: 132px;
  padding: 14px 15px;
}

.temperature-conclusion textarea {
  min-height: 94px;
  padding: 13px 14px;
}

.temperature-form textarea:focus,
.temperature-conclusion textarea:focus {
  border-color: var(--theme-accent);
  box-shadow: 0 0 0 3px var(--theme-accent-soft);
}

.experiment-form__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.experiment-form__footer > span {
  color: var(--theme-muted);
  font-size: 10px;
}

.experiment-form__footer button,
.temperature-report-actions button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 11px 17px;
  color: #fff;
  background: linear-gradient(145deg, var(--theme-accent), var(--theme-accent-strong));
  border: 0;
  border-radius: 13px;
  box-shadow: 0 9px 22px color-mix(in srgb, var(--theme-accent) 22%, transparent);
  cursor: pointer;
  font-size: 12px;
  font-weight: 750;
}

.experiment-form__footer button:disabled {
  cursor: default;
  opacity: 0.56;
}

.button-spinner {
  width: 13px;
  height: 13px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: 50%;
  animation: temperature-spin 700ms linear infinite;
}

.temperature-scale {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  margin: 18px 0 30px;
}

.temperature-scale article {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: color-mix(in srgb, var(--theme-card) 90%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 17px;
}

.temperature-scale__value {
  min-width: 48px;
  color: var(--theme-accent-strong);
  font-family: Georgia, "Times New Roman", serif;
  font-size: 24px;
  text-align: center;
}

.temperature-scale article div:last-child {
  display: grid;
  gap: 3px;
}

.temperature-scale strong {
  font-size: 12px;
}

.temperature-scale span {
  color: var(--theme-muted);
  font-size: 10px;
  line-height: 1.35;
}

.temperature-request-error {
  margin: 0 0 20px;
  padding: 13px 16px;
  color: #9a3c3c;
  background: rgba(206, 84, 84, 0.1);
  border: 1px solid rgba(206, 84, 84, 0.2);
  border-radius: 14px;
  font-size: 13px;
}

.temperature-results {
  display: grid;
  gap: 18px;
}

.results-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
}

.results-heading h3,
.temperature-summary h3 {
  margin-bottom: 0;
  font-size: clamp(22px, 2vw, 31px);
}

.results-heading__meta {
  display: grid;
  justify-items: end;
  gap: 4px;
  color: var(--theme-muted);
  font-size: 10px;
}

.experiment-id {
  color: var(--theme-accent-strong);
  font-weight: 750;
}

.experiment-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  overflow: hidden;
  background: var(--theme-border);
  border: 1px solid var(--theme-border);
  border-radius: 17px;
}

.experiment-metrics > div {
  display: grid;
  gap: 5px;
  padding: 13px 15px;
  background: var(--theme-card);
}

.experiment-metrics span {
  color: var(--theme-muted);
  font-size: 9px;
  text-transform: uppercase;
}

.experiment-metrics strong {
  font-size: 13px;
}

.temperature-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 15px;
  align-items: start;
}

.temperature-card {
  min-width: 0;
  display: grid;
  gap: 14px;
  padding: 18px;
  background: var(--theme-card);
  border: 1px solid var(--theme-border);
  border-radius: 22px;
  box-shadow: 0 14px 34px color-mix(in srgb, var(--theme-shadow) 75%, transparent);
}

.temperature-card--precise {
  border-top: 4px solid #5d9ab3;
}

.temperature-card--balanced {
  border-top: 4px solid var(--theme-accent);
}

.temperature-card--creative {
  border-top: 4px solid #b36c9a;
}

.temperature-card--experimental {
  border-top: 4px solid #a44f63;
}

.temperature-card__header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
}

.temperature-card__header h4 {
  margin: 0 0 3px;
  font-size: 15px;
}

.temperature-card__header p {
  margin: 0;
  color: var(--theme-muted);
  font-size: 9px;
  line-height: 1.35;
}

.temperature-orb {
  width: 52px;
  height: 52px;
  display: grid;
  place-content: center;
  justify-items: center;
  color: var(--theme-accent-strong);
  background: var(--theme-accent-soft);
  border-radius: 50%;
}

.temperature-orb strong {
  font-family: Georgia, "Times New Roman", serif;
  font-size: 20px;
  line-height: 1;
}

.temperature-orb span {
  margin-top: 2px;
  font-size: 7px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.result-state {
  padding: 5px 7px;
  border-radius: 999px;
  font-size: 8px;
  font-weight: 800;
  text-transform: uppercase;
}

.result-state--success {
  color: var(--theme-accent-strong);
  background: var(--theme-accent-soft);
}

.result-state--error {
  color: #9a3c3c;
  background: rgba(206, 84, 84, 0.12);
}

.strategy-loading,
.strategy-error {
  min-height: 180px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 5px;
  color: var(--theme-muted);
  text-align: center;
}

.strategy-loading > span {
  width: 7px;
  height: 7px;
  background: var(--theme-accent);
  border-radius: 50%;
  animation: temperature-pulse 900ms infinite alternate ease-in-out;
}

.strategy-loading > span:nth-child(2) { animation-delay: 150ms; }
.strategy-loading > span:nth-child(3) { animation-delay: 300ms; }

.strategy-loading p,
.strategy-error p,
.strategy-error span {
  flex-basis: 100%;
  margin: 5px 0 0;
  font-size: 11px;
}

.temperature-answer {
  min-height: 210px;
  max-height: 410px;
  margin: 0;
  padding: 15px;
  overflow: auto;
  color: var(--theme-body);
  background: color-mix(in srgb, var(--theme-workspace) 78%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 15px;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
}

.strategy-metrics {
  padding: 10px 12px;
  color: var(--theme-muted);
  background: var(--theme-workspace);
  border: 1px solid var(--theme-border);
  border-radius: 13px;
  font-size: 9px;
}

.strategy-metrics summary {
  display: flex;
  justify-content: space-between;
  cursor: pointer;
}

.strategy-metrics__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px;
  margin-top: 10px;
}

.strategy-metrics__grid span {
  display: grid;
  gap: 2px;
}

.strategy-metrics__grid strong {
  overflow-wrap: anywhere;
  color: var(--theme-text);
}

.temperature-ratings {
  display: grid;
  gap: 9px;
}

.criterion-row,
.diversity-rating {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.criterion-row > span {
  color: var(--theme-muted);
  font-size: 10px;
}

.rating-scale {
  display: flex;
  gap: 4px;
}

.rating-scale button {
  width: 25px;
  height: 25px;
  color: var(--theme-muted);
  background: var(--theme-workspace);
  border: 1px solid var(--theme-border);
  border-radius: 8px;
  cursor: pointer;
  font-size: 9px;
  font-weight: 750;
}

.rating-scale button:hover,
.rating-scale .rating-button--active {
  color: #fff;
  background: var(--theme-accent);
  border-color: var(--theme-accent);
}

.temperature-winner {
  width: 100%;
  padding: 9px 12px;
  color: var(--theme-accent-strong);
  background: transparent;
  border: 1px solid var(--theme-border);
  border-radius: 11px;
  cursor: pointer;
  font-size: 10px;
  font-weight: 750;
}

.temperature-winner--selected {
  background: var(--theme-accent-soft);
  border-color: var(--theme-accent);
}

.temperature-summary {
  display: grid;
  gap: 17px;
  margin-top: 8px;
  padding: clamp(20px, 3vw, 32px);
  background: color-mix(in srgb, var(--theme-card) 94%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 24px;
}

.temperature-summary__heading h3 {
  margin-top: 6px;
}

.diversity-rating {
  padding: 14px 16px;
  background: var(--theme-workspace);
  border-radius: 15px;
}

.diversity-rating > div:first-child {
  display: grid;
  gap: 3px;
}

.diversity-rating strong {
  font-size: 12px;
}

.diversity-rating span {
  color: var(--theme-muted);
  font-size: 10px;
}

.temperature-conclusion {
  display: grid;
  gap: 8px;
}

.temperature-guidance {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 10px;
}

.temperature-guidance article {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 10px;
  align-items: center;
  padding: 13px;
  background: var(--theme-workspace);
  border: 1px solid var(--theme-border);
  border-radius: 14px;
}

.temperature-guidance strong {
  color: var(--theme-accent-strong);
  font-family: Georgia, "Times New Roman", serif;
  font-size: 22px;
}

.temperature-guidance p {
  margin: 0;
  color: var(--theme-muted);
  font-size: 9px;
  line-height: 1.4;
}

.temperature-judge {
  display: grid;
  gap: 15px;
  padding: 20px;
  background: color-mix(in srgb, var(--theme-workspace) 84%, transparent);
  border: 1px solid var(--theme-border);
  border-radius: 19px;
}

.temperature-judge > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.temperature-judge h4 {
  margin: 4px 0 0;
  color: var(--theme-text);
  font-size: 17px;
}

.temperature-judge header p,
.temperature-judge__note {
  margin: 5px 0 0;
  color: var(--theme-muted);
  font-size: 10px;
  line-height: 1.5;
}

.temperature-judge__button,
.temperature-judge__error button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 14px;
  color: #fff;
  background: var(--theme-accent);
  border: 0;
  border-radius: 11px;
  cursor: pointer;
  font-size: 10px;
  font-weight: 750;
  white-space: nowrap;
}

.temperature-judge__button:disabled,
.temperature-judge__error button:disabled {
  cursor: default;
  opacity: 0.55;
}

.temperature-judge__error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 14px;
  color: #9a3c3c;
  background: rgba(206, 84, 84, 0.1);
  border-radius: 12px;
  font-size: 11px;
}

.temperature-judge__result {
  display: grid;
  gap: 14px;
}

.temperature-judge__winner {
  display: grid;
  gap: 3px;
  padding: 14px 16px;
  background: var(--theme-accent-soft);
  border-radius: 14px;
}

.temperature-judge__winner span,
.temperature-judge__winner em {
  color: var(--theme-muted);
  font-size: 9px;
  font-style: normal;
}

.temperature-judge__winner strong {
  color: var(--theme-accent-strong);
  font-size: 15px;
}

.temperature-judge__explanation {
  margin: 0;
  color: var(--theme-body);
  font-size: 12px;
  line-height: 1.6;
}

.temperature-judge__diversity {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  background: var(--theme-card);
  border: 1px solid var(--theme-border);
  border-radius: 12px;
}

.temperature-judge__diversity strong {
  font-size: 11px;
}

.temperature-judge__diversity span {
  color: var(--theme-muted);
  font-size: 10px;
  line-height: 1.5;
}

.temperature-judge__evaluations {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
}

.temperature-judge__evaluations > article {
  display: grid;
  gap: 9px;
  padding: 13px;
  background: var(--theme-card);
  border: 1px solid var(--theme-border);
  border-radius: 13px;
}

.temperature-judge__evaluations > article.is-winner {
  border-color: var(--theme-accent);
  box-shadow: inset 3px 0 0 var(--theme-accent);
}

.temperature-judge__evaluations article > header {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.temperature-judge__evaluations article > header strong {
  font-size: 11px;
}

.temperature-judge__evaluations article > header span {
  color: var(--theme-accent-strong);
  font-size: 10px;
  font-weight: 750;
}

.temperature-judge__evaluations dl {
  display: grid;
  gap: 5px;
  margin: 0;
}

.temperature-judge__evaluations dl div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.temperature-judge__evaluations dt,
.temperature-judge__evaluations dd,
.temperature-judge__evaluations p {
  margin: 0;
  color: var(--theme-muted);
  font-size: 9px;
  line-height: 1.5;
}

.temperature-judge__evaluations dd {
  color: var(--theme-text);
  font-weight: 750;
}

.temperature-judge__metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.temperature-judge__metrics span {
  padding: 5px 8px;
  color: var(--theme-muted);
  background: var(--theme-card);
  border: 1px solid var(--theme-border);
  border-radius: 999px;
  font-size: 8px;
}

.temperature-report-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.temperature-report-actions span {
  color: var(--theme-accent-strong);
  font-size: 10px;
}

@keyframes temperature-spin {
  to { transform: rotate(360deg); }
}

@keyframes temperature-pulse {
  to { opacity: 0.25; transform: translateY(-3px); }
}

@media (max-width: 1120px) {
  .temperature-intro {
    grid-template-columns: 1fr;
  }

  .temperature-grid {
    grid-template-columns: 1fr;
  }

  .temperature-answer {
    min-height: 0;
    max-height: 320px;
  }
}

@media (max-width: 720px) {
  .temperature-scroll {
    padding: 15px;
  }

  .temperature-intro {
    padding: 18px;
    border-radius: 20px;
  }

  .temperature-scale,
  .experiment-metrics,
  .temperature-guidance {
    grid-template-columns: 1fr;
  }

  .results-heading,
  .diversity-rating,
  .temperature-judge > header,
  .temperature-judge__error {
    align-items: stretch;
    flex-direction: column;
  }

  .results-heading__meta {
    justify-items: start;
  }

  .temperature-card__header {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .result-state {
    grid-column: 2;
    justify-self: start;
  }

  .temperature-judge__button,
  .temperature-judge__error button {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .button-spinner,
  .strategy-loading > span {
    animation: none;
  }
}
</style>
