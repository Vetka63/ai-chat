import { request } from '../../api/agents'

export const saveInvariants = (agent, chat, command) => request(
  `/agents/${encodeURIComponent(agent)}/conversations/${encodeURIComponent(chat)}/invariants`,
  { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(command) })
