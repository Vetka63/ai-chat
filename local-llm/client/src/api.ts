export type Status = 'RUNNING' | 'COMPLETED' | 'TRUNCATED' | 'FAILED'
export interface GenerateRequest { model: string; prompt: string; temperature: number; thinking: boolean; maxOutputTokens: number | null; scenarioId: string | null }
export interface Generation { answer: string; thinking: string | null; doneReason: string; inputTokens: number | null; outputTokens: number | null; totalMilliseconds: number | null; loadMilliseconds: number | null; tokensPerSecond: number | null }
export interface Run { id: string; createdAt: string; request: GenerateRequest; status: Status; generation: Generation | null; elapsedMilliseconds: number | null; error: string | null }
export interface Summary { id: string; createdAt: string; prompt: string; model: string; status: Status }
export interface RuntimeInfo { connected: boolean; version: string | null; configuredModels: string[]; models: { name: string; sizeBytes: number; parameterSize: string | null; quantization: string | null }[]; error: string | null }
export interface Scenario { id: string; title: string; difficulty: string; prompt: string; expectation: string }
async function request<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(`/api/v1${path}`, { method: body === undefined ? 'GET' : 'POST', headers: body === undefined ? {} : { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok) { const error = await response.json().catch(() => null); throw new Error(error?.message ?? `Ошибка HTTP ${response.status}`) }
  return response.json() as Promise<T>
}
export const api = {
  runtime: () => request<RuntimeInfo>('/runtime'), scenarios: () => request<Scenario[]>('/scenarios'),
  runs: () => request<Summary[]>('/runs'), run: (id: string) => request<Run>(`/runs/${encodeURIComponent(id)}`),
  generate: (body: GenerateRequest) => request<Run>('/runs', body),
}
