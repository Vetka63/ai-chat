import { computed, ref, watch, onScopeDispose } from 'vue'
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
  const models = ref([])
  const modelId = ref('')
  const defaultModelId = ref('')
  const estimate = ref(null)
  const previewError = ref('')
  const estimating = ref(false)
  const contextSettings = ref({ mode: 'full', keep_last: 10, summarize_every: 10 })
  const runs = computed(() => conversation.value?.runs || [])
  const selectedModel = computed(() => models.value.find((m) => m.id === modelId.value))

  const agent = computed(() => agents.value.find((item) => item.id === agentId.value))
  const messages = computed(() => conversation.value?.messages || [])
  const selectedKey = (id) => `day9:selected:${id}`

  // Старый результат оценки не может заменить оценку нового черновика/модели.
  let previewController, previewTimer, revision = 0
  watch([draft, modelId, agentId, conversation, sending, contextSettings], () => {
    const requestRevision = ++revision
    clearTimeout(previewTimer)
    previewController?.abort()
    estimate.value = null
    previewError.value = ''
    estimating.value = false
    if (!agentId.value || !modelId.value || sending.value) return
    estimating.value = true
    previewTimer = setTimeout(async () => {
      previewController = new AbortController()
      try {
        const result = await api.previewTokens(agentId.value, conversation.value?.id, draft.value, modelId.value, previewController.signal)
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
    modelId.value = conversation.value.selected_model_id || defaultModelId.value
    contextSettings.value = conversation.value.context_settings || { mode: 'full', keep_last: 10, summarize_every: 10 }
    localStorage.setItem(selectedKey(agentId.value), id)
  }

  async function loadAgent(id) {
    agentId.value = id
    conversation.value = null
    modelId.value = defaultModelId.value
    conversations.value = await api.listConversations(id)
    const saved = localStorage.getItem(selectedKey(id))
    const selected = conversations.value.find((item) => item.id === saved) || conversations.value[0]
    if (selected) await loadConversation(selected.id)
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
      const created = await api.createConversation(agentId.value, 'Новый чат', contextSettings.value)
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
        const created = await api.createConversation(agentId.value, text.slice(0, 60), contextSettings.value)
        conversations.value.unshift(created)
        conversation.value = { ...created, messages: [] }
        localStorage.setItem(selectedKey(agentId.value), created.id)
        createdForMessage = true
      }
      const target = conversation.value
      target.messages.push({ role: 'user', content: text })
      draft.value = ''
      const result = await api.runConversationAgent(agentId.value, target.id, text, modelId.value)
      target.messages.push({ role: 'assistant', content: result.reply, model: result.model, source: result.source })
      target.runs = [...(target.runs || []), ...(result.additional_runs || []), ...(result.run ? [result.run] : [])]
      target.summary = result.summary || null
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
    models, modelId, selectedModel, runs, estimate, estimating, previewError, changeModel,
    contextSettings, changeContext, forkChat,
  }

  async function changeContext(settings) {
    if (loading.value || sending.value) return
    loading.value = true
    try {
      if (conversation.value) {
        await api.configureContext(agentId.value, conversation.value.id, settings)
        conversation.value.context_settings = { ...settings }
        const item = conversations.value.find((c) => c.id === conversation.value.id)
        if (item) item.context_settings = { ...settings }
      }
      contextSettings.value = { ...settings }
      error.value = ''
    } catch (cause) { error.value = cause.message }
    finally { loading.value = false }
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
}
