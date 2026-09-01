<script setup>
import { nextTick, ref } from 'vue'
import { sendChatMessage } from './api/chat.js'

const draft = ref('')
const isSending = ref(false)
const messages = ref([
  {
    id: crypto.randomUUID(),
    role: 'assistant',
    text: 'Привет! Я готов помочь. Напишите сообщение — оно безопасно пройдёт через ваш backend.',
    source: 'system',
  },
])
const messageList = ref(null)

function formatTime(date = new Date()) {
  return new Intl.DateTimeFormat('ru-RU', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

async function scrollToLatest() {
  await nextTick()
  if (typeof messageList.value?.scrollTo === 'function') {
    messageList.value.scrollTo({ top: messageList.value.scrollHeight, behavior: 'smooth' })
  }
}

async function submitMessage() {
  const text = draft.value.trim()
  if (!text || isSending.value) return

  messages.value.push({
    id: crypto.randomUUID(),
    role: 'user',
    text,
    time: formatTime(),
  })
  draft.value = ''
  isSending.value = true
  await scrollToLatest()

  try {
    const response = await sendChatMessage(text)
    messages.value.push({
      id: crypto.randomUUID(),
      role: 'assistant',
      text: response.reply,
      source: response.source,
      time: formatTime(),
    })
  } catch (error) {
    messages.value.push({
      id: crypto.randomUUID(),
      role: 'error',
      text: error instanceof Error ? error.message : 'Произошла неизвестная ошибка',
      time: formatTime(),
    })
  } finally {
    isSending.value = false
    await scrollToLatest()
  }
}

function handleKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    submitMessage()
  }
}
</script>

<template>
  <main class="page-shell">
    <section class="chat-card" aria-label="AI-чат">
      <header class="chat-header">
        <div class="brand-mark" aria-hidden="true">
          <span></span>
        </div>
        <div class="brand-copy">
          <p class="eyebrow">PRIVATE AI GATEWAY</p>
          <h1>Orbita</h1>
        </div>
        <div class="status-pill">
          <span class="status-dot" aria-hidden="true"></span>
          Backend online
        </div>
      </header>

      <div ref="messageList" class="messages" aria-live="polite">
        <div class="date-separator"><span>Сегодня</span></div>

        <article
          v-for="message in messages"
          :key="message.id"
          class="message-row"
          :class="`message-row--${message.role}`"
        >
          <div v-if="message.role !== 'user'" class="avatar" aria-hidden="true">O</div>
          <div class="message-stack">
            <div class="bubble">
              <p>{{ message.text }}</p>
            </div>
            <div class="message-meta">
              <span v-if="message.source === 'fallback'" class="source-badge">Fallback</span>
              <span v-else-if="message.source === 'llm'" class="source-badge source-badge--live">LLM</span>
              <time v-if="message.time">{{ message.time }}</time>
            </div>
          </div>
        </article>

        <article v-if="isSending" class="message-row message-row--assistant">
          <div class="avatar" aria-hidden="true">O</div>
          <div class="bubble typing" aria-label="Ассистент печатает">
            <span></span><span></span><span></span>
          </div>
        </article>
      </div>

      <form class="composer" @submit.prevent="submitMessage">
        <label class="sr-only" for="message">Сообщение</label>
        <textarea
          id="message"
          v-model="draft"
          :disabled="isSending"
          maxlength="10000"
          rows="1"
          placeholder="Напишите сообщение…"
          @keydown="handleKeydown"
        ></textarea>
        <button type="submit" :disabled="!draft.trim() || isSending" aria-label="Отправить сообщение">
          <span aria-hidden="true">↑</span>
        </button>
      </form>

      <footer class="chat-footer">
        <span>Enter — отправить</span>
        <span>Shift + Enter — новая строка</span>
      </footer>
    </section>
  </main>
</template>
