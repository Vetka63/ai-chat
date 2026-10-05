<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { api, type RuntimeInfo, type Run, type Summary, type Scenario, type Status } from './api'

const runtime = ref<RuntimeInfo | null>(null), scenarios = ref<Scenario[]>([]), history = ref<Summary[]>([])
const current = ref<Run | null>(null), scenarioId = ref<string | null>(null), prompt = ref(''), model = ref('')
const temperature = ref(.7), thinking = ref(false), maxOutputText = ref<string | number>('')
const maxOutput = computed(() => String(maxOutputText.value).trim() === '' ? null : Number(maxOutputText.value))
const busy = ref(false), loading = ref(false), error = ref(''), answerElement = ref<HTMLElement | null>(null)
const scenario = computed(() => scenarios.value.find(s => s.id === scenarioId.value))
const invalid = computed(() => !prompt.value.trim() || prompt.value.length > 16000 || !runtime.value?.models.some(m => m.name === model.value) || !Number.isFinite(temperature.value) || temperature.value < 0 || temperature.value > 2 || (maxOutput.value !== null && (maxOutput.value < 1 || maxOutput.value > 32768 || !Number.isInteger(maxOutput.value))))
const labels: Record<Status,string> = { RUNNING: 'Выполняется', COMPLETED: 'Ответ получен', TRUNCATED: 'Ответ обрезан лимитом', FAILED: 'Ошибка запуска' }
const seconds = (n: number | null | undefined) => n == null ? '—' : `${(n / 1000).toFixed(2)} с`
const date = (n: string) => new Date(n).toLocaleString('ru-RU')

async function refresh() {
  loading.value = true; error.value = ''
  try {
    const [info, cases, runs] = await Promise.all([api.runtime(), api.scenarios(), api.runs()])
    runtime.value = info; scenarios.value = cases; history.value = runs
    if (!info.models.some(m => m.name === model.value)) model.value = info.models[0]?.name ?? ''
    if (current.value) current.value = await api.run(current.value.id)
  } catch (e) { error.value = String(e) } finally { loading.value = false }
}
onMounted(async () => { await refresh(); if (history.value[0]) await openRun(history.value[0].id) })
function choose(s: Scenario) { scenarioId.value = s.id; prompt.value = s.prompt; current.value = null; error.value = '' }
function newRequest() { current.value = null; prompt.value = ''; scenarioId.value = null; error.value = '' }
async function openRun(id: string) {
  if (busy.value) return
  loading.value = true
  try { current.value = await api.run(id); await nextTick(); answerElement.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
  catch (e) { error.value = String(e) } finally { loading.value = false }
}
async function send() {
  if (busy.value || invalid.value) return
  busy.value = true; error.value = ''; current.value = null
  try {
    current.value = await api.generate({ model: model.value, prompt: prompt.value.trim(), temperature: temperature.value, thinking: thinking.value, maxOutputTokens: maxOutput.value, scenarioId: scenarioId.value })
    await nextTick(); answerElement.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch (e) { error.value = `${String(e)}. Если соединение прервалось, обновите список: сервер мог сохранить результат. Повтор автоматически не отправляется.` }
  finally { busy.value = false; try { history.value = await api.runs() } catch (e) { error.value = String(e) } }
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">L</span><div><strong>Local Lab</strong><small>Лаборатория локальных моделей</small></div></div>
      <button class="new-button" :disabled="busy" @click="newRequest">＋ Новый запрос</button>
      <div class="history-heading"><h2>Сохранённые запуски</h2><button class="text-button" :disabled="busy || loading" @click="refresh">Обновить</button></div>
      <p v-if="!history.length" class="muted">Здесь появятся запросы и ответы. Они хранятся на сервере и доступны из любого браузера.</p>
      <nav class="history" aria-label="Сохранённые запуски">
        <button v-for="run in history" :key="run.id" :class="{ selected: current?.id === run.id }" :disabled="busy" @click="openRun(run.id)">
          <span class="history-prompt">{{ run.prompt }}</span><small>{{ labels[run.status] }} · {{ run.model }}</small><small>{{ date(run.createdAt) }}</small>
        </button>
      </nav>
      <div class="local-note">Вычисления выполняются в вашей Ollama. Каждый запуск независим; предыдущие ответы не добавляются в контекст.</div>
    </aside>

    <main>
      <header class="page-heading"><div><span class="eyebrow">День 26 · запуск локальной LLM</span><h1>Модель работает на вашем компьютере</h1><p>Задайте вопрос и проверьте ответ, время и расход токенов.</p></div><span class="runtime-status" :class="{ offline: !runtime?.connected }">{{ runtime?.connected ? `Ollama ${runtime.version} · подключена` : 'Ollama недоступна' }}</span></header>
      <div v-if="error" class="notice error" role="alert">{{ error }}</div>
      <div v-if="runtime?.error" class="notice error" role="alert">{{ runtime.error }}</div>
      <div v-if="runtime?.connected && !runtime.models.length" class="notice" role="status">Нет установленной разрешённой модели. Загрузите {{ runtime.configuredModels.join(', ') }} и нажмите «Обновить».</div>

      <section class="scenario-section"><h2>Три проверки разной сложности</h2><div class="scenarios">
        <button v-for="s in scenarios" :key="s.id" :class="{ active: scenarioId === s.id }" :disabled="busy" @click="choose(s)"><small>{{ s.difficulty }}</small><strong>{{ s.title }}</strong><span>Подставить запрос →</span></button>
      </div><p v-if="scenario" class="expectation"><strong>Что проверить:</strong> {{ scenario.expectation }}</p></section>

      <form class="composer" @submit.prevent="send">
        <label for="prompt">Ваш запрос</label><textarea id="prompt" v-model="prompt" maxlength="16000" :disabled="busy" placeholder="Напишите вопрос или выберите одну из трёх проверок…" @input="scenarioId = null" @keydown.ctrl.enter.prevent="send"></textarea>
        <div class="composer-bottom"><span class="muted">Ctrl+Enter · {{ prompt.length }} / 16000</span><button class="primary" :disabled="busy || invalid" type="submit">{{ busy ? 'Модель отвечает…' : 'Отправить локальной модели' }}</button></div>
        <p v-if="busy" role="status" class="muted">Первый запрос также загружает модель в память. Ответ появится после завершения генерации.</p>
      </form>

      <article v-if="current" ref="answerElement" class="result" aria-label="Результат запуска">
        <header><div><span class="eyebrow">{{ current.request.model }} · {{ date(current.createdAt) }}</span><h2>{{ labels[current.status] }}</h2></div><a class="text-button" :href="`/api/v1/runs/${encodeURIComponent(current.id)}/report`" download>Скачать MD</a></header>
        <h3>Исходный запрос</h3><p class="original-prompt">{{ current.request.prompt }}</p>
        <div v-if="current.status === 'TRUNCATED'" class="notice">Генерация остановлена по лимиту токенов. Этот ответ может быть неполным.</div>
        <div v-if="current.status === 'FAILED'" class="notice error" role="alert">{{ current.error }}</div>
        <p v-if="current.status === 'RUNNING'" class="notice">Запрос выполняется на сервере. Нажмите «Обновить», чтобы получить его состояние.</p>
        <template v-if="current.generation"><h3>Ответ модели</h3><div class="answer">{{ current.generation.answer }}</div>
          <dl class="metrics"><div><dt>Входные токены</dt><dd>{{ current.generation.inputTokens ?? '—' }}</dd></div><div><dt>Выходные токены</dt><dd>{{ current.generation.outputTokens ?? '—' }}</dd></div><div><dt>Время запроса</dt><dd>{{ seconds(current.elapsedMilliseconds) }}</dd></div><div><dt>Генерация</dt><dd>{{ current.generation.tokensPerSecond?.toFixed(1) ?? '—' }} ток/с</dd></div></dl>
          <details><summary>Параметры фактического запуска</summary><dl class="details"><dt>Температура</dt><dd>{{ current.request.temperature }}</dd><dt>Reasoning</dt><dd>{{ current.request.thinking ? 'Включён' : 'Выключен' }}</dd><dt>Лимит ответа приложения</dt><dd>{{ current.request.maxOutputTokens ?? 'Не задан · используется значение Ollama' }}</dd><dt>Причина завершения</dt><dd>{{ current.generation.doneReason }}</dd><dt>Загрузка модели</dt><dd>{{ seconds(current.generation.loadMilliseconds) }}</dd><dt>Время Ollama</dt><dd>{{ seconds(current.generation.totalMilliseconds) }}</dd></dl></details>
          <details v-if="current.generation.thinking"><summary>Reasoning модели</summary><div class="answer thinking">{{ current.generation.thinking }}</div></details>
        </template>
      </article>
    </main>

    <aside class="settings"><h2>Настройки запроса</h2><fieldset :disabled="busy"><label>Модель<select v-model="model"><option v-if="!runtime?.models.length" value="">Нет установленной модели</option><option v-for="m in runtime?.models" :key="m.name" :value="m.name">{{ m.name }}</option></select></label>
      <p v-for="m in runtime?.models.filter(m => m.name === model)" :key="m.name" class="muted">{{ m.parameterSize }} · {{ m.quantization }} · {{ (m.sizeBytes / 1e9).toFixed(1) }} ГБ на диске</p>
      <label>Температура<input v-model.number="temperature" type="number" min="0" max="2" step="0.1"></label>
      <label class="checkbox"><input v-model="thinking" type="checkbox"> Reasoning модели</label>
      <label>Максимум выходных токенов<input v-model="maxOutputText" type="number" min="1" max="32768" placeholder="По умолчанию Ollama"></label><p class="muted">Пустое поле не задаёт num_predict. Лимит, заданный здесь, включает токены reasoning.</p>
    </fieldset><div class="settings-note"><strong>Локальный запуск</strong><p>Облачные вызовы отключены в Ollama. После загрузки весов для генерации не требуется интернет или API-ключ.</p></div></aside>
  </div>
</template>
