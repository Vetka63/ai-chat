import { computed, ref } from 'vue'
import * as api from '../api/agents'

export function useAgentChat() {
  const agents = ref([])
  const agentId = ref('')
  const conversations = ref([])
  const conversation = ref(null)
  const draft = ref('')
  const loading = ref(true)
  const sending = ref(false)
  const error = ref('')

  const agent = computed(() => agents.value.find((item) => item.id === agentId.value))
  const messages = computed(() => conversation.value?.messages || [])
  const selectedKey = (id) => `day7:selected:${id}`

  async function loadConversation(id) {
    conversation.value = await api.getConversation(agentId.value, id)
    localStorage.setItem(selectedKey(agentId.value), id)
  }

  async function loadAgent(id) {
    agentId.value = id
    conversation.value = null
    conversations.value = await api.listConversations(id)
    const saved = localStorage.getItem(selectedKey(id))
    const selected = conversations.value.find((item) => item.id === saved) || conversations.value[0]
    if (selected) await loadConversation(selected.id)
  }

  async function initialize() {
    loading.value = true
    error.value = ''
    try {
      const data = await api.listAgents()
      agents.value = data.agents || []
      if (agents.value[0]) await loadAgent(agents.value[0].id)
    } catch (cause) {
      error.value = cause.message
    } finally {
      loading.value = false
    }
  }

  async function selectAgent(id) {
    if (id === agentId.value || loading.value || sending.value) return
    loading.value = true
    error.value = ''
    try {
      await loadAgent(id)
    } catch (cause) {
      error.value = cause.message
    } finally {
      loading.value = false
    }
  }

  async function selectConversation(id) {
    if (conversation.value?.id === id || loading.value || sending.value) return
    loading.value = true
    error.value = ''
    try {
      await loadConversation(id)
    } catch (cause) {
      error.value = cause.message
    } finally {
      loading.value = false
    }
  }

  async function newChat() {
    if (!agentId.value || loading.value || sending.value) return
    loading.value = true
    error.value = ''
    try {
      const created = await api.createConversation(agentId.value)
      conversations.value.unshift(created)
      conversation.value = { ...created, messages: [] }
      localStorage.setItem(selectedKey(agentId.value), created.id)
      draft.value = ''
    } catch (cause) {
      error.value = cause.message
    } finally {
      loading.value = false
    }
  }

  async function removeConversation(id) {
    if (loading.value || sending.value) return
    loading.value = true
    error.value = ''
    try {
      await api.deleteConversation(agentId.value, id)
      conversations.value = conversations.value.filter((item) => item.id !== id)
      if (conversation.value?.id === id) {
        conversation.value = null
        localStorage.removeItem(selectedKey(agentId.value))
        if (conversations.value[0]) await loadConversation(conversations.value[0].id)
      }
    } catch (cause) {
      error.value = cause.message
    } finally {
      loading.value = false
    }
  }

  async function send() {
    const text = draft.value.trim()
    if (!text || sending.value || loading.value || !agentId.value) return
    sending.value = true
    error.value = ''
    let createdForMessage = false
    try {
      if (!conversation.value) {
        const created = await api.createConversation(agentId.value, text.slice(0, 60))
        conversations.value.unshift(created)
        conversation.value = { ...created, messages: [] }
        localStorage.setItem(selectedKey(agentId.value), created.id)
        createdForMessage = true
      }
      const target = conversation.value
      target.messages.push({ role: 'user', content: text })
      draft.value = ''
      const result = await api.runConversationAgent(agentId.value, target.id, text)
      target.messages.push({ role: 'assistant', content: result.reply, model: result.model, source: result.source })
      conversations.value = await api.listConversations(agentId.value)
      const summary = conversations.value.find((item) => item.id === target.id)
      if (summary) Object.assign(target, summary)
    } catch (cause) {
      error.value = cause.message || 'Не удалось получить ответ'
      // Сервер сохраняет вопрос до обращения к LLM. Перечитываем диалог, чтобы
      // при ошибке модели пользовательское сообщение не исчезало с экрана.
      if (conversation.value?.id) {
        try {
          conversation.value = await api.getConversation(agentId.value, conversation.value.id)
        } catch {
          // При сетевой ошибке оставляем оптимистично добавленный вопрос на экране.
        }
      }
      if (createdForMessage && !conversation.value?.messages.length) {
        // Пустой созданный чат остаётся доступным для повторной отправки.
      }
    } finally {
      sending.value = false
    }
  }

  return {
    agents, agentId, agent, conversations, conversation, messages, draft,
    loading, sending, error, initialize, selectAgent, selectConversation,
    newChat, removeConversation, send,
  }
}
