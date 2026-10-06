import { request, type Generation, type Status } from '../../api'

export interface SendMessage { requestId: string; content: string; temperature: number; thinking: boolean; maxOutputTokens: number | null }
export interface ChatMessage { id: string; requestId: string; role: 'USER' | 'ASSISTANT'; content: string; status: Status; createdAt: string; parameters: SendMessage | null; generation: Generation | null; elapsedMilliseconds: number | null; contextMessageCount: number | null; error: string | null }
export interface Chat { id: string; title: string; model: string; createdAt: string; updatedAt: string; systemPrompt: string; messages: ChatMessage[] }
export interface ChatSummary { id: string; title: string; model: string; updatedAt: string; messageCount: number }
export const chatApi = {
  list: () => request<ChatSummary[]>('/chats'),
  get: (id: string) => request<Chat>(`/chats/${encodeURIComponent(id)}`),
  create: (model: string, title: string) => request<Chat>('/chats', { model, title }),
  delete: (id: string) => request<void>(`/chats/${encodeURIComponent(id)}`, undefined, 'DELETE'),
  send: (id: string, body: SendMessage) => request<Chat>(`/chats/${encodeURIComponent(id)}/messages`, body),
  retry: (id: string, requestId: string) => request<Chat>(`/chats/${encodeURIComponent(id)}/messages/${encodeURIComponent(requestId)}/retry`, {}),
}
