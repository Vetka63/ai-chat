<script setup>
import { onMounted, ref, watch } from 'vue'
import AgentSidebar from './components/AgentSidebar.vue'
import ChatThread from './components/ChatThread.vue'
import MessageComposer from './components/MessageComposer.vue'
import { useAgentChat } from './composables/useAgentChat'

const chat = useAgentChat()
const sidebarOpen = ref(false)
const theme = ref('light')

watch(theme, (value) => { document.documentElement.dataset.theme = value }, { immediate: true })
onMounted(chat.initialize)

function chooseAgent(id) {
  chat.selectAgent(id)
  sidebarOpen.value = false
}
</script>

<template>
  <div class="app-shell">
    <div v-if="theme === 'new-year'" class="garland" aria-hidden="true"><i v-for="n in 18" :key="n"></i></div>
    <button v-if="sidebarOpen" class="scrim" aria-label="Закрыть меню" @click="sidebarOpen = false"></button>
    <AgentSidebar
      :agents="chat.agents.value"
      :selected="chat.agentId.value"
      :theme="theme"
      :open="sidebarOpen"
      @new="chat.newChat"
      @select="chooseAgent"
      @theme="theme = $event"
      @close="sidebarOpen = false"
    />
    <main class="main-panel">
      <header class="chat-header">
        <button class="menu-button" aria-label="Открыть меню" @click="sidebarOpen = true">☰</button>
        <div><strong>{{ chat.agent.value?.name || 'AI Agents' }}</strong><span><i></i>{{ chat.loading.value ? 'Подключение…' : 'Готов к запросу' }}</span></div>
        <span class="day-badge">DAY 6</span>
      </header>
      <div v-if="chat.error.value" class="error-banner" role="alert">{{ chat.error.value }} <button @click="chat.initialize">Повторить</button></div>
      <ChatThread :messages="chat.messages.value" :agent-name="chat.agent.value?.name" :sending="chat.sending.value" />
      <MessageComposer v-model="chat.draft.value" :disabled="chat.sending.value || !chat.agentId.value" :sending="chat.sending.value" @send="chat.send" />
    </main>
  </div>
</template>

