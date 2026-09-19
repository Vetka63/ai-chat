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
import TaskPanel from './features/tasks/TaskPanel.vue'
import { useTaskWorkflow } from './features/tasks/useTaskWorkflow'
import InvariantPanel from './features/invariants/InvariantPanel.vue'
import { useInvariants } from './features/invariants/useInvariants'

const chat = useAgentChat()
const memory = useMemoryLayers(chat)
const profiles = useProfiles(chat, memory)
const tasks = useTaskWorkflow(chat, memory)
const invariants = useInvariants(chat, memory)
const lastMemoryContext = computed(() => [...chat.runs.value].reverse().find(r => r.purpose === 'dialogue' && r.memory_context)?.memory_context)
const sidebarOpen = ref(false)
const compact = ref(window.innerWidth <= 1180)
const mobile = ref(window.innerWidth <= 760)
const settingsOpen = ref(!compact.value)
const theme = ref('light')
const settingsButton = ref(null)
const chatsButton = ref(null)
const settingsPanel = ref(null)
const chatsPanel = ref(null)
const newChatOpen = ref(false)
const busy = computed(() => chat.loading.value || chat.sending.value || memory.busy.value || profiles.busy.value || tasks.busy.value || tasks.controlBusy.value || invariants.busy.value)
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
  <div class="app-shell" :class="{ 'settings-open': settingsOpen }">
    <div v-if="theme === 'new-year'" class="garland" aria-hidden="true"><i v-for="n in 18" :key="n"></i></div>
    <button v-if="overlay" class="scrim" tabindex="-1" aria-label="Закрыть боковую панель" @click="closePanels"></button>
    <ChatSidebar ref="chatsPanel" id="chats-panel" :class="{ open: sidebarOpen }"
      :inert="newChatOpen || (mobile && !sidebarOpen) || overlay === 'settings'"
      :aria-hidden="newChatOpen || (mobile && !sidebarOpen) || overlay === 'settings' ? true : undefined"
      :role="overlay === 'chats' ? 'dialog' : undefined" :aria-modal="overlay === 'chats' ? true : undefined"
      :conversations="chat.conversations.value" :selected-conversation="chat.conversation.value?.id"
      :agent-name="chat.agent.value?.name" :busy="busy"
      @new="newChat" @select="chooseConversation" @delete="removeConversation" @close="closePanels" />
    <main class="main-panel" :inert="Boolean(overlay) || newChatOpen" :aria-hidden="overlay || newChatOpen ? true : undefined">
      <header class="chat-header">
        <button ref="chatsButton" class="menu-button icon-button" aria-label="Открыть список чатов" aria-controls="chats-panel" :aria-expanded="sidebarOpen" @click="toggleChats">☰</button>
        <div><strong>{{ chat.conversation.value?.title || chat.agent.value?.name || 'AI Agents' }}</strong><span><i></i>{{ chat.loading.value ? 'Загрузка истории…' : memory.workspace.value?.profile ? memory.workspace.value.profile.name + ' · память сохранена' : 'Контекст сохранён' }}</span></div>
        <button ref="settingsButton" class="settings-toggle icon-button" aria-label="Настройки агента" aria-controls="settings-panel" :aria-expanded="settingsOpen" @click="toggleSettings"><span aria-hidden="true">☷</span><span class="settings-label">Настройки</span></button>
      </header>
      <div v-if="chat.error.value" class="error-banner" role="alert">{{ chat.error.value }} <button @click="chat.initialize">Повторить</button></div>
      <div v-if="chat.warning.value" class="warning-banner" role="status">{{ chat.warning.value }}</div>
      <ChatThread :messages="chat.messages.value" :runs="chat.runs.value" :agent-name="chat.agent.value?.name" :sending="chat.sending.value" :memory-layers="memory.enabled.value" />
      <div v-if="tasks.enabled.value && tasks.blocked.value && tasks.workspace.value" class="warning-banner" role="status">{{ tasks.workspace.value.state.status === 'paused' ? 'Задача на паузе. Продолжите её в панели настроек.' : tasks.workspace.value.state.phase === 'done' ? 'Задача завершена. Для нового решения создайте новую задачу.' : 'Нет подтверждения плана Дня 15. Выберите «Перепланировать» в панели задачи.' }}</div>
      <MessageComposer v-model="chat.draft.value" :disabled="busy || tasks.blocked.value || !chat.agentId.value || !chat.conversation.value || !chat.outputLimitValid.value" :sending="chat.sending.value" @send="tasks.enabled.value ? tasks.send() : chat.send()">
        <ModelPicker :models="chat.models.value" :model-id="chat.modelId.value" :busy="busy" @model="chat.changeModel" />
      </MessageComposer>
    </main>
    <AgentSidebar ref="settingsPanel" id="settings-panel" v-show="settingsOpen" :inert="overlay === 'chats' || newChatOpen"
      :aria-hidden="overlay === 'chats' || newChatOpen ? true : undefined"
      :role="overlay === 'settings' ? 'dialog' : undefined" :aria-modal="overlay === 'settings' ? true : undefined"
      :agents="chat.agents.value" :selected-agent="chat.agentId.value" :theme="theme" :busy="busy"
      @select-agent="chat.selectAgent" @theme="theme = $event" @close="closePanels">
      <InvariantPanel v-if="invariants.enabled.value" :workspace="memory.workspace.value?.invariants" :state="tasks.workspace.value?.state"
        :task-id="memory.workspace.value?.task.id" :busy="busy" :error="invariants.error.value" @save="invariants.save" />
      <TaskPanel v-if="tasks.enabled.value" :workspace="tasks.workspace.value" :busy="tasks.controlBusy.value || invariants.busy.value || chat.loading.value || memory.busy.value || profiles.busy.value"
        :artifact-busy="tasks.busy.value" :invariant-revision="memory.workspace.value?.invariants.revision"
        :sending="chat.sending.value" :error="tasks.error.value" :task-revision="memory.workspace.value?.task.revision"
        @transition="tasks.transition" @save="tasks.save" @step="tasks.step" @refresh="memory.refresh" />
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
