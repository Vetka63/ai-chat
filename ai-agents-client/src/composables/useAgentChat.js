import { computed, ref, watch, onScopeDispose } from 'vue'
import * as api from '../api/agents'
import { listMcpServers } from '../features/mcp/api'

export function useAgentChat() {
  const agents = ref([])
  const agentId = ref('')
  const conversations = ref([])
  const conversation = ref(null)
  const draft = ref('')
  const loading = ref(true)
  const sending = ref(false)
  const error = ref('')
  const warning = ref('')
  const models = ref([])
  const modelId = ref('')
  const defaultModelId = ref('')
  const estimate = ref(null)
  const previewError = ref('')
  const estimating = ref(false)
  const contextSettings = ref({ mode: 'full', keep_last: 10, summarize_every: 10 })
  const outputLimit = ref(null)
  const mcpServers = ref([])
  const mcpServerIds = ref([])
  const mcpLoading = ref(false)
  const mcpError = ref('')
  const outputLimitValid = computed(() => outputLimit.value == null || (Number.isInteger(outputLimit.value) && outputLimit.value > 0 && outputLimit.value <= (selectedModel.value?.max_output_tokens || 1200)))
  const runs = computed(() => conversation.value?.runs || [])
  const selectedModel = computed(() => models.value.find((m) => m.id === modelId.value))

  const agent = computed(() => agents.value.find((item) => item.id === agentId.value))
  const messages = computed(() => conversation.value?.messages || [])
  const selectedKey = (id) => `agents:selected:${id}`
  const selectedAgentKey = 'agents:selected-agent'
  const mcpSelectionKey = (id) => `agents:mcp-selection:${id}`

  function changeMcpServers(ids) {
    mcpServerIds.value = [...ids]
    if (agent.value?.capabilities?.includes('mcp_tools') && conversation.value) {
      localStorage.setItem(mcpSelectionKey(conversation.value.id), JSON.stringify(mcpServerIds.value))
    }
  }

  function savedMcpSelection(id) {
    try {
      const value = JSON.parse(localStorage.getItem(mcpSelectionKey(id)))
      return Array.isArray(value) && value.every(item => typeof item === 'string') ? value : null
    } catch {
      return null
    }
  }

  // Старый результат оценки не может заменить оценку нового черновика/модели.
  let previewController, previewTimer, revision = 0
  watch([draft, modelId, agentId, conversation, sending, contextSettings, outputLimit], () => {
    const requestRevision = ++revision
    clearTimeout(previewTimer)
    previewController?.abort()
    estimate.value = null
    previewError.value = ''
    estimating.value = false
    if (!agentId.value || !modelId.value || sending.value || !agent.value?.capabilities?.includes('token_accounting') || (agent.value?.capabilities?.includes('memory_layers') && !conversation.value)) return
    estimating.value = true
    previewTimer = setTimeout(async () => {
      previewController = new AbortController()
      try {
        const result = await api.previewTokens(agentId.value, conversation.value?.id, draft.value, modelId.value, previewController.signal, outputLimit.value)
        if (requestRevision === revision) estimate.value = result
      } catch (cause) {
        if (requestRevision === revision && cause.name !== 'AbortError') previewError.value = 'Оценка временно недоступна; отправка разрешена'
      } finally {
        if (requestRevision === revision) estimating.value = false
      }
    }, 500)
  })
  onScopeDispose(() => { ++revision; clearTimeout(previewTimer); previewController?.abort() })

  async function changeModel(id) {
    if (sending.value || loading.value) return
    loading.value = true
    try {
      if (conversation.value) {
        await api.selectModel(agentId.value, conversation.value.id, id)
        conversation.value.selected_model_id = id
      }
      modelId.value = id
      error.value = ''
    } catch (cause) { error.value = cause.message }
    finally { loading.value = false }
  }

  async function loadConversation(id) {
    conversation.value = await api.getConversation(agentId.value, id)
    if (agent.value?.capabilities?.includes('mcp_tools')) {
      const lastUser = [...conversation.value.messages].reverse().find(item => item.role === 'user' && Array.isArray(item.mcp_server_ids))
      mcpServerIds.value = [...(savedMcpSelection(id) ?? lastUser?.mcp_server_ids ?? conversation.value.mcp_server_ids ?? [])]
      if (mcpServers.value.length) {
        const available = new Set(mcpServers.value.map(server => server.id))
        mcpServerIds.value = mcpServerIds.value.filter(serverId => available.has(serverId))
      }
    }
    warning.value = ''
    outputLimit.value = conversation.value.max_output_tokens ?? null
    modelId.value = conversation.value.selected_model_id || defaultModelId.value
    contextSettings.value = conversation.value.context_settings || { mode: 'full', keep_last: 10, summarize_every: 10 }
    localStorage.setItem(selectedKey(agentId.value), id)
  }

  async function loadAgent(id) {
    agentId.value = id
    conversation.value = null
    mcpServers.value = []
    mcpServerIds.value = []
    mcpError.value = ''
    outputLimit.value = null
    modelId.value = defaultModelId.value
    conversations.value = await api.listConversations(id)
    const saved = localStorage.getItem(selectedKey(id)) || localStorage.getItem(`day9:selected:${id}`)
    const selected = conversations.value.find((item) => item.id === saved) || conversations.value[0]
    if (selected) await loadConversation(selected.id)
    if (agent.value?.capabilities?.includes('mcp_tools')) await refreshMcpServers()
  }

  async function refreshMcpServers() {
    if (!agent.value?.capabilities?.includes('mcp_tools')) return
    mcpLoading.value = true
    mcpError.value = ''
    try {
      const response = await listMcpServers()
      mcpServers.value = (Array.isArray(response) ? response : response.servers || []).filter(
        item => item.chat_enabled && (!item.agent_ids?.length || item.agent_ids.includes(agentId.value)),
      )
      const available = new Set(mcpServers.value.map(item => item.id))
      mcpServerIds.value = mcpServerIds.value.filter(id => available.has(id))
      if (conversation.value) localStorage.setItem(mcpSelectionKey(conversation.value.id), JSON.stringify(mcpServerIds.value))
    } catch (cause) { mcpError.value = cause.message || 'Не удалось загрузить MCP-серверы' }
    finally { mcpLoading.value = false }
  }

  async function initialize() {
    loading.value = true
    error.value = ''
    try {
      const [data, catalog] = await Promise.all([api.listAgents(), api.listModels()])
      models.value = catalog.models
      defaultModelId.value = catalog.default_model_id
      modelId.value = defaultModelId.value
      agents.value = data.agents || []
      const savedAgent = localStorage.getItem(selectedAgentKey)
      const initialAgent = agents.value.find(item => item.id === savedAgent) || agents.value[0]
      if (initialAgent) await loadAgent(initialAgent.id)
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
      localStorage.setItem(selectedAgentKey, id)
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

  async function newChat(title = 'Новый чат', settings = { mode: 'full', keep_last: 10, summarize_every: 10 }, problem, profileId, mcpServerIds) {
    if (!agentId.value || loading.value || sending.value) return
    loading.value = true
    error.value = ''
    warning.value = ''
    try {
      const created = await api.createConversation(agentId.value, title, settings, problem, profileId, mcpServerIds)
      conversations.value.unshift(created)
      conversation.value = { ...created, messages: [], tool_events: [] }
      if (agent.value?.capabilities?.includes('mcp_tools')) changeMcpServers(created.mcp_server_ids || [])
      contextSettings.value = { ...created.context_settings }
      localStorage.setItem(selectedKey(agentId.value), created.id)
      draft.value = ''
      outputLimit.value = null
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
      localStorage.removeItem(mcpSelectionKey(id))
      conversations.value = conversations.value.filter((item) => item.id !== id)
      if (conversation.value?.id === id) {
        conversation.value = null
        mcpServerIds.value = []
        outputLimit.value = null
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
    if (!outputLimitValid.value || !text || sending.value || loading.value || !agentId.value) return
    sending.value = true
    error.value = ''
    warning.value = ''
    try {
      if (!conversation.value) return
      const target = conversation.value
      const selectedMcp = agent.value?.capabilities?.includes('mcp_tools') ? [...mcpServerIds.value] : undefined
      target.messages.push({ role: 'user', content: text, ...(selectedMcp ? { mcp_server_ids: selectedMcp } : {}) })
      draft.value = ''
      const result = await api.runConversationAgent(agentId.value, target.id, text, modelId.value, outputLimit.value, selectedMcp)
      target.messages.push({ role: 'assistant', content: result.reply, model: result.model, source: result.source })
      target.runs = [...(target.runs || []), ...(result.additional_runs || []), ...(result.run ? [result.run] : [])]
      target.tool_events = [...(target.tool_events || []), ...(result.tool_events || [])]
      target.summary = result.summary || null
      target.facts = result.facts || null
      target.token_savings = result.token_savings || null
      warning.value = (result.memory_warnings || []).join(' ')
      target.selected_model_id = modelId.value
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
    } finally {
      sending.value = false
    }
  }

  return {
    agents, agentId, agent, conversations, conversation, messages, draft,
    loading, sending, error, initialize, selectAgent, selectConversation,
    newChat, removeConversation, send,
    models, modelId, selectedModel, runs, estimate, estimating, previewError, changeModel,
    contextSettings, forkChat, createCheckpoint, createBranches,
    outputLimit, outputLimitValid, warning,
    mcpServers, mcpServerIds, mcpLoading, mcpError, refreshMcpServers, changeMcpServers,
  }

  async function forkChat() {
    if (loading.value || sending.value || !conversation.value) return
    loading.value = true
    try {
      const settings = { ...contextSettings.value, mode: contextSettings.value.mode === 'full' ? 'summary' : 'full' }
      const created = await api.forkConversation(agentId.value, conversation.value.id, settings)
      conversations.value = await api.listConversations(agentId.value)
      await loadConversation(created.id)
      draft.value = ''
      error.value = ''
    } catch (cause) { error.value = cause.message }
    finally { loading.value = false }
  }

  async function createCheckpoint(title) {
    if (loading.value || sending.value || !conversation.value) return
    loading.value = true
    try {
      const checkpoint = await api.createCheckpoint(agentId.value, conversation.value.id, title)
      conversation.value.checkpoints = [...(conversation.value.checkpoints || []), checkpoint]
      error.value = ''
    } catch (cause) { error.value = cause.message }
    finally { loading.value = false }
  }

  async function createBranches(checkpointId, names) {
    if (loading.value || sending.value || !conversation.value) return
    loading.value = true
    try {
      const created = await api.createBranches(agentId.value, checkpointId, names)
      conversations.value = await api.listConversations(agentId.value)
      if (created[0]) await loadConversation(created[0].id)
      error.value = ''
    } catch (cause) { error.value = cause.message }
    finally { loading.value = false }
  }
}
