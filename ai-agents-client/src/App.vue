<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AgentSidebar from './components/AgentSidebar.vue'
import ChatSidebar from './components/ChatSidebar.vue'
import OutputSettings from './components/OutputSettings.vue'
import ModelPicker from './components/ModelPicker.vue'
import ChatThread from './components/ChatThread.vue'
import MessageComposer from './components/MessageComposer.vue'
import NewChatDialog from './components/NewChatDialog.vue'
import { useAgentChat } from './composables/useAgentChat'
import TokenPanel from './features/tokens/TokenPanel.vue'
import ContextPanel from './features/context/ContextPanel.vue'
import MemoryPanel from './features/memory/MemoryPanel.vue'
import { useMemoryLayers } from './features/memory/useMemoryLayers'
import { useProfiles } from './features/profiles/useProfiles'
import ProfilePanel from './features/profiles/ProfilePanel.vue'
import WorkflowPanel from './features/workflow/WorkflowPanel.vue'
import { useWorkflow } from './features/workflow/useWorkflow'

const chat = useAgentChat()
const memory = useMemoryLayers(chat)
const profiles = useProfiles(chat, memory)
const workflow = useWorkflow(chat)
const lastMemoryContext = computed(() => [...chat.runs.value].reverse().find(r => r.purpose === 'dialogue' && r.memory_context)?.memory_context)
const sidebarOpen = ref(false)
const compact = ref(window.innerWidth <= 1180)
const mobile = ref(window.innerWidth <= 760)
const settingsOpen = ref(!compact.value)
const focusMode = ref(localStorage.getItem('agents:focus-mode') === 'true')
const theme = ref('light')
const settingsButton = ref(null)
const chatsButton = ref(null)
const settingsPanel = ref(null)
const chatsPanel = ref(null)
const newChatOpen = ref(false)
const busy = computed(() => chat.loading.value || chat.sending.value || memory.busy.value || profiles.busy.value || workflow.busy.value)
const overlay = computed(() => focusMode.value ? null : mobile.value && sidebarOpen.value ? 'chats' : compact.value && settingsOpen.value ? 'settings' : null)
watch(theme, value => { document.documentElement.dataset.theme = value }, { immediate: true })
watch(focusMode, value => { localStorage.setItem('agents:focus-mode', String(value)) })

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
  if (focusMode.value) { focusMode.value = false; settingsOpen.value = true; return }
  sidebarOpen.value = false
  settingsOpen.value = !settingsOpen.value
}
function toggleChats() {
  if (focusMode.value) { focusMode.value = false; sidebarOpen.value = true; settingsOpen.value = false; return }
  settingsOpen.value = false
  sidebarOpen.value = !sidebarOpen.value
}
function toggleFocus() {
  focusMode.value = !focusMode.value
  sidebarOpen.value = false
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
  const items = [...(panel?.querySelectorAll('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex="0"]') || [])].filter(el => el.getClientRects().length > 0)
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
  newChatOpen.value = true
}
async function createNewChat({ title, contextSettings, problem, profileId }) {
  await chat.newChat(title, contextSettings, problem, profileId)
  if (!chat.error.value) {
    newChatOpen.value = false
    if (mobile.value) closePanels()
  }
}
async function removeConversation(item) {
  const suffix = memory.enabled.value ? ' Карточка и рабочая память удалятся, долговременные записи останутся.' : ''
  if (window.confirm(`Удалить чат «${item.title}»?${suffix}`)) await chat.removeConversation(item.id)
}
</script>

<template>
  <div class="app-shell" :class="{ 'settings-open': settingsOpen, 'focus-mode': focusMode }">
    <div v-if="theme === 'new-year'" class="garland" aria-hidden="true"><i v-for="n in 18" :key="n"></i></div>
    <button v-if="overlay" class="scrim" tabindex="-1" aria-label="Закрыть боковую панель" @click="closePanels"></button>
    <ChatSidebar ref="chatsPanel" v-show="!focusMode" id="chats-panel" :class="{ open: sidebarOpen }"
      :inert="newChatOpen || focusMode || (mobile && !sidebarOpen) || overlay === 'settings'"
      :aria-hidden="newChatOpen || focusMode || (mobile && !sidebarOpen) || overlay === 'settings' ? true : undefined"
      :role="overlay === 'chats' ? 'dialog' : undefined" :aria-modal="overlay === 'chats' ? true : undefined"
      :conversations="chat.conversations.value" :selected-conversation="chat.conversation.value?.id"
      :agent-name="chat.agent.value?.name" :busy="busy"
      @new="newChat" @select="chooseConversation" @delete="removeConversation" @close="closePanels" />
    <main class="main-panel" :inert="Boolean(overlay) || newChatOpen" :aria-hidden="overlay || newChatOpen ? true : undefined">
      <header class="chat-header">
        <button ref="chatsButton" class="menu-button icon-button" aria-label="Открыть список чатов" aria-controls="chats-panel" :aria-expanded="sidebarOpen" @click="toggleChats">☰</button>
        <div><strong>{{ chat.conversation.value?.title || chat.agent.value?.name || 'AI Agents' }}</strong><span><i></i>{{ chat.loading.value ? 'Загрузка истории…' : memory.workspace.value?.profile ? memory.workspace.value.profile.name + ' · память сохранена' : 'Контекст сохранён' }}</span></div>
        <button class="focus-toggle icon-button" :aria-label="focusMode ? 'Вернуть боковые панели' : 'Развернуть чат'" :aria-pressed="focusMode" @click="toggleFocus"><span aria-hidden="true">{{ focusMode ? '◧' : '⛶' }}</span><span class="focus-label">{{ focusMode ? 'Обычный вид' : 'Развернуть чат' }}</span></button>
        <button ref="settingsButton" class="settings-toggle icon-button" aria-label="Настройки агента" aria-controls="settings-panel" :aria-expanded="settingsOpen && !focusMode" @click="toggleSettings"><span aria-hidden="true">☷</span><span class="settings-label">Настройки</span></button>
      </header>
      <div v-if="chat.error.value" class="error-banner" role="alert">{{ chat.error.value }} <button @click="chat.initialize">Повторить</button></div>
      <div v-if="chat.warning.value" class="warning-banner" role="status">{{ chat.warning.value }}</div>
      <WorkflowPanel v-if="workflow.enabled.value" :workspace="workflow.workspace.value"
        :busy="workflow.busy.value || chat.loading.value" :sending="chat.sending.value" :error="workflow.error.value"
        @apply="workflow.apply" @refresh="workflow.refresh" />
      <ChatThread :messages="chat.messages.value" :runs="chat.runs.value" :agent-name="chat.agent.value?.name" :sending="chat.sending.value" :memory-layers="memory.enabled.value" />
      <MessageComposer v-model="chat.draft.value" :disabled="busy || !chat.agentId.value || !chat.conversation.value || !chat.outputLimitValid.value || (workflow.enabled.value && (!workflow.workspace.value || workflow.workspace.value.state.status === 'paused' || workflow.workspace.value.state.phase === 'done'))" :sending="chat.sending.value" @send="chat.send">
        <ModelPicker :models="chat.models.value" :model-id="chat.modelId.value" :busy="busy" @model="chat.changeModel" />
      </MessageComposer>
    </main>
    <AgentSidebar ref="settingsPanel" id="settings-panel" v-show="settingsOpen && !focusMode" :inert="focusMode || overlay === 'chats' || newChatOpen"
      :aria-hidden="focusMode || overlay === 'chats' || newChatOpen ? true : undefined"
      :role="overlay === 'settings' ? 'dialog' : undefined" :aria-modal="overlay === 'settings' ? true : undefined"
      :agents="chat.agents.value" :selected-agent="chat.agentId.value" :theme="theme" :busy="busy"
      @select-agent="chat.selectAgent" @theme="theme = $event" @close="closePanels">
      <ProfilePanel v-if="profiles.enabled.value" :profiles="profiles.profiles.value" :profile="memory.workspace.value?.profile"
        :busy="busy" :loading="profiles.loading.value" :error="profiles.error.value" :notice="profiles.notice.value" :preferred-id="profiles.preferredId.value"
        @save="profiles.save" @refresh="profiles.refresh" />
      <OutputSettings :limit="chat.outputLimit.value" :model="chat.selectedModel.value" :busy="busy" :memory-layers="memory.enabled.value" @change="chat.outputLimit.value = $event" />
      <MemoryPanel v-if="memory.enabled.value" :workspace="memory.workspace.value" :busy="busy"
        :loading="memory.loading.value" :error="memory.error.value" :last-context="lastMemoryContext"
        @refresh="memory.refresh" @problem="memory.saveProblem" @save="memory.saveEntry"
        @delete="memory.deleteEntry" @propose="memory.propose" @resolve="memory.resolve" />
      <ContextPanel v-if="chat.agent.value?.capabilities?.includes('context_memory')" :settings="chat.contextSettings.value" :summary="chat.conversation.value?.summary"
        :facts="chat.conversation.value?.facts" :conversation="chat.conversation.value" :conversations="chat.conversations.value"
        :estimate="chat.estimate.value" :busy="busy" :has-conversation="Boolean(chat.conversation.value)"
        @fork="chat.forkChat" @checkpoint="chat.createCheckpoint"
        @branches="({ checkpointId, names }) => chat.createBranches(checkpointId, names)" @select-branch="chat.selectConversation" />
      <TokenPanel :models="chat.models.value" :model-id="chat.modelId.value" :runs="chat.runs.value" :conversation="chat.conversation.value" :memory-workspace="memory.workspace.value"
        :estimate="chat.estimate.value" :estimating="chat.estimating.value" :preview-error="chat.previewError.value" :busy="busy" :title="chat.conversation.value?.title" />
    </AgentSidebar>
    <NewChatDialog :open="newChatOpen" :busy="busy" :memory-layers="memory.enabled.value"
      :personalization="profiles.enabled.value" :profiles="profiles.profiles.value"
      :default-profile-id="profiles.preferredId.value || memory.workspace.value?.profile.id || 'local'"
      @cancel="newChatOpen = false" @create="createNewChat" />
  </div>
</template>
