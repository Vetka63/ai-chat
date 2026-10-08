import { request, type Generation } from '../../api'
export type Provider = 'LOCAL' | 'DEEPSEEK'
export interface Parameters { temperature: number; maxTokens: number; contextSize: number; thinking: boolean; topP: number; topK: number; seed: number }
export interface ExperimentRequest { id: string; provider: Provider; cloudModel: string; promptProfile: 'BASELINE' | 'SPECIALIZED'; skillEnabled: boolean; parameters: Parameters; caseIds: string[]; input: string; repetitions: number }
export interface ExpectedCard { service: string | null; environment: string | null; priority: string | null; started_at: string | null; root_cause: string | null }
export interface BenchmarkCase { id: string; title: string; split: string; input: string; expected: ExpectedCard }
export interface Identity { name: string; digest: string; quantization: string | null; parameterSize: string | null; fileBytes: number | null }
export interface Skill { id: string; version: string; title: string; sha256: string }
export interface Settings { localModel: string; runtimeConnected: boolean; installed: boolean; identity: Identity | null; cloudEnabled: boolean; cloudReady: boolean; cloudModels: string[]; skill: Skill }
export interface Resources { samples: number; peakAllocatedBytes: number | null; peakVramBytes: number | null; peakNonVramBytes: number | null; observedContextSize: number | null; placement: string | null; source: string }
export interface Evaluation { validJson: boolean; validSchema: boolean; matchedFields: number | null; totalFields: number | null; exactFields: boolean | null; mismatches: string[]; card: Record<string, unknown> | null }
export interface Item { id: string; caseId: string | null; title: string; repetition: number; input: string; expected: ExpectedCard | null; messages: { role: string; content: string }[]; promptSha256: string; status: string; generation: (Generation & { actualModel?: string | null }) | null; elapsedMilliseconds: number | null; resources: Resources | null; evaluation: Evaluation | null; error: string | null }
export interface Experiment { id: string; createdAt: string; request: ExperimentRequest; model: string; identity: Identity | null; runtimeVersion: string | null; promptVersion: string; skill: Skill | null; status: string; items: Item[]; note: string }
export interface Summary { id: string; createdAt: string; provider: Provider; model: string; profile: string; skillEnabled: boolean; status: string; finished: number; total: number }
export const optimizationApi = {
  settings: () => request<Settings>('/optimization/settings'), cases: () => request<BenchmarkCase[]>('/optimization/cases'),
  list: () => request<Summary[]>('/optimization/experiments'), get: (id: string) => request<Experiment>(`/optimization/experiments/${encodeURIComponent(id)}`),
  start: (body: ExperimentRequest) => request<Experiment>('/optimization/experiments', body),
  note: (id: string, note: string) => request<Experiment>(`/optimization/experiments/${encodeURIComponent(id)}/note`, { note }, 'PATCH'),
}
export const metric = (value: number | null | undefined, digits = 0) => value == null ? '—' : value.toLocaleString('ru-RU', { maximumFractionDigits: digits })
export const gib = (value: number | null | undefined) => value == null ? '—' : `${metric(value / 1024 ** 3, 2)} GiB`
export function score(e: Experiment) {
  const gold = e.items.filter(i => i.expected), successful = e.items.filter(i => i.status === 'COMPLETED')
  const times = successful.map(i => i.elapsedMilliseconds).filter((n): n is number => n != null).sort((a, b) => a - b)
  const memory = successful.map(i => i.resources?.peakVramBytes).filter((n): n is number => n != null)
  return { fields: gold.reduce((n, i) => n + (i.evaluation?.matchedFields ?? 0), 0), fieldTotal: gold.length * 5,
    exact: gold.filter(i => i.status === 'COMPLETED' && i.evaluation?.exactFields).length, goldTotal: gold.length,
    successful: successful.length, total: e.items.length, schema: e.items.filter(i => i.evaluation?.validSchema).length,
    medianMs: times.length ? times.length % 2 ? times[Math.floor(times.length / 2)]! : (times[times.length / 2 - 1]! + times[times.length / 2]!) / 2 : null,
    maxVram: memory.length ? Math.max(...memory) : null }
}
/** Для изолированного теста квантования входы/инструкции/настройки должны совпадать. */
export function comparisonWarnings(a: Experiment, b: Experiment): string[] {
  const warnings: string[] = []
  const key = (e: Experiment) => e.items.map(i => `${i.caseId ?? 'custom'}:${i.repetition}:${i.input}`).sort().join('\n')
  if (key(a) !== key(b)) warnings.push('Наборы обращений или число повторов различаются.')
  if (a.request.promptProfile !== b.request.promptProfile || a.skill?.sha256 !== b.skill?.sha256 || a.promptVersion !== b.promptVersion) warnings.push('Промпты/skills различаются: это не изолированный тест квантования.')
  if (JSON.stringify(a.request.parameters) !== JSON.stringify(b.request.parameters)) warnings.push('Настройки различаются: эффект нельзя приписать только квантованию.')
  if (a.request.provider !== b.request.provider) warnings.push('Разные провайдеры: отличаются токенизаторы, настройки и условия выполнения; облачные ресурсы неизвестны.')
  if (a.identity?.parameterSize && b.identity?.parameterSize && a.identity.parameterSize !== b.identity.parameterSize) warnings.push('Размеры моделей различаются: это не только квантование.')
  return warnings
}
