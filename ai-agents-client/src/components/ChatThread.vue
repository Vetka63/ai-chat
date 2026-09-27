<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import SummaryEvent from '../features/context/SummaryEvent.vue'
import { requestNumber } from '../features/context/compressionDisplay'
import RunMetrics from '../features/tokens/RunMetrics.vue'

const props = defineProps({ messages: Array, runs: Array, toolEvents: Array, agentName: String, sending: Boolean, memoryLayers: Boolean, mcpTools: Boolean, mcpPipeline: Boolean, mcpOrchestration: Boolean })
const thread = ref(null)
const expanded = ref(new Set())
const metrics = computed(() => new Map((props.runs || []).filter((r) => !r.purpose || r.purpose === 'dialogue').map((r) => [r.assistant_index ?? r.user_index, r])))
const summariesAt = (index) => (props.runs || []).filter((r) => r.purpose === 'summary' && r.user_index === index)
const toolsAt = (index) => (props.toolEvents || []).filter((event) => event.user_index === index)
const reportStep = { search_games: '1. Поиск', summarize_games: '2. Обработка', save_report: '3. Сохранение' }
function downloadReport(event) {
  const content = event?.result?.report_markdown
  if (typeof content !== 'string') return
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = event.result.file_name || 'game-report.md'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
watch(() => props.messages, () => { expanded.value = new Set() })

async function scrollToBottom(smooth = true) {
  await nextTick()
  if (!thread.value) return

  const options = { top: thread.value.scrollHeight, behavior: smooth ? 'smooth' : 'auto' }
  if (typeof thread.value.scrollTo === 'function') thread.value.scrollTo(options)
  else thread.value.scrollTop = options.top
}

watch(
  () => [props.messages, props.messages?.length, props.sending],
  (_, previous) => scrollToBottom(Boolean(previous)),
  { immediate: true },
)
</script>

<template>
  <section ref="thread" class="thread" aria-live="polite">
    <div v-if="!messages.length" class="welcome">
      <span class="welcome-orbit"><i>✦</i></span>
      <p class="eyebrow">{{ mcpTools ? 'АГЕНТ С MCP-ИНСТРУМЕНТАМИ' : 'АГЕНТ С ПОСТОЯННЫМ КОНТЕКСТОМ' }}</p>
      <h1>{{ mcpOrchestration ? 'Что исследуем сегодня?' : mcpPipeline ? 'Какой отчёт составить?' : mcpTools ? 'Какую игру найти?' : memoryLayers ? 'Какую задачу разберём?' : 'О чём поговорим?' }}</h1>
      <p v-if="memoryLayers">Наставник видит условие задачи, сохранённые результаты и память. План, решение и проверку можно обсудить и подтвердить прямо в чате.</p>
      <p v-else-if="mcpOrchestration">Соберите подборки игр, создайте отдельные сводки, затем попросите отчёт по выбранным сводкам. Агент сам маршрутизирует вызовы между Java, Python и Go MCP.</p>
      <p v-else-if="mcpPipeline">Попросите составить и сохранить отчёт по теме игр. Агент последовательно вызовет три инструмента одного MCP-сервера: поиск, обработку и сохранение.</p>
      <p v-else-if="mcpTools">Спросите об играх в учебном каталоге. Агент сам выберет MCP-инструмент, покажет его вызов и ответит по найденным данным.</p>
      <p v-else>{{ agentName || 'Агент' }} сохранит сообщения в SQLite и вспомнит их даже после перезапуска приложения.</p>
      <div class="suggestions">
        <template v-if="mcpOrchestration"><span>Найди и собери две игры про космос</span><span>Сделай сводку по подборке «Космос»</span><span>Сохрани отчёт по двум сводкам</span></template>
        <template v-else-if="mcpPipeline"><span>Создай и сохрани отчёт об играх про космос</span><span>Составь отчёт про сад</span></template>
        <template v-else-if="mcpTools"><span>Игра про космос</span><span>Найди «Лунный архив»</span><span>Есть ли игры про сад?</span></template>
        <template v-else><span>Объясни простую тему</span><span>Предложи три идеи</span><span>Помоги составить план</span></template>
      </div>
    </div>

    <div v-else class="message-list">
      <template v-for="(message, index) in messages" :key="index">
      <article class="message" :class="message.role">
        <div class="message-avatar">{{ message.role === 'user' ? 'В' : message.role === 'error' ? '!' : '✦' }}</div>
        <div class="message-body">
          <strong>{{ message.role === 'user' ? `Вы · запрос №${requestNumber(messages, index)}` : message.role === 'error' ? 'Ошибка' : agentName }}</strong>
          <small class="message-index">Сообщение истории №{{ index + 1 }}</small>
          <small v-if="mcpTools && message.role === 'user' && Array.isArray(message.mcp_server_ids)" class="message-mcp">{{ message.mcp_server_ids.length ? `MCP: ${message.mcp_server_ids.join(', ')}` : 'MCP отключены' }}</small>
          <p>{{ expanded.has(index) || message.content.length <= 5000 ? message.content : message.content.slice(0, 1200) + '…' }}</p>
          <button v-if="message.content.length > 5000" class="report-button" @click="expanded.has(index) ? expanded.delete(index) : expanded.add(index)">{{ expanded.has(index) ? 'Свернуть' : `Показать весь текст (${message.content.length.toLocaleString('ru-RU')} символов)` }}</button>
          <small v-if="message.model">{{ message.model }}<template v-if="message.source === 'demo'"> · demo</template></small>
          <RunMetrics :run="metrics.get(index)" />

        </div>
      </article>
      <article v-for="event in toolsAt(index)" :key="event.id" class="tool-event" :class="event.status" aria-label="Вызов MCP-инструмента">
        <strong>↗ MCP · {{ event.server_id }} / {{ event.tool_name }}</strong>
        <span v-if="event.server_id === 'game-reports'">{{ reportStep[event.tool_name] }}</span>
        <span>{{ event.status === 'success' ? 'Результат получен' : 'Ошибка инструмента' }}</span>
        <template v-if="event.tool_name === 'save_report' && event.status === 'success'">
          <span>Отчёт {{ event.result.file_name }}<template v-if="event.result.game_count !== undefined"> · игр: {{ event.result.game_count }}</template><template v-else-if="event.result.summary_count !== undefined"> · сводок: {{ event.result.summary_count }}</template></span>
          <button type="button" class="report-button" @click="downloadReport(event)">Скачать Markdown-отчёт</button>
        </template>
        <details><summary>Показать вход и результат</summary><pre>Вход: {{ JSON.stringify(event.arguments, null, 2) }}
Результат: {{ JSON.stringify(event.result, null, 2) }}</pre></details>
      </article>
      <SummaryEvent v-for="run in summariesAt(index)" :key="run.id" :run="run" :runs="runs" :messages="messages" />
      </template>
      <article v-if="sending" class="message assistant pending">
        <div class="message-avatar">✦</div><div class="message-body"><strong>{{ agentName }}</strong><span class="typing"><i></i><i></i><i></i></span></div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.tool-event { margin: 4px auto 18px; width: min(790px, 90%); padding: 12px 16px; border: 1px solid var(--line); border-left: 3px solid #59a886; border-radius: 12px; background: var(--panel); color: var(--text); display: grid; gap: 4px; }
.tool-event.error { border-left-color: #c66b64; }
.tool-event span { font-size: .85rem; color: var(--muted); }
.tool-event summary { cursor: pointer; font-size: .85rem; }
.tool-event pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: .8rem; }
</style>
