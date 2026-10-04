import type { components } from './schema'
export type Schema<K extends keyof components['schemas']> = components['schemas'][K]

/** Одна HTTP-точка входа; ошибки API отображаются без потери сообщения backend. */
export async function request<T>(path: string, body?: unknown, method = body === undefined ? 'GET' : 'POST'): Promise<T> {
  const response = await fetch(`/api/v1${path}`, { method, headers: body === undefined ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok) {
    const error = await response.json().catch(() => null) as Schema<'ApiError'> | null
    throw new Error(error?.message ?? `Ошибка HTTP ${response.status}`)
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}

export const api = {
  conversations: () => request<Schema<'Conversation'>[]>('/conversations'),
  createConversation: (body: Schema<'CreateConversation'>) => request<Schema<'ConversationDetail'>>('/conversations', body),
  conversation: (id: string) => request<Schema<'ConversationDetail'>>(`/conversations/${encodeURIComponent(id)}`),
  sendTurn: (id: string, body: Schema<'SendTurn'>) => request<Schema<'ConversationDetail'>>(`/conversations/${encodeURIComponent(id)}/turns`, body),
  deleteConversation: (id: string) => request<void>(`/conversations/${encodeURIComponent(id)}`, undefined, 'DELETE'),
  groundedAnswer: (body: Schema<'GroundingRequest'>) => request<Schema<'GroundedResult'>>('/grounded-answers', body),
  experiment: (body: Schema<'ExperimentRequest'>) => request<Schema<'ExperimentComparison'>>('/experiments/compare', body),
  answerSettings: () => request<Schema<'AnswerSettings'>>('/answer-settings'),
  questions: () => request<Schema<'ControlQuestion'>[]>('/evaluation/questions'),
  answer: (body: Schema<'AnswerRequest'>) => request<Schema<'AnswerResult'>>('/answers', body),
  corpus: () => request<Schema<'CorpusInfo'>>('/corpus'),
  documents: () => request<Schema<'DocumentInfo'>[]>('/documents'),
  document: (id: string) => request<Schema<'Document'>>(`/documents/${encodeURIComponent(id)}`),
  indexDocument: (indexId: string, id: string) => request<Schema<'Document'>>(`/indexes/${encodeURIComponent(indexId)}/documents/${encodeURIComponent(id)}`),
  preview: (config: Schema<'ChunkConfig'>) => request<Schema<'ChunkPreview'>>('/preview', config),
  start: (config: Schema<'ChunkConfig'>) => request<Schema<'IndexJob'>>('/jobs', config),
  jobs: () => request<Schema<'IndexJob'>[]>('/jobs'),
  indexes: () => request<Schema<'IndexInfo'>[]>('/indexes'),
  chunks: (id: string) => request<Schema<'Chunk'>[]>(`/indexes/${encodeURIComponent(id)}/chunks`),
  compare: (indexIds: string[]) => request<Schema<'IndexComparison'>>('/compare', { indexIds }),
  search: (id: string, query: string) => request<Schema<'SearchResult'>>(`/indexes/${encodeURIComponent(id)}/search`, { query, topK: 5 }),
}
