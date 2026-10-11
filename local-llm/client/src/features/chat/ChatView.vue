<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { api, type RuntimeInfo, type PrivateSettings } from '../../api'
import { chatApi, type Chat, type ChatSummary, type SendMessage } from './api'
import './chat.css'

const emit = defineEmits<{ busy: [value: boolean] }>()
const props = defineProps<{ serviceSettings?: PrivateSettings | null }>()
const day = computed(() => props.serviceSettings?.enabled ? 30 : 27)
const runtime = ref<RuntimeInfo | null>(null), chats = ref<ChatSummary[]>([]), current = ref<Chat | null>(null)
const creating = ref(false), model = ref(''), title = ref(''), draft = ref(''), error = ref('')
const busy = ref(false), loading = ref(false), settingsOpen = ref(window.matchMedia?.('(min-width:1101px)').matches ?? true), deleteCandidate = ref<ChatSummary | null>(null)
const temperature = ref(.7), thinking = ref(false), limit = ref<string | number>('')
const optimistic = ref<SendMessage | null>(null), uncertain = ref<SendMessage | null>(null)
const bottom = ref<HTMLElement | null>(null), input = ref<HTMLTextAreaElement | null>(null)
const maxOutput = computed(() => String(limit.value).trim() === '' ? null : Number(limit.value))
const validSettings = computed(() => Number.isFinite(temperature.value) && temperature.value >= 0 && temperature.value <= 2 &&
  (maxOutput.value == null || (Number.isInteger(maxOutput.value) && maxOutput.value >= 1 && maxOutput.value <= 32768)))
const ready = computed(() => runtime.value?.connected && runtime.value.models.some(m => m.name === (current.value?.model ?? model.value)))
const hasPending = computed(() => current.value?.messages.some(m => m.status === 'RUNNING'))
const canSend = computed(() => current.value && !busy.value && !loading.value && !uncertain.value && !hasPending.value && ready.value && validSettings.value && draft.value.trim() && draft.value.length <= 16000)
const lastUser = computed(() => current.value?.messages.filter(m => m.role === 'USER').at(-1))
const time = (value: string) => new Date(value).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
const seconds = (value: number | null) => value == null ? '—' : `${(value / 1000).toFixed(2)} с`
const turnParameters = (requestId: string) => current.value?.messages.find(m => m.role === 'USER' && m.requestId === requestId)?.parameters

function setBusy(value: boolean) { busy.value = value; emit('busy', value) }
async function scrollDown() { await nextTick(); bottom.value?.scrollIntoView({ behavior: 'smooth', block: 'end' }) }
function restoreSettings(chat: Chat) {
  const params = chat.messages.filter(m => m.role === 'USER').at(-1)?.parameters
  temperature.value = params?.temperature ?? .7; thinking.value = params?.thinking ?? false; limit.value = params?.maxOutputTokens ?? ''
}
async function refresh() {
  loading.value = true; error.value = ''
  try {
    const [info, list] = await Promise.all([api.runtime(), chatApi.list()])
    runtime.value = info; chats.value = list
    if (!info.models.some(m => m.name === model.value)) model.value = info.models[0]?.name ?? ''
    if (current.value) current.value = await chatApi.get(current.value.id)
    if (uncertain.value && current.value?.messages.some(m => m.requestId === uncertain.value!.requestId)) uncertain.value = null
    await scrollDown()
  } catch (e) { error.value = String(e) } finally { loading.value = false }
}
async function openChat(id: string) {
  if (busy.value || loading.value) return
  loading.value = true; error.value = ''
  try {
    const selected = await chatApi.get(id)
    current.value = selected; creating.value = false; draft.value = ''; uncertain.value = null; restoreSettings(selected)
    window.location.hash = `chat/${encodeURIComponent(id)}`
    await scrollDown()
  } catch (e) { error.value = String(e) } finally { loading.value = false }
}
function newChat() {
  if (busy.value || loading.value) return
  current.value = null; creating.value = true; title.value = ''; draft.value = ''; uncertain.value = null; error.value = ''
  window.location.hash = 'chat'
  temperature.value = .7; thinking.value = false; limit.value = ''
}
async function create() {
  if (!ready.value || busy.value || loading.value || title.value.length > 100) return
  loading.value = true; error.value = ''
  try {
    current.value = await chatApi.create(model.value, title.value.trim()); creating.value = false; chats.value = await chatApi.list()
    window.location.hash = `chat/${encodeURIComponent(current.value.id)}`
    await nextTick(); input.value?.focus()
  } catch (e) { error.value = String(e) } finally { loading.value = false }
}
async function removeChat() {
  const removing = deleteCandidate.value
  if (!removing || busy.value) return
  loading.value = true; error.value = ''
  try {
    await chatApi.delete(removing.id); deleteCandidate.value = null; chats.value = await chatApi.list()
    if (current.value?.id === removing.id) {
      current.value = null; uncertain.value = null; draft.value = ''
      if (chats.value[0]) { current.value = await chatApi.get(chats.value[0].id); restoreSettings(current.value) }
      else creating.value = true
      window.location.hash = current.value ? `chat/${encodeURIComponent(current.value.id)}` : 'chat'
    }
  } catch (e) { error.value = String(e) } finally { loading.value = false }
}
async function submitRequest(body: SendMessage, recovery = false) {
  if (!current.value || busy.value) return
  const id = current.value.id
  setBusy(true); error.value = ''; optimistic.value = recovery ? null : body; draft.value = ''; await scrollDown()
  try {
    current.value = await chatApi.send(id, body); uncertain.value = null; optimistic.value = null
  } catch (e) {
    error.value = `${String(e)}. Автоматический повтор не отправлен.`
    // Определённый отказ ДО принятия сообщения: восстановить текст, не выдавать его за потерянный ответ.
    const status = e instanceof Error && 'status' in e ? Number(e.status) : null
    const rejected = status != null && [400, 401, 403, 404, 413, 422, 429, 503].includes(status)
    try {
      current.value = await chatApi.get(id)
      if (!current.value.messages.some(m => m.requestId === body.requestId)) { uncertain.value = rejected ? null : body; draft.value = body.content }
      else uncertain.value = null
    } catch { uncertain.value = rejected ? null : body; draft.value = body.content }
  } finally {
    optimistic.value = null; setBusy(false)
    try { chats.value = await chatApi.list() } catch { /* исходная ошибка остаётся видимой */ }
    await scrollDown(); input.value?.focus()
  }
}
async function send() {
  if (!canSend.value) return
  // HTTP через VPN на iPhone не является Secure Context: randomUUID может отсутствовать.
  const bytes = crypto.getRandomValues(new Uint8Array(16)); bytes[6] = (bytes[6]! & 15) | 64; bytes[8] = (bytes[8]! & 63) | 128
  const hex = [...bytes].map(b => b.toString(16).padStart(2, '0')).join('')
  const requestId = `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`
  await submitRequest({ requestId, content: draft.value.trim(), temperature: temperature.value, thinking: thinking.value, maxOutputTokens: maxOutput.value })
}
async function resendUncertain() { if (uncertain.value) await submitRequest(uncertain.value, true) }
async function retry() {
  if (!current.value || busy.value || loading.value || lastUser.value?.status !== 'FAILED') return
  const id = current.value.id, requestId = lastUser.value.requestId
  setBusy(true); error.value = ''
  try { current.value = await chatApi.retry(id, requestId) }
  catch (e) { error.value = String(e); try { current.value = await chatApi.get(id) } catch { /* сохраняем последний известный чат */ } }
  finally { setBusy(false); await scrollDown() }
}
onMounted(async () => {
  await refresh()
  const selectedId = window.location.hash.startsWith('#chat/') ? window.location.hash.slice(6) : null
  if (selectedId && chats.value.some(c => c.id === selectedId)) await openChat(selectedId)
  else if (chats.value[0]) await openChat(chats.value[0].id)
  else creating.value = true
})
</script>

<template>
  <div class="chat-page" :class="{ 'settings-closed': !settingsOpen }">
    <aside class="chat-sidebar">
      <div class="chat-brand"><span class="brand-mark">L</span><div><strong>Локальный чат</strong><small>День {{ day }} · Qwen и Ollama</small></div></div>
      <button class="new-button" :disabled="busy || loading" @click="newChat">＋ Новый чат</button>
      <div class="history-heading"><h2>Ваши диалоги</h2><button class="text-button" :disabled="busy || loading" @click="refresh">Обновить</button></div>
      <div v-if="deleteCandidate" class="chat-delete-confirm" role="alertdialog" aria-label="Удаление чата">
        <p>Удалить «{{ deleteCandidate.title }}» вместе со всей перепиской?</p>
        <button :disabled="loading" @click="removeChat">Удалить навсегда</button><button :disabled="loading" @click="deleteCandidate = null">Отмена</button>
      </div>
      <nav class="chat-list" aria-label="Список чатов">
        <div v-for="chat in chats" :key="chat.id" class="chat-row" :class="{ selected: current?.id === chat.id && !creating }">
          <button class="chat-select" :disabled="busy || loading" @click="openChat(chat.id)"><strong>{{ chat.title }}</strong><small>{{ chat.model }} · {{ chat.messageCount }} сообщ.</small></button>
          <button class="chat-delete" :aria-label="`Удалить чат ${chat.title}`" :disabled="busy || loading" @click="deleteCandidate = chat">×</button>
        </div>
        <p v-if="!chats.length && !loading" class="muted">Создайте первый чат. Переписка будет храниться в SQLite на сервере.</p>
      </nav>
      <p class="chat-storage-note">Чаты изолированы. История остаётся после перезапуска и доступна из любого браузера.</p>
    </aside>

    <main class="chat-main">
      <header class="chat-header"><div><span class="eyebrow">День {{ day }} · {{ serviceSettings?.enabled ? 'приватный AI-сервис' : 'приложение с локальной LLM' }}</span><h1>{{ current?.title ?? 'Новый диалог' }}</h1><small>{{ current?.model ?? 'Выберите модель при создании чата' }}</small></div><button class="text-button" :aria-expanded="settingsOpen" @click="settingsOpen = !settingsOpen">{{ settingsOpen ? 'Скрыть настройки' : 'Настройки' }}</button></header>
      <div v-if="error" class="notice error chat-notice" role="alert">{{ error }}</div>
      <div v-if="runtime?.error" class="notice error chat-notice" role="alert">{{ runtime.error }}</div>
      <div v-if="uncertain" class="notice chat-notice" role="status">Не удалось подтвердить сохранение сообщения. Обновите чат или повторите с тем же ID — дубликат не появится.<button :disabled="busy || loading" @click="resendUncertain">Повторить безопасно</button></div>

      <section v-if="creating || !current" class="chat-create">
        <span class="chat-welcome-mark">L</span><h2>О чём поговорим?</h2><p>Ответит модель на вашем компьютере. Никаких облачных API.</p>
        <form @submit.prevent="create"><label>Название чата<input v-model="title" maxlength="100" placeholder="Новый чат" :disabled="loading"></label><label>Модель<select v-model="model" :disabled="loading"><option v-if="!runtime?.models.length" value="">Нет установленной модели</option><option v-for="m in runtime?.models" :key="m.name" :value="m.name">{{ m.name }}</option></select></label><button class="primary" :disabled="!ready || loading" type="submit">Создать чат</button></form>
        <p v-if="!ready" class="muted">Запустите Ollama, загрузите разрешённую модель и нажмите «Обновить».</p>
      </section>

      <template v-else>
        <section class="chat-messages" aria-label="Переписка" aria-live="polite">
          <div v-if="!current.messages.length && !optimistic" class="chat-empty"><h2>Начните диалог</h2><p>Можно рассказать о себе или задаче. Следующие ответы будут учитывать сообщения этого чата.</p></div>
          <article v-for="message in current.messages" :key="message.id" class="chat-message" :class="message.role === 'USER' ? 'from-user' : 'from-assistant'">
            <header><strong>{{ message.role === 'USER' ? 'Вы' : current.model }}</strong><time>{{ time(message.createdAt) }}</time></header>
            <div class="chat-message-content">{{ message.content }}</div>
            <div v-if="message.status === 'FAILED'" class="notice error" role="alert">{{ message.error }}<p>Это сообщение не добавляется в контекст, пока ответ не получен.</p><button v-if="message.id === lastUser?.id" :disabled="busy || loading || !ready" @click="retry">Повторить ответ</button></div>
            <p v-if="message.status === 'RUNNING'" class="muted">Ответ ещё формируется. Если страница была обновлена, нажмите «Обновить».</p>
            <p v-if="message.role === 'ASSISTANT' && message.status === 'TRUNCATED'" class="notice">Ответ обрезан лимитом или окном контекста и может быть неполным.</p>
            <details v-if="message.generation" class="chat-turn-details"><summary>Токены и параметры ответа</summary><dl><dt>Входные / выходные токены</dt><dd>{{ message.generation.inputTokens ?? '—' }} / {{ message.generation.outputTokens ?? '—' }}</dd><dt>Время</dt><dd>{{ seconds(message.elapsedMilliseconds) }}</dd><dt>Сообщений в контексте</dt><dd>{{ message.contextMessageCount }} + системная инструкция</dd><dt>Причина завершения</dt><dd>{{ message.generation.doneReason }}</dd><dt>Температура</dt><dd>{{ turnParameters(message.requestId)?.temperature }}</dd><dt>Reasoning</dt><dd>{{ turnParameters(message.requestId)?.thinking ? 'Включён' : 'Выключен' }}</dd><dt>Лимит выхода</dt><dd>{{ turnParameters(message.requestId)?.maxOutputTokens ?? 'Не задан' }}</dd></dl><div v-if="message.generation.thinking" class="chat-message-content">{{ message.generation.thinking }}</div></details>
          </article>
          <article v-if="optimistic" class="chat-message from-user"><header><strong>Вы</strong><small>Отправляется…</small></header><div class="chat-message-content">{{ optimistic.content }}</div></article>
          <p v-if="busy" class="chat-working" role="status">Ollama готовит ответ… При первом запросе требуется загрузка модели.</p>
          <div ref="bottom" class="chat-bottom"></div>
        </section>
        <form class="chat-composer" @submit.prevent="send"><label class="sr-only" for="chat-input">Сообщение</label><textarea id="chat-input" ref="input" v-model="draft" maxlength="16000" placeholder="Напишите сообщение локальной модели…" :disabled="busy || loading || hasPending || !!uncertain" @keydown.ctrl.enter.prevent="send"></textarea><div><small>Ctrl+Enter · история передаётся автоматически</small><button class="primary" type="submit" :disabled="!canSend">{{ busy ? 'Модель отвечает…' : 'Отправить' }}</button></div></form>
      </template>
    </main>

    <aside v-if="settingsOpen" class="chat-settings">
      <div class="chat-settings-heading"><h2>Настройки диалога</h2><button class="text-button" aria-label="Закрыть настройки" @click="settingsOpen = false">×</button></div><span class="runtime-status" :class="{ offline: !runtime?.connected }">{{ runtime?.connected ? `Ollama ${runtime.version} · подключена` : 'Ollama недоступна' }}</span>
      <p class="chat-model-info">{{ current?.model ?? model }}<small>Модель фиксируется при создании чата.</small></p>
      <fieldset :disabled="busy || loading || !!uncertain"><label>Температура<input v-model.number="temperature" type="number" min="0" max="2" step="0.1"></label><label class="checkbox"><input v-model="thinking" type="checkbox">Reasoning модели</label><label>Максимум выходных токенов<input v-model="limit" type="number" min="1" max="32768" placeholder="По умолчанию Ollama"></label></fieldset>
      <p class="muted">Параметры применяются к следующему сообщению. Пустой лимит не ограничивает ответ со стороны приложения.</p>
      <div class="chat-context-info"><h3>Как работает память</h3><p>В запрос: системная инструкция + предыдущие реплики + новое сообщение.</p><p>Окно настроено на {{ serviceSettings?.contextTokens ?? 8192 }} токена. Сжатия нет. При переполнении история не обрезается молча: появится ошибка.</p><p>Во время генерации при заполнении окна ответ может остановиться как неполный.</p><template v-if="serviceSettings?.enabled"><h3>Защита сервиса</h3><p>{{ serviceSettings.generationsPerMinute }} генераций/мин на владельца, включая явные повторы. Только один одновременный запрос; скрытой очереди нет.</p><p>Передаваемый текст истории: максимум {{ serviceSettings.contextBytes }} байт UTF-8. Это ограничение размера, не оценка токенов. Фактические токены видны у ответа.</p><p>Сессия действует {{ serviceSettings.sessionSeconds / 3600 }} ч. После перезапуска потребуется войти снова.</p></template></div>
      <details v-if="current" class="chat-system"><summary>Системная инструкция</summary><p>{{ current.systemPrompt }}</p></details>
    </aside>
  </div>
</template>
