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
  return response.json()
}

export async function listAgents() {
  return request('/agents')
}

export async function runAgent(agentId, message) {
  return request(`/agents/${encodeURIComponent(agentId)}/runs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message }),
  })
}

