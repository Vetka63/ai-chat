import { request, type Status } from '../../api'
export type Mode = 'LOCAL' | 'DEEPSEEK' | 'COMPARE'
export interface DocumentInfo { id: string; title: string; filename: string; characters: number; sha256: string }
export interface RagIndex { id: string; status: 'BUILDING' | 'READY' | 'FAILED' | 'INTERRUPTED'; processed: number; total: number; documentIds: string[]; error: string | null; space: { model: string; digest: string; dimension: number } | null }
export interface Chunk { id: string; title: string; source: string; section: string; text: string; start: number; endExclusive: number }
export interface Hit { ref: string; similarity: number; chunk: Chunk; denseRank?: number; lexicalRank?: number; fusionScore?: number; origin?: string }
export interface RagRequest { requestId: string; indexId: string; question: string; mode: Mode; localModel: string; cloudModel: string; topK: number; threshold: number; contextCharacters: number; temperature: number; thinking: boolean; maxOutputTokens: number | null; documentIds: string[] }
export interface ModelAnswer { provider: string; requestedModel: string; actualModel: string | null; status: Status; answer: string | null; milliseconds: number | null; inputTokens: number | null; outputTokens: number | null; loadMilliseconds: number | null; tokensPerSecond: number | null; citationStatus: string; citedRefs: string[]; error: string | null; startedAt: string | null; finishedAt: string | null }
export interface Experiment { id: string; createdAt: string; request: RagRequest; status: string; retrieval: { hits: Hit[]; included: Hit[]; milliseconds: number; contextCharacters: number; promptSha256: string; embeddingTokens: number | null; embeddingCacheHit?: boolean } | null; answers: ModelAnswer[]; milliseconds: number | null; error: string | null; conclusion?: string | null }
export interface ExperimentInfo { id: string; question: string; mode: Mode; status: string }
export interface Settings { cloudEnabled: boolean; cloudReady: boolean; cloudModels: string[]; embeddingModel: string; contextTokens: number }
export const ragApi = {
  settings: () => request<Settings>('/rag/settings'), documents: () => request<DocumentInfo[]>('/rag/documents'),
  indexes: () => request<RagIndex[]>('/rag/indexes'), index: (id: string) => request<RagIndex>(`/rag/indexes/${encodeURIComponent(id)}`),
  build: () => request<RagIndex>('/rag/indexes', { documentIds: [], chunkCharacters: 1400, overlapCharacters: 180 }),
  experiments: () => request<ExperimentInfo[]>('/rag/experiments'),
  experiment: (id: string) => request<Experiment>(`/rag/experiments/${encodeURIComponent(id)}`),
  start: (body: RagRequest) => request<Experiment>('/rag/experiments', body),
  conclude: (id: string, text: string) => request<Experiment>(`/rag/experiments/${encodeURIComponent(id)}/conclusion`, {text}, 'PATCH'),
  async upload(file: File, title: string, startMarker: string) {
    const body = new FormData(); body.append('file', file); body.append('title', title)
    if (startMarker.trim()) body.append('startMarker', startMarker.trim())
    const response = await fetch('/api/v1/rag/documents', { method: 'POST', body })
    if (!response.ok) { const error = await response.json().catch(() => null); throw new Error(error?.message ?? `Ошибка загрузки ${response.status}`) }
    return response.json() as Promise<DocumentInfo>
  },
}
export const isRunning = (status?: string) => status === 'PREPARING' || status === 'RUNNING'
export function metric(value: number | null | undefined, suffix = '') { return value == null ? '—' : `${Math.round(value).toLocaleString('ru-RU')}${suffix}` }
