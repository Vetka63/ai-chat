import type { components } from './schema'
export type Schema<K extends keyof components['schemas']> = components['schemas'][K]

/** Одна HTTP-точка входа; ошибки API отображаются без потери сообщения backend. */
export async function request<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(`/api/v1${path}`, { method: body === undefined ? 'GET' : 'POST', headers: body === undefined ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok) {
    const error = await response.json().catch(() => null) as Schema<'ApiError'> | null
    throw new Error(error?.message ?? `Ошибка HTTP ${response.status}`)
  }
  return response.json() as Promise<T>
}

export const api = {
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
