import { computed, ref } from 'vue'
import { listAgents, runAgent } from '../api/agents'

export function useAgentChat() {
  const agents = ref([])
  const agentId = ref('')
  const messages = ref([])
  const draft = ref('')
  const loading = ref(true)
  const sending = ref(false)
  const error = ref('')

  const agent = computed(() => agents.value.find((item) => item.id === agentId.value))

  async function initialize() {
    loading.value = true
    error.value = ''
    try {
      const data = await listAgents()
      agents.value = data.agents || []
      agentId.value = agents.value[0]?.id || ''
    } catch (cause) {
      error.value = cause.message
    } finally {
      loading.value = false
    }
  }

  function newChat() {
    messages.value = []
    draft.value = ''
    error.value = ''
  }

  function selectAgent(id) {
    if (id === agentId.value) return
    agentId.value = id
    newChat()
  }

  async function send() {
    const text = draft.value.trim()
    if (!text || sending.value || !agentId.value) return
    const currentAgent = agentId.value
    messages.value.push({ id: crypto.randomUUID(), role: 'user', content: text })
    draft.value = ''
    sending.value = true
    error.value = ''
    try {
      const result = await runAgent(currentAgent, text)
      messages.value.push({
        id: crypto.randomUUID(),
        role: 'assistant',
        content: result.reply,
        model: result.model,
        source: result.source,
      })
    } catch (cause) {
      messages.value.push({
        id: crypto.randomUUID(),
        role: 'error',
        content: cause.message || 'Не удалось получить ответ',
      })
    } finally {
      sending.value = false
    }
  }

  return { agents, agentId, agent, messages, draft, loading, sending, error, initialize, newChat, selectAgent, send }
}

