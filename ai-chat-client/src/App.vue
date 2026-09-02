<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { getChatProfiles, sendChatMessage } from './api/chat.js'
import AppDropdown from './components/AppDropdown.vue'
import SeasonalEffects from './components/SeasonalEffects.vue'
import { findTheme, THEME_STORAGE_KEY, themeOptions } from './config/themes.js'

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
const backendState = ref('checking')
const storedTheme = typeof window !== 'undefined'
  ? window.localStorage.getItem(THEME_STORAGE_KEY)
  : null
const themeId = ref(storedTheme || 'light')

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

const backendStatusLabel = computed(() => ({
  checking: 'Подключаемся',
  online: 'Сервис на связи',
  offline: 'Сервис недоступен',
}[backendState.value]))

const selectedTheme = computed(() => (
  findTheme(themeId.value)
))

watch(themeId, (value) => {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(THEME_STORAGE_KEY, value)
  }
})

function initialMessage(profileId, responseModeId) {
  const profile = profiles.value.find((item) => item.id === profileId)
  const mode = profile?.responseModes?.find((item) => item.id === responseModeId)
  return {
    id: crypto.randomUUID(),
    role: 'assistant',
    text: profile?.description
      ? `Привет! Я Клевер. Сейчас я работаю в профиле «${profile.name}». ${profile.description}. Выбран формат «${mode?.name || responseModeId}».`
      : 'Привет! Я Клевер. Расскажите, с чем вам помочь.',
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
    backendState.value = 'online'
  } catch {
    backendState.value = 'offline'
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

function responseModeName(modeId) {
  return responseModes.value.find((mode) => mode.id === modeId)?.name || modeId
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
  <main class="page-shell" :data-theme="themeId">
    <SeasonalEffects :theme-id="themeId" />
    <section class="app-window">
      <aside class="sidebar" aria-label="Настройки чата">
        <div class="brand">
          <div class="brand-mark" aria-hidden="true">
            <img src="/assets/klever-mascot.png" alt="" />
          </div>
          <div class="brand-copy">
            <p class="eyebrow">Личный AI-помощник</p>
            <h1>Клевер</h1>
          </div>
        </div>

        <div class="sidebar-controls">
          <div class="control-card">
            <span class="control-label">Пространство</span>
            <AppDropdown
              v-model="selectedProfileId"
              :options="profiles"
              :disabled="isSending"
              icon="space"
              aria-label="Профиль чата"
            />
          </div>

          <div class="control-card">
            <span class="control-label">Формат ответа</span>
            <AppDropdown
              v-model="selectedResponseModeId"
              :options="responseModes"
              :disabled="isSending"
              icon="format"
              aria-label="Режим ответа"
            />
            <span class="control-hint">{{ selectedResponseMode?.description }}</span>
          </div>

          <div class="control-card control-card--theme">
            <span class="control-label">Оформление</span>
            <AppDropdown
              v-model="themeId"
              :options="themeOptions"
              icon="theme"
              aria-label="Тема оформления"
            />
            <span class="control-hint">{{ selectedTheme.description }}</span>
          </div>
        </div>

        <section
          v-if="selectedTheme.story"
          class="season-story"
          :class="'season-story--' + selectedTheme.season"
          aria-live="polite"
        >
          <div class="season-story__heading">
            <span class="season-story__symbol" aria-hidden="true">
              {{ selectedTheme.story.symbol }}
            </span>
            <span>{{ selectedTheme.story.eyebrow }}</span>
          </div>
          <strong>{{ selectedTheme.story.title }}</strong>
          <p>{{ selectedTheme.story.description }}</p>
          <div class="season-story__moments" :aria-label="'Настроения темы «' + selectedTheme.name + '»'">
            <span v-for="moment in selectedTheme.story.moments" :key="moment">{{ moment }}</span>
          </div>
        </section>

        <div class="privacy-note">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <rect x="5" y="10" width="14" height="10" rx="3" />
            <path d="M8.5 10V7.5a3.5 3.5 0 0 1 7 0V10" />
          </svg>
          <div>
            <strong>Приватное подключение</strong>
            <span>Запросы проходят через ваш backend</span>
          </div>
        </div>
      </aside>

      <section class="chat-workspace" aria-label="AI-чат">
        <header class="conversation-header">
          <div class="conversation-identity">
            <div class="profile-glyph" aria-hidden="true">
              <svg v-if="selectedProfileId === 'recipe'" viewBox="0 0 24 24">
                <path d="M6 11h12v2a6 6 0 0 1-12 0v-2Z" />
                <path d="M9 7c0-1 1-1.5 1-2.5M13 7c0-1 1-1.5 1-2.5M17 7c0-1 1-1.5 1-2.5" />
              </svg>
              <svg v-else viewBox="0 0 24 24">
                <path d="m12 3 1.4 4.6L18 9l-4.6 1.4L12 15l-1.4-4.6L6 9l4.6-1.4L12 3Z" />
                <path d="m18.5 15 .7 2.3 2.3.7-2.3.7-.7 2.3-.7-2.3-2.3-.7 2.3-.7.7-2.3Z" />
              </svg>
            </div>
            <div>
              <p class="conversation-kicker">Текущий помощник</p>
              <h2>{{ selectedProfile?.name }}</h2>
              <p>{{ selectedProfile?.description }}</p>
            </div>
          </div>

          <div class="status-pill" :class="`status-pill--${backendState}`">
            <span class="status-dot" aria-hidden="true"></span>
            {{ backendStatusLabel }}
          </div>
        </header>

        <div ref="messageList" class="messages" aria-live="polite">
          <div class="date-separator"><span>Сегодня</span></div>

          <article
            v-for="message in messages"
            :key="message.id"
            class="message-row"
            :class="[
              `message-row--${message.role}`,
              { 'message-row--welcome': message.source === 'system' },
            ]"
          >
            <div v-if="message.role !== 'user'" class="avatar" aria-hidden="true">
              <img src="/assets/klever-mascot.png" alt="" />
            </div>

            <div class="message-stack">
              <span v-if="message.role !== 'user'" class="message-author">Клевер</span>
              <div class="bubble">
                <div v-if="isRecipeReply(message.structuredReply)" class="recipe-reply">
                  <div class="recipe-heading">
                    <span class="recipe-kicker">Готовый рецепт</span>
                    <h3>{{ message.structuredReply.dishName }}</h3>
                  </div>
                  <div class="recipe-section">
                    <strong>Ингредиенты</strong>
                    <ul>
                      <li
                        v-for="ingredient in message.structuredReply.requiredIngredients"
                        :key="ingredient"
                      >
                        <span aria-hidden="true"></span>{{ ingredient }}
                      </li>
                    </ul>
                  </div>
                  <div class="recipe-time">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <circle cx="12" cy="12" r="8" />
                      <path d="M12 8v4l3 2" />
                    </svg>
                    <span><strong>Время готовки</strong>{{ message.structuredReply.cookingTime }}</span>
                  </div>
                </div>
                <pre v-else-if="message.structuredReply" class="json-reply">{{ formatStructuredReply(message.structuredReply) }}</pre>
                <p v-else>{{ message.text }}</p>
              </div>
              <div class="message-meta">
                <span v-if="message.source === 'fallback'" class="source-badge">Локально</span>
                <span v-else-if="message.source === 'llm'" class="source-badge source-badge--live">DeepSeek</span>
                <span v-if="message.responseMode" class="mode-badge">{{ responseModeName(message.responseMode) }}</span>
                <time v-if="message.time">{{ message.time }}</time>
              </div>
            </div>
          </article>

          <article v-if="isSending" class="message-row message-row--assistant">
            <div class="avatar" aria-hidden="true">
              <img src="/assets/klever-mascot.png" alt="" />
            </div>
            <div class="message-stack">
              <span class="message-author">Клевер отвечает</span>
              <div class="bubble typing" aria-label="Ассистент печатает">
                <span></span><span></span><span></span>
              </div>
            </div>
          </article>
        </div>

        <div class="input-dock">
          <form class="composer" @submit.prevent="submitMessage">
            <label class="sr-only" for="message">Сообщение</label>
            <textarea
              id="message"
              v-model="draft"
              :disabled="isSending"
              maxlength="10000"
              rows="1"
              placeholder="Напишите, о чём хотите поговорить…"
              @keydown="handleKeydown"
            ></textarea>
            <button type="submit" :disabled="!draft.trim() || isSending" aria-label="Отправить сообщение">
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="m5 12 14-7-4.5 14-3-5.5L5 12Z" />
                <path d="m11.5 13.5 3-3" />
              </svg>
            </button>
          </form>

          <footer class="chat-footer">
            <span>{{ selectedResponseMode?.description }}</span>
            <span><kbd>Enter</kbd> отправить · <kbd>Shift</kbd> + <kbd>Enter</kbd> новая строка</span>
          </footer>
        </div>
      </section>
    </section>
  </main>
</template>
