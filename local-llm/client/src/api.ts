export type Status = 'RUNNING' | 'COMPLETED' | 'TRUNCATED' | 'FAILED'
export interface GenerateRequest { model: string; prompt: string; temperature: number; thinking: boolean; maxOutputTokens: number | null; scenarioId: string | null }
export interface Generation { answer: string; thinking: string | null; doneReason: string; inputTokens: number | null; outputTokens: number | null; totalMilliseconds: number | null; loadMilliseconds: number | null; tokensPerSecond: number | null }
export interface Run { id: string; createdAt: string; request: GenerateRequest; status: Status; generation: Generation | null; elapsedMilliseconds: number | null; error: string | null }
export interface Summary { id: string; createdAt: string; prompt: string; model: string; status: Status }
export interface RuntimeInfo { connected: boolean; version: string | null; configuredModels: string[]; models: { name: string; sizeBytes: number; parameterSize: string | null; quantization: string | null }[]; error: string | null }
export interface Scenario { id: string; title: string; difficulty: string; prompt: string; expectation: string }
export class HttpFailure extends Error {
  constructor(public status: number, message: string, public retryAfter: string | null = null) { super(message) }
}
export interface PrivateSettings { enabled: boolean; authenticated: boolean; contextTokens: number; contextBytes: number; generationsPerMinute: number; maxConcurrentGenerations: number; sessionSeconds: number }
export async function request<T>(path: string, body?: unknown, method?: string): Promise<T> {
  const response = await fetch(`/api/v1${path}`, { credentials: 'same-origin', method: method ?? (body === undefined ? 'GET' : 'POST'), headers: { 'X-Day30-Request': '1', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    if (response.status === 401 && path !== '/private/session') window.dispatchEvent(new Event('private-session-expired'))
    throw new HttpFailure(response.status, error?.message ?? `Ошибка HTTP ${response.status}`, response.headers.get('Retry-After'))
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
export const privateApi = {
  settings: () => request<PrivateSettings>('/private/session'),
  login: (password: string) => request('/private/session', { password }),
  logout: () => request('/private/session', undefined, 'DELETE'),
}
export const api = {
  runtime: () => request<RuntimeInfo>('/runtime'), scenarios: () => request<Scenario[]>('/scenarios'),
  runs: () => request<Summary[]>('/runs'), run: (id: string) => request<Run>(`/runs/${encodeURIComponent(id)}`),
  generate: (body: GenerateRequest) => request<Run>('/runs', body),
}
