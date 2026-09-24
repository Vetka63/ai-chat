<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AgentSidebar from './components/AgentSidebar.vue'
import ChatSidebar from './components/ChatSidebar.vue'
import OutputSettings from './components/OutputSettings.vue'
import ModelPicker from './components/ModelPicker.vue'
import McpToolPicker from './components/McpToolPicker.vue'
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
import InvariantPanel from './features/invariants/InvariantPanel.vue'
import { useInvariants } from './features/invariants/useInvariants'
import McpExplorer from './features/mcp/McpExplorer.vue'
import GameDigestPanel from './features/gameDigest/GameDigestPanel.vue'

const chat = useAgentChat()
const memory = useMemoryLayers(chat)
const profiles = useProfiles(chat, memory)
const workflow = useWorkflow(chat)
const invariants = useInvariants(chat, workflow)
const lastMemoryContext = computed(() => [...chat.runs.value].reverse().find(r => r.purpose === 'dialogue' && r.memory_context)?.memory_context)
const sidebarOpen = ref(false)
const compact = ref(window.innerWidth <= 1180)
const mobile = ref(window.innerWidth <= 760)
const settingsOpen = ref(false)
const focusMode = ref(localStorage.getItem('agents:focus-mode') === 'true')
const theme = ref('light')
const settingsButton = ref(null)
const chatsButton = ref(null)
const settingsPanel = ref(null)
const chatsPanel = ref(null)
const newChatOpen = ref(false)
const mcpMode = ref(false)
const mcpExplorer = ref(null)
const busy = computed(() => chat.loading.value || chat.sending.value || memory.busy.value || profiles.busy.value || workflow.busy.value || invariants.busy.value)
const mcpEnabled = computed(() => chat.agent.value?.capabilities?.includes('mcp_tools'))
const selectedMcpNames = computed(() => chat.mcpServers.value
  .filter(server => chat.mcpServerIds.value.includes(server.id))
  .map(server => server.name).join(', '))
const overlay = computed(() => focusMode.value ? null : mobile.value && sidebarOpen.value ? 'chats' : !mcpMode.value && compact.value && settingsOpen.value ? 'settings' : null)
watch(theme, value => { document.documentElement.dataset.theme = value }, { immediate: true })
watch(focusMode, value => { localStorage.setItem('agents:focus-mode', String(value)) })

function resize() {
  const nextCompact = window.innerWidth <= 1180
  if (nextCompact !== compact.value) settingsOpen.value = false
  compact.value = nextCompact
  mobile.value = window.innerWidth <= 760
  sidebarOpen.value = false
}
function closePanels() {
  const wasChats = overlay.value === 'chats'
  if (wasChats) sidebarOpen.value = false
  else settingsOpen.value = false
  nextTick(() => {
    if (wasChats && mcpMode.value) mcpExplorer.value?.focusMenu()
    else (wasChats ? chatsButton : settingsButton).value?.focus()
  })
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
  mcpMode.value = false
  chat.selectConversation(id)
  if (mobile.value) closePanels()
}
function chooseAgent(id) {
  mcpMode.value = false
  chat.selectAgent(id)
  if (mobile.value) closePanels()
}
function newChat() {
  mcpMode.value = false
  newChatOpen.value = true
}
function openMcp() {
  mcpMode.value = !mcpMode.value
  focusMode.value = false
  settingsOpen.value = false
  sidebarOpen.value = false
}
async function createNewChat({ title, contextSettings, problem, profileId, mcpServerIds }) {
  await chat.newChat(title, contextSettings, problem, profileId, mcpServerIds)
  if (!chat.error.value) {
    newChatOpen.value = false
    if (mobile.value) closePanels()
  }
}
async function removeConversation(item) {
  const suffix = memory.enabled.value ? ' Карточка и рабочая память удалятся, долговременные записи останутся.' : ''
  if (window.confirm(`Удалить чат «${item.title}»?${suffix}`)) await chat.removeConversation(item.id)
}
async function saveProblem(problem) {
  await memory.saveProblem(problem)
  await workflow.refresh()
}
</script>

<template>
  <div class="app-shell" :class="{ 'settings-open': settingsOpen && !mcpMode, 'focus-mode': focusMode, 'mcp-mode': mcpMode }">
    <div v-if="theme === 'new-year'" class="garland" aria-hidden="true"><i v-for="n in 18" :key="n"></i></div>
    <button v-if="overlay" class="scrim" tabindex="-1" aria-label="Закрыть боковую панель" @click="closePanels"></button>
    <ChatSidebar ref="chatsPanel" v-show="!focusMode" id="chats-panel" :class="{ open: sidebarOpen }"
      :inert="newChatOpen || focusMode || (mobile && !sidebarOpen) || overlay === 'settings'"
      :aria-hidden="newChatOpen || focusMode || (mobile && !sidebarOpen) || overlay === 'settings' ? true : undefined"
      :role="overlay === 'chats' ? 'dialog' : undefined" :aria-modal="overlay === 'chats' ? true : undefined"
      :agents="chat.agents.value" :selected-agent="chat.agentId.value"
      :conversations="chat.conversations.value" :selected-conversation="chat.conversation.value?.id"
      :busy="busy" :mcp-active="mcpMode"
      @new="newChat" @select="chooseConversation" @select-agent="chooseAgent" @delete="removeConversation" @close="closePanels" @open-mcp="openMcp" />
    <main class="main-panel" :inert="Boolean(overlay) || newChatOpen" :aria-hidden="overlay || newChatOpen ? true : undefined">
      <McpExplorer v-if="mcpMode" ref="mcpExplorer" @open-chats="toggleChats" />
      <template v-else>
      <header class="chat-header">
        <button ref="chatsButton" class="menu-button icon-button" aria-label="Открыть список чатов" aria-controls="chats-panel" :aria-expanded="sidebarOpen" @click="toggleChats">☰</button>
        <div class="chat-heading"><span class="chat-agent-name">{{ chat.agent.value?.name || 'AI Agents' }}</span><strong>{{ chat.conversation.value?.title || 'Новый чат' }}</strong></div>
        <button class="focus-toggle icon-button" :aria-label="focusMode ? 'Вернуть боковые панели' : 'Развернуть чат'" :aria-pressed="focusMode" @click="toggleFocus"><span aria-hidden="true">{{ focusMode ? '◧' : '⛶' }}</span><span class="focus-label">{{ focusMode ? 'Обычный вид' : 'Развернуть чат' }}</span></button>
        <button ref="settingsButton" class="settings-toggle icon-button" aria-label="Настройки агента" aria-controls="settings-panel" :aria-expanded="settingsOpen && !focusMode" @click="toggleSettings"><span aria-hidden="true">☷</span><span class="settings-label">Настройки</span></button>
      </header>
      <div v-if="chat.error.value" class="error-banner" role="alert">{{ chat.error.value }} <button @click="chat.initialize">Повторить</button></div>
      <div v-if="chat.warning.value" class="warning-banner" role="status">{{ chat.warning.value }}</div>
      <WorkflowPanel v-if="workflow.enabled.value" :workspace="workflow.workspace.value"
        :busy="workflow.busy.value || chat.loading.value" :sending="chat.sending.value" :error="workflow.error.value"
        @apply="workflow.apply" @refresh="workflow.refresh" />
      <InvariantPanel v-if="invariants.enabled.value" :workspace="invariants.workspace.value"
        :conversation-id="chat.conversation.value?.id" :workflow-state="workflow.workspace.value?.state"
        :busy="invariants.busy.value || workflow.busy.value || chat.loading.value"
        :sending="chat.sending.value" :error="invariants.error.value"
        @save="invariants.save" @refresh="invariants.refresh" />
      <GameDigestPanel v-if="chat.agent.value?.capabilities?.includes('scheduled_reports')" :conversation-id="chat.conversation.value?.id" />
      <ChatThread v-else :messages="chat.messages.value" :runs="chat.runs.value" :tool-events="chat.conversation.value?.tool_events || []" :agent-name="chat.agent.value?.name" :sending="chat.sending.value" :memory-layers="memory.enabled.value" :mcp-tools="chat.agent.value?.capabilities?.includes('mcp_tools')" />
      <MessageComposer v-if="!chat.agent.value?.capabilities?.includes('scheduled_reports')" v-model="chat.draft.value" :disabled="busy || !chat.agentId.value || !chat.conversation.value || !chat.outputLimitValid.value || (workflow.enabled.value && (!workflow.workspace.value || workflow.workspace.value.state.status === 'paused' || workflow.workspace.value.state.phase === 'done'))" :sending="chat.sending.value" @send="chat.send">
        <template v-if="mcpEnabled" #context>
          <div class="mcp-composer-context">
            <McpToolPicker :servers="chat.mcpServers.value" :selected-ids="chat.mcpServerIds.value"
              :busy="busy || !chat.conversation.value" :loading="chat.mcpLoading.value" :error="chat.mcpError.value"
              @change="chat.changeMcpServers" @refresh="chat.refreshMcpServers" />
            <span v-if="selectedMcpNames" class="mcp-context-copy" :title="selectedMcpNames">{{ selectedMcpNames }}</span>
          </div>
        </template>
        <ModelPicker :models="chat.models.value" :model-id="chat.modelId.value" :busy="busy" @model="chat.changeModel" />
      </MessageComposer>
      </template>
    </main>
    <AgentSidebar ref="settingsPanel" id="settings-panel" v-show="settingsOpen && !focusMode && !mcpMode" :inert="focusMode || mcpMode || overlay === 'chats' || newChatOpen"
      :aria-hidden="focusMode || overlay === 'chats' || newChatOpen ? true : undefined"
      :role="overlay === 'settings' ? 'dialog' : undefined" :aria-modal="overlay === 'settings' ? true : undefined"
      :agent="chat.agent.value" :theme="theme" :busy="busy" :mcp-tools="mcpEnabled"
      @theme="theme = $event" @close="closePanels">
      <template #agent>
        <OutputSettings v-if="!chat.agent.value?.capabilities?.includes('scheduled_reports')" :limit="chat.outputLimit.value" :model="chat.selectedModel.value" :busy="busy" :memory-layers="memory.enabled.value" @change="chat.outputLimit.value = $event" />
      </template>
      <template #context>
        <div v-if="mcpEnabled" class="inspector-mcp-overview">
          <div class="inspector-feature-heading"><span class="inspector-feature-icon" aria-hidden="true">⌘</span><div><strong>Инструменты MCP</strong><small>Агент проверяет каталог, когда это полезно</small></div></div>
          <p>Выбранные серверы доступны агенту. Если модель признаёт, что не знает конкретную игру, агент проверит доступный каталог перед ответом. Список можно изменить над полем ввода.</p>
          <div v-for="server in chat.mcpServers.value" :key="server.id" class="inspector-server" :class="{ selected: chat.mcpServerIds.value.includes(server.id) }">
            <span aria-hidden="true">{{ chat.mcpServerIds.value.includes(server.id) ? '●' : '○' }}</span><div><strong>{{ server.name }}</strong><small>{{ chat.mcpServerIds.value.includes(server.id) ? 'Доступен следующему сообщению' : 'Не выбран' }}</small></div>
          </div>
          <p v-if="!chat.mcpServers.value.length" class="muted">Доступные MCP-серверы не найдены.</p>
          <button type="button" class="inspector-link-button" @click="openMcp">Открыть каталог MCP <span aria-hidden="true">↗</span></button>
        </div>
        <ProfilePanel v-if="profiles.enabled.value" :profiles="profiles.profiles.value" :profile="memory.workspace.value?.profile"
          :busy="busy" :loading="profiles.loading.value" :error="profiles.error.value" :notice="profiles.notice.value" :preferred-id="profiles.preferredId.value"
          @save="profiles.save" @refresh="profiles.refresh" />
        <MemoryPanel v-if="memory.enabled.value" :workspace="memory.workspace.value" :busy="busy"
          :loading="memory.loading.value" :error="memory.error.value" :last-context="lastMemoryContext"
          @refresh="memory.refresh" @problem="saveProblem" @save="memory.saveEntry"
          @delete="memory.deleteEntry" @propose="memory.propose" @resolve="memory.resolve" />
        <ContextPanel v-if="chat.agent.value?.capabilities?.includes('context_memory')" :settings="chat.contextSettings.value" :summary="chat.conversation.value?.summary"
          :facts="chat.conversation.value?.facts" :conversation="chat.conversation.value" :conversations="chat.conversations.value"
          :estimate="chat.estimate.value" :busy="busy" :has-conversation="Boolean(chat.conversation.value)"
          @fork="chat.forkChat" @checkpoint="chat.createCheckpoint"
          @branches="({ checkpointId, names }) => chat.createBranches(checkpointId, names)" @select-branch="chat.selectConversation" />
        <p v-if="chat.agent.value?.capabilities?.includes('scheduled_reports')" class="inspector-empty">Расписание и последние сводки находятся в центре чата. Сбор выполняется фоновым MCP-сервисом.</p>
        <p v-else-if="!mcpEnabled && !memory.enabled.value && !chat.agent.value?.capabilities?.includes('context_memory')" class="inspector-empty">У этого агента нет дополнительных настроек контекста.</p>
      </template>
      <template #metrics>
        <TokenPanel v-if="!chat.agent.value?.capabilities?.includes('scheduled_reports')" :models="chat.models.value" :model-id="chat.modelId.value" :runs="chat.runs.value" :conversation="chat.conversation.value" :memory-workspace="memory.workspace.value"
          :estimate="chat.estimate.value" :estimating="chat.estimating.value" :preview-error="chat.previewError.value" :busy="busy" :title="chat.conversation.value?.title" />
        <p v-else class="inspector-empty">Фоновые сводки не вызывают LLM и не расходуют её токены.</p>
      </template>
    </AgentSidebar>
    <NewChatDialog :open="newChatOpen" :busy="busy" :memory-layers="memory.enabled.value" :mcp-tools="chat.agent.value?.capabilities?.includes('mcp_tools')"
      :scheduled-reports="chat.agent.value?.capabilities?.includes('scheduled_reports')"
      :agent-name="chat.agent.value?.name" :personalization="profiles.enabled.value" :profiles="profiles.profiles.value"
      :default-profile-id="profiles.preferredId.value || memory.workspace.value?.profile.id || 'local'"
      @cancel="newChatOpen = false" @create="createNewChat" />
  </div>
</template>
