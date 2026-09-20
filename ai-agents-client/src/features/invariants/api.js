import { request } from '../../api/agents'

const path = (agentId, conversationId) =>
  `/agents/${encodeURIComponent(agentId)}/conversations/${encodeURIComponent(conversationId)}/invariants`

export const getInvariants = (agentId, conversationId) => request(path(agentId, conversationId))

export const saveInvariants = (agentId, conversationId, body) => request(path(agentId, conversationId), {
  method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
})
