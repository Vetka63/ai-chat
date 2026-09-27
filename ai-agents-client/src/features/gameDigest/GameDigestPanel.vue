<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { getDigestReports, getDigestStatus, startDigest, stopDigest } from './api'

const props = defineProps({ conversationId: String })
const watchState = ref(null)
const reports = ref([])
const busy = ref(false)
const error = ref('')
let timer
let generation = 0

async function refresh() {
  const id = props.conversationId
  if (!id) return
  const current = generation
  try {
    const [status, history] = await Promise.all([getDigestStatus(id), getDigestReports(id)])
    if (current !== generation) return
    watchState.value = status.watch
    reports.value = history.reports || []
    error.value = ''
  } catch (cause) {
    if (current === generation) error.value = cause.message
  }
}

watch(() => props.conversationId, id => {
  generation += 1
  clearInterval(timer)
  watchState.value = null
  reports.value = []
  error.value = ''
  if (id) {
    refresh()
    timer = setInterval(() => { if (!busy.value) refresh() }, 10000)
  }
}, { immediate: true })
onBeforeUnmount(() => { generation += 1; clearInterval(timer) })

async function start() {
  const id = props.conversationId
  if (!id || busy.value) return
  busy.value = true
  try {
    await startDigest(id, 300)
    await refresh()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

async function stop() {
  const id = props.conversationId
  if (!id || busy.value) return
  busy.value = true
  try {
    await stopDigest(id)
    await refresh()
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

const date = value => value ? new Date(value).toLocaleString('ru-RU') : '—'
</script>

<template>
  <section class="digest-panel">
    <div class="digest-inner">
      <div class="digest-heading">
        <span class="digest-symbol" aria-hidden="true">✦</span>
        <div><p class="digest-kicker">ДЕНЬ 18 · MCP И ФОНОВЫЕ ЗАДАЧИ</p><h1>Игровые сводки</h1>
          <p>Java mock-сервис добавляет игру каждые 30 секунд. Раз в 5 минут планировщик запускает агента: он получает через MCP до 10 новейших игр и сохраняет сводку.</p></div>
      </div>
      <div v-if="!conversationId" class="digest-card">Создайте чат «Игровые сводки», чтобы настроить расписание.</div>
      <template v-else>
        <form class="digest-card digest-controls" @submit.prevent="start">
          <div><strong>{{ watchState?.status === 'active' ? 'Сбор включён' : 'Сбор остановлен' }}</strong>
            <p v-if="watchState?.status === 'active'">Следующий запуск: {{ date(watchState.next_run_at) }}. Отчёты обновляются автоматически каждые 10 секунд.</p>
            <p v-else>Включите сводку. Первый сбор произойдёт через 5 минут; каталог продолжит пополняться независимо от чата.</p></div>
          <div class="digest-actions">
            <button class="digest-primary" type="submit" :disabled="busy">{{ watchState?.status === 'active' ? 'Перезапустить расписание' : 'Запустить каждые 5 минут' }}</button>
            <button v-if="watchState?.status === 'active'" type="button" :disabled="busy" @click="stop">Остановить</button></div>
        </form>
        <p v-if="error" class="digest-error" role="alert">{{ error }} <button @click="refresh">Повторить</button></p>
        <div class="digest-list" aria-live="polite">
          <h2>Последние сводки <span>{{ reports.length }}/3</span></h2>
          <p v-if="!reports.length" class="digest-empty">Пока нет сводок. После запуска worker здесь появится первый результат.</p>
          <article v-for="report in reports" :key="report.id" class="digest-card digest-report">
            <div class="digest-meta"><span>СВОДКА · {{ date(report.created_at) }}</span><span>Java SQL-каталог</span></div>
            <p>{{ report.text }}</p>
            <small>Новых игр в сводке: {{ report.stats?.new_games }} / 10 · Запусков: {{ report.stats?.runs }}</small>
          </article>
        </div>
      </template>
    </div>
  </section>
</template>

<style scoped>
.digest-panel { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: clamp(20px, 4vw, 54px); color: var(--text); }
.digest-inner { max-width: 850px; margin: 0 auto; }
.digest-heading { display: flex; gap: 20px; align-items: flex-start; margin: 14px 0 30px; }
.digest-symbol { display: grid; place-items: center; flex: 0 0 62px; height: 62px; border-radius: 20px; background: var(--accent-soft); color: var(--accent); font-size: 30px; }
.digest-kicker { font-size: 11px; font-weight: 800; letter-spacing: .16em; color: var(--accent); }
h1 { margin: 5px 0 8px; font-size: clamp(25px, 4vw, 38px); }
h2 { display: flex; justify-content: space-between; margin: 30px 0 14px; font-size: 17px; }
h2 span, .digest-heading p:not(.digest-kicker), .digest-controls p, .digest-empty, small { color: var(--muted); }
.digest-card { background: var(--panel); border: 1px solid var(--line); border-radius: 20px; padding: 22px; box-shadow: var(--shadow); }
.digest-controls { display: flex; align-items: center; justify-content: space-between; gap: 20px; flex-wrap: wrap; }
.digest-controls p { margin: 7px 0 0; font-size: 13px; }
.digest-actions { display: flex; align-items: end; flex-wrap: wrap; gap: 9px; }
.digest-actions label { display: grid; gap: 4px; font-size: 12px; color: var(--muted); }
.digest-actions input { width: 95px; height: 39px; padding: 7px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); color: var(--text); font: inherit; }
.digest-actions button, .digest-error button { min-height: 39px; padding: 7px 13px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); cursor: pointer; }
.digest-actions .digest-primary { background: var(--accent); color: white; border-color: var(--accent); }
.digest-actions button:disabled { opacity: .6; cursor: not-allowed; }
.digest-report { margin: 12px 0; }
.digest-report p { line-height: 1.65; margin: 18px 0; }
.digest-meta { display: flex; justify-content: space-between; gap: 10px; color: var(--accent); font-size: 11px; font-weight: 800; letter-spacing: .07em; }
.digest-error { color: var(--danger); }
.digest-empty { padding: 20px; border: 1px dashed var(--line); border-radius: 14px; }
@media (max-width: 650px) { .digest-heading { gap: 12px; } .digest-symbol { flex-basis: 46px; height: 46px; } }
</style>
