const BASE = '/api/v1/mcp'

async function request(path, options) {
  const response = await fetch(`${BASE}${path}`, options)
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(payload.error || 'Не удалось получить ответ MCP-раздела')
  return payload
}

export const listMcpServers = () => request('/servers')
export const discoverMcpTools = serverId => request(`/servers/${encodeURIComponent(serverId)}/discover`, { method: 'POST' })
