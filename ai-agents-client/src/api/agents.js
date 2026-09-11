const base = '/api/v1'

export class ApiError extends Error {
  constructor(message, code, status) {
    super(message)
    this.code = code
    this.status = status
  }
}

async function request(path, options = {}) {
  const response = await fetch(base + path, options)
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}))
    throw new ApiError(payload.error || 'Сервис временно недоступен', payload.code, response.status)
  }
  if (response.status === 204) return null
  return response.json()
}

export async function listAgents() {
  return request('/agents')
}

const conversationsPath = (agentId, conversationId = '') =>
  `/agents/${encodeURIComponent(agentId)}/conversations${conversationId ? `/${encodeURIComponent(conversationId)}` : ''}`

export async function listConversations(agentId) {
  return request(conversationsPath(agentId))
}

export async function createConversation(agentId, title = 'Новый чат', contextSettings) {
  return request(conversationsPath(agentId), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, context_settings: contextSettings }),
  })
}

export async function getConversation(agentId, conversationId) {
  return request(conversationsPath(agentId, conversationId))
}

export async function deleteConversation(agentId, conversationId) {
  return request(conversationsPath(agentId, conversationId), { method: 'DELETE' })
}

export async function runConversationAgent(agentId, conversationId, message, modelId, maxOutputTokens = null) {
  return request(`/agents/${encodeURIComponent(agentId)}/runs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversation_id: conversationId, message, model_id: modelId, max_output_tokens: maxOutputTokens }),
  })
}

export const listModels = () => request('/models')
export const configureContext = (agentId, conversationId, settings) => request(`${conversationsPath(agentId, conversationId)}/context`, {
  method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(settings),
})
export const forkConversation = (agentId, conversationId, settings) => request(`${conversationsPath(agentId, conversationId)}/fork`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(settings),
})
export const selectModel = (agentId, conversationId, modelId) => request(`${conversationsPath(agentId, conversationId)}/model`, {
  method: 'PATCH', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ model_id: modelId }),
})
export const previewTokens = (agentId, conversationId, message, modelId, signal, maxOutputTokens = null) => request(`/agents/${encodeURIComponent(agentId)}/preview`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ conversation_id: conversationId || null, message, model_id: modelId, max_output_tokens: maxOutputTokens }), signal,
})
