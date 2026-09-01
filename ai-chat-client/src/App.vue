<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { getChatProfiles, sendChatMessage } from './api/chat.js'

const defaultResponseModes = [
  { id: 'free', name: 'Без ограничений', description: 'Обычный текстовый ответ' },
  { id: 'controlled', name: 'Краткий ответ', description: 'Лимит длины и stop sequence' },
  { id: 'json', name: 'Строгий JSON', description: 'Проверяемая JSON-схема' },
]

const draft = ref('')
const isSending = ref(false)
const profiles = ref([
  {
    id: 'general',
    name: 'Обычный чат',
    description: 'Универсальный помощник для любых вопросов',
    defaultResponseMode: 'free',
    responseModes: defaultResponseModes,
  },
])
const selectedProfileId = ref('general')
const selectedModes = reactive({ general: 'free' })
const conversations = reactive({})
const messageList = ref(null)

const selectedProfile = computed(() => (
  profiles.value.find((profile) => profile.id === selectedProfileId.value)
  || profiles.value[0]
))

const responseModes = computed(() => (
  selectedProfile.value?.responseModes?.length
    ? selectedProfile.value.responseModes
    : defaultResponseModes
))

const selectedResponseModeId = computed({
  get() {
    const profile = selectedProfile.value
    return selectedModes[selectedProfileId.value]
      || profile?.defaultResponseMode
      || responseModes.value[0]?.id
      || 'free'
  },
  set(value) {
    selectedModes[selectedProfileId.value] = value
  },
})

const selectedResponseMode = computed(() => (
  responseModes.value.find((mode) => mode.id === selectedResponseModeId.value)
  || responseModes.value[0]
))

const conversationKey = computed(() => (
  `${selectedProfileId.value}:${selectedResponseModeId.value}`
))

const messages = computed(() => conversations[conversationKey.value] || [])

function initialMessage(profileId, responseModeId) {
  const profile = profiles.value.find((item) => item.id === profileId)
  const mode = profile?.responseModes?.find((item) => item.id === responseModeId)
  return {
    id: crypto.randomUUID(),
    role: 'assistant',
    text: profile?.description
      ? `Профиль «${profile.name}», режим «${mode?.name || responseModeId}». ${profile.description}.`
      : 'Чат готов. Напишите сообщение — оно безопасно пройдёт через ваш backend.',
    source: 'system',
  }
}

function ensureConversation(profileId, responseModeId) {
  const key = `${profileId}:${responseModeId}`
  if (!conversations[key]) {
    conversations[key] = [initialMessage(profileId, responseModeId)]
  }
  return key
}

watch([selectedProfileId, selectedResponseModeId], ([profileId, responseModeId]) => {
  ensureConversation(profileId, responseModeId)
  scrollToLatest()
}, { immediate: true })

onMounted(async () => {
  try {
    const loadedProfiles = await getChatProfiles()
    if (Array.isArray(loadedProfiles) && loadedProfiles.length > 0) {
      profiles.value = loadedProfiles
      for (const profile of loadedProfiles) {
        selectedModes[profile.id] = profile.defaultResponseMode
          || profile.responseModes?.[0]?.id
          || 'free'
      }
      if (!loadedProfiles.some((profile) => profile.id === selectedProfileId.value)) {
        selectedProfileId.value = loadedProfiles[0].id
      }
      ensureConversation(selectedProfileId.value, selectedResponseModeId.value)
    }
  } catch {
    // Локальный профиль оставляет интерфейс рабочим, а ошибку API покажет отправка сообщения.
  }
})

function formatTime(date = new Date()) {
  return new Intl.DateTimeFormat('ru-RU', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function isRecipeReply(value) {
  return Boolean(
    value
    && typeof value.dishName === 'string'
    && Array.isArray(value.requiredIngredients)
    && typeof value.cookingTime === 'string',
  )
}

function formatStructuredReply(value) {
  return JSON.stringify(value, null, 2)
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

  const profileId = selectedProfileId.value
  const responseMode = selectedResponseModeId.value
  const key = ensureConversation(profileId, responseMode)
  const history = conversations[key]
    .filter((message) => (
      (message.role === 'user' || message.role === 'assistant')
      && message.source !== 'system'
      && !message.historyExcluded
    ))
    .map((message) => ({
      role: message.role,
      content: message.historyContent || message.text,
    }))

  const userMessage = {
    id: crypto.randomUUID(),
    role: 'user',
    text,
    time: formatTime(),
  }
  conversations[key].push(userMessage)
  draft.value = ''
  isSending.value = true
  await scrollToLatest()

  try {
    const response = await sendChatMessage({
      message: text,
      profileId,
      responseMode,
      history,
    })
    conversations[key].push({
      id: crypto.randomUUID(),
      role: 'assistant',
      text: response.reply,
      structuredReply: response.structuredReply || null,
      historyContent: response.structuredReply
        ? JSON.stringify(response.structuredReply)
        : response.reply,
      responseMode: response.responseMode,
      source: response.source,
      time: formatTime(),
    })
  } catch (error) {
    userMessage.historyExcluded = true
    conversations[key].push({
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
        <div class="brand-mark" aria-hidden="true"><span></span></div>
        <div class="brand-copy">
          <p class="eyebrow">PRIVATE AI GATEWAY</p>
          <h1>Orbita</h1>
        </div>
        <div class="chat-controls">
          <label class="profile-picker">
            <span>Профиль</span>
            <select
              v-model="selectedProfileId"
              :disabled="isSending"
              aria-label="Профиль чата"
            >
              <option v-for="profile in profiles" :key="profile.id" :value="profile.id">
                {{ profile.name }}
              </option>
            </select>
          </label>
          <label class="profile-picker">
            <span>Контроль ответа</span>
            <select
              v-model="selectedResponseModeId"
              :disabled="isSending"
              aria-label="Режим ответа"
            >
              <option v-for="mode in responseModes" :key="mode.id" :value="mode.id">
                {{ mode.name }}
              </option>
            </select>
          </label>
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
              <div v-if="isRecipeReply(message.structuredReply)" class="recipe-reply">
                <p><strong>Название блюда:</strong> {{ message.structuredReply.dishName }}</p>
                <div>
                  <strong>Требуемые ингредиенты:</strong>
                  <ul>
                    <li
                      v-for="ingredient in message.structuredReply.requiredIngredients"
                      :key="ingredient"
                    >
                      {{ ingredient }}
                    </li>
                  </ul>
                </div>
                <p><strong>Время готовки:</strong> {{ message.structuredReply.cookingTime }}</p>
              </div>
              <pre v-else-if="message.structuredReply" class="json-reply">{{ formatStructuredReply(message.structuredReply) }}</pre>
              <p v-else>{{ message.text }}</p>
            </div>
            <div class="message-meta">
              <span v-if="message.source === 'fallback'" class="source-badge">Fallback</span>
              <span v-else-if="message.source === 'llm'" class="source-badge source-badge--live">LLM</span>
              <span v-if="message.responseMode" class="mode-badge">{{ message.responseMode }}</span>
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
        <span>{{ selectedResponseMode?.description }}</span>
        <span>Enter — отправить</span>
        <span>Shift + Enter — новая строка</span>
      </footer>
    </section>
  </main>
</template>
