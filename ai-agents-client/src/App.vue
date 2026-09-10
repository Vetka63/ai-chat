<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AgentSidebar from './components/AgentSidebar.vue'
import ChatSidebar from './components/ChatSidebar.vue'
import ModelPicker from './components/ModelPicker.vue'
import ChatThread from './components/ChatThread.vue'
import MessageComposer from './components/MessageComposer.vue'
import { useAgentChat } from './composables/useAgentChat'
import TokenPanel from './features/tokens/TokenPanel.vue'
import ContextPanel from './features/context/ContextPanel.vue'

const chat = useAgentChat()
const sidebarOpen = ref(false)
const compact = ref(window.innerWidth <= 1180)
const mobile = ref(window.innerWidth <= 760)
const settingsOpen = ref(!compact.value)
const theme = ref('light')
const settingsButton = ref(null)
const chatsButton = ref(null)
const settingsPanel = ref(null)
const chatsPanel = ref(null)
const busy = computed(() => chat.loading.value || chat.sending.value)
const overlay = computed(() => mobile.value && sidebarOpen.value ? 'chats' : compact.value && settingsOpen.value ? 'settings' : null)
watch(theme, value => { document.documentElement.dataset.theme = value }, { immediate: true })

function resize() {
  const nextCompact = window.innerWidth <= 1180
  if (nextCompact !== compact.value) settingsOpen.value = !nextCompact
  compact.value = nextCompact
  mobile.value = window.innerWidth <= 760
  sidebarOpen.value = false
}
function closePanels() {
  const wasChats = overlay.value === 'chats'
  if (wasChats) sidebarOpen.value = false
  else settingsOpen.value = false
  nextTick(() => (wasChats ? chatsButton : settingsButton).value?.focus())
}
function toggleSettings() {
  sidebarOpen.value = false
  settingsOpen.value = !settingsOpen.value
}
function toggleChats() {
  settingsOpen.value = false
  sidebarOpen.value = !sidebarOpen.value
}
// В выдвижной панели фокус остаётся внутри; Escape возвращает его на кнопку.
watch(overlay, async value => {
  if (!value) return
  await nextTick()
  const panel = value === 'chats' ? chatsPanel.value?.$el : settingsPanel.value?.$el
  panel?.querySelector('button:not(:disabled)')?.focus()
})
function keyboard(event) {
  if (!overlay.value) return
  if (event.key === 'Escape') { event.preventDefault(); closePanels(); return }
  if (event.key !== 'Tab') return
  const panel = overlay.value === 'chats' ? chatsPanel.value?.$el : settingsPanel.value?.$el
  const items = [...(panel?.querySelectorAll('button:not(:disabled), a[href], input:not(:disabled), summary, [tabindex="0"]') || [])].filter(el => el.getClientRects().length > 0)
  const first = items[0], last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
onMounted(() => {
  chat.initialize()
  window.addEventListener('resize', resize)
  document.addEventListener('keydown', keyboard)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  document.removeEventListener('keydown', keyboard)
})
function chooseConversation(id) {
  chat.selectConversation(id)
  if (mobile.value) closePanels()
}
function newChat() {
  chat.newChat()
  if (mobile.value) closePanels()
}
async function removeConversation(item) {
  if (window.confirm(`Удалить чат «${item.title}»?`)) await chat.removeConversation(item.id)
}
</script>

<template>
  <div class="app-shell" :class="{ 'settings-open': settingsOpen }">
    <div v-if="theme === 'new-year'" class="garland" aria-hidden="true"><i v-for="n in 18" :key="n"></i></div>
    <button v-if="overlay" class="scrim" tabindex="-1" aria-label="Закрыть боковую панель" @click="closePanels"></button>
    <ChatSidebar ref="chatsPanel" id="chats-panel" :class="{ open: sidebarOpen }"
      :inert="(mobile && !sidebarOpen) || overlay === 'settings'"
      :aria-hidden="(mobile && !sidebarOpen) || overlay === 'settings' ? true : undefined"
      :role="overlay === 'chats' ? 'dialog' : undefined" :aria-modal="overlay === 'chats' ? true : undefined"
      :conversations="chat.conversations.value" :selected-conversation="chat.conversation.value?.id"
      :agent-name="chat.agent.value?.name" :busy="busy"
      @new="newChat" @select="chooseConversation" @delete="removeConversation" @close="closePanels" />
    <main class="main-panel" :inert="Boolean(overlay)" :aria-hidden="overlay ? true : undefined">
      <header class="chat-header">
        <button ref="chatsButton" class="menu-button icon-button" aria-label="Открыть список чатов" aria-controls="chats-panel" :aria-expanded="sidebarOpen" @click="toggleChats">☰</button>
        <div><strong>{{ chat.conversation.value?.title || chat.agent.value?.name || 'AI Agents' }}</strong><span><i></i>{{ chat.loading.value ? 'Загрузка истории…' : 'Контекст сохранён' }}</span></div>
        <button ref="settingsButton" class="settings-toggle icon-button" aria-label="Настройки агента" aria-controls="settings-panel" :aria-expanded="settingsOpen" @click="toggleSettings"><span aria-hidden="true">☷</span><span class="settings-label">Настройки</span></button>
      </header>
      <div v-if="chat.error.value" class="error-banner" role="alert">{{ chat.error.value }} <button @click="chat.initialize">Повторить</button></div>
      <ChatThread :messages="chat.messages.value" :runs="chat.runs.value" :agent-name="chat.agent.value?.name" :sending="chat.sending.value" />
      <MessageComposer v-model="chat.draft.value" :disabled="busy || !chat.agentId.value" :sending="chat.sending.value" @send="chat.send">
        <ModelPicker :models="chat.models.value" :model-id="chat.modelId.value" :busy="busy" @model="chat.changeModel" />
      </MessageComposer>
    </main>
    <AgentSidebar ref="settingsPanel" id="settings-panel" v-show="settingsOpen" :inert="overlay === 'chats'"
      :aria-hidden="overlay === 'chats' ? true : undefined"
      :role="overlay === 'settings' ? 'dialog' : undefined" :aria-modal="overlay === 'settings' ? true : undefined"
      :agents="chat.agents.value" :selected-agent="chat.agentId.value" :theme="theme" :busy="busy"
      @select-agent="chat.selectAgent" @theme="theme = $event" @close="closePanels">
      <ContextPanel v-if="chat.agent.value?.capabilities?.includes('context_memory')" :settings="chat.contextSettings.value" :summary="chat.conversation.value?.summary"
        :estimate="chat.estimate.value" :busy="busy" :has-conversation="Boolean(chat.conversation.value)" @change="chat.changeContext" @fork="chat.forkChat" />
      <TokenPanel :models="chat.models.value" :model-id="chat.modelId.value" :runs="chat.runs.value" :conversation="chat.conversation.value"
        :estimate="chat.estimate.value" :estimating="chat.estimating.value" :preview-error="chat.previewError.value" :busy="busy" :title="chat.conversation.value?.title" />
    </AgentSidebar>
  </div>
</template>
