import { request } from '../../api/agents'

const path = (agentId, conversationId) =>
  `/agents/${encodeURIComponent(agentId)}/conversations/${encodeURIComponent(conversationId)}/workflow`

export const getWorkflow = (agentId, conversationId) => request(path(agentId, conversationId))

export const applyWorkflow = (agentId, conversationId, body) => request(`${path(agentId, conversationId)}/actions`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
})
