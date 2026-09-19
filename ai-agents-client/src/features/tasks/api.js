import { request } from '../../api/agents'
export const taskCommand = (agent, chat, resource, command) => request(
  `/agents/${encodeURIComponent(agent)}/conversations/${encodeURIComponent(chat)}/task/${resource}`,
  { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(command) })
