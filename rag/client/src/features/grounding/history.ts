import contract from '../../../../api/openapi.json'
import type { Schema } from '../../api/client'

/** Локальный журнал браузера: не серверная история чата и не повторная проверка ответа. */
export interface GroundingHistoryEntry {
  id: string
  savedAt: string
  origin: 'request' | 'import'
  fileName?: string
  question: string
  indexId: string
  status: Schema<'GroundedStatus'>
}
export interface SavedGroundingResult { entry: GroundingHistoryEntry; result: Schema<'GroundedResult'> }
export const MAX_IMPORT_BYTES = 20 * 1024 * 1024
const databaseName = 'rag-grounding-history-v1'
type JsonSchema = { $ref?: string; nullable?: boolean; allOf?: JsonSchema[]; type?: string; enum?: unknown[]; required?: string[]; properties?: Record<string, JsonSchema>; items?: JsonSchema; minimum?: number; maximum?: number; minLength?: number; maxLength?: number }
const schemas = contract.components.schemas as Record<string, JsonSchema>

/** Проверяет структуру по тому же OpenAPI, что и клиентские типы, но НЕ достоверность импортированного ответа. */
function matches(value: unknown, schema: JsonSchema, depth = 0): boolean {
  if (depth > 40) return false
  if (value === null) return schema.nullable === true
  if (schema.$ref) {
    const name = schema.$ref.replace('#/components/schemas/', '')
    return !!schemas[name] && matches(value, schemas[name]!, depth + 1)
  }
  if (schema.allOf && !schema.allOf.every(s => matches(value, s, depth + 1))) return false
  if (schema.enum && !schema.enum.includes(value)) return false
  switch (schema.type) {
    case 'object': {
      if (!value || typeof value !== 'object' || Array.isArray(value)) return false
      const record = value as Record<string, unknown>
      return (schema.required ?? []).every(key => Object.hasOwn(record, key)) && Object.entries(schema.properties ?? {}).every(([key, child]) => !Object.hasOwn(record, key) || matches(record[key], child, depth + 1))
    }
    case 'array': return Array.isArray(value) && !!schema.items && value.every(item => matches(item, schema.items!, depth + 1))
    case 'string': return typeof value === 'string' && (schema.minLength === undefined || value.length >= schema.minLength) && (schema.maxLength === undefined || value.length <= schema.maxLength)
    case 'boolean': return typeof value === 'boolean'
    case 'integer':
    case 'number': return typeof value === 'number' && Number.isFinite(value) && (schema.type !== 'integer' || Number.isInteger(value)) && (schema.minimum === undefined || value >= schema.minimum) && (schema.maximum === undefined || value <= schema.maximum)
    default: return !!schema.allOf || !!schema.enum
  }
}

export function parseGroundingImport(text: string): Schema<'GroundedResult'> {
  if (new Blob([text]).size > MAX_IMPORT_BYTES) throw new Error('Файл больше 20 МБ. Импортируйте один JSON-ответ, а не общий отчёт.')
  let value: unknown
  try { value = JSON.parse(text) } catch { throw new Error('Файл не является корректным JSON.') }
  if (!matches(value, schemas.GroundedResult!)) throw new Error('Ожидается один JSON-ответ дня 24 (GroundedResult), экспортированный приложением. Структура файла не совпала с контрактом.')
  const result = value as Schema<'GroundedResult'>
  const requestFields = ['candidateTopK', 'finalTopK', 'similarityThreshold', 'contextMaxCharacters', 'useRewrite']
  if (!requestFields.every(key => Object.hasOwn(result.request, key))) throw new Error('В JSON отсутствуют фактические настройки исходного запроса.')
  if (result.status !== 'ANSWERED' && (result.claims.length || result.sources.length)) throw new Error('Отклонённый ответ не должен содержать публичные утверждения и источники.')
  if (result.status === 'ANSWERED' && (!result.claims.length || !result.sources.length || result.claims.some(c => !c.citations.length))) throw new Error('У принятого ответа отсутствуют утверждения или цитаты.')
  for (const claim of result.claims) for (const citation of claim.citations) {
    if (citation.canonicalStart < 0 || citation.startInChunk < 0 || citation.canonicalEndExclusive - citation.canonicalStart !== citation.quote.length || citation.endInChunkExclusive - citation.startInChunk !== citation.quote.length) throw new Error('В JSON некорректные координаты цитаты.')
  }
  return result
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) { reject(new Error('IndexedDB недоступна. Скачайте JSON, чтобы не потерять результат.')); return }
    const request = indexedDB.open(databaseName, 1)
    request.onupgradeneeded = () => {
      request.result.createObjectStore('entries', { keyPath: 'id' })
      request.result.createObjectStore('results')
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
    request.onblocked = () => reject(new Error('Хранилище результатов занято другой вкладкой.'))
  })
}

async function transact<T>(stores: string[], mode: IDBTransactionMode, operation: (transaction: IDBTransaction) => IDBRequest<T>): Promise<T> {
  const db = await openDatabase()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(stores, mode)
    let request: IDBRequest<T>
    try { request = operation(tx) } catch (e) { db.close(); reject(e); return }
    tx.oncomplete = () => { db.close(); resolve(request.result) }
    tx.onabort = tx.onerror = () => { db.close(); reject(tx.error ?? new Error('Не удалось сохранить историю. Скачайте JSON результата.')) }
  })
}

export async function saveGroundingResult(result: Schema<'GroundedResult'>, origin: GroundingHistoryEntry['origin'], fileName?: string): Promise<GroundingHistoryEntry> {
  const entry: GroundingHistoryEntry = { id: crypto.randomUUID(), savedAt: new Date().toISOString(), origin, fileName, question: result.request.question, indexId: result.request.indexId, status: result.status }
  // JSON-копия снимает Vue Proxy. Сохраняем весь trace, включая отклонённые стадии, без обрезания.
  const copy = JSON.parse(JSON.stringify(result)) as Schema<'GroundedResult'>
  await transact(['entries', 'results'], 'readwrite', tx => { tx.objectStore('results').add(copy, entry.id); return tx.objectStore('entries').add(entry) })
  return entry
}
export async function listGroundingHistory(): Promise<GroundingHistoryEntry[]> {
  const entries = await transact<GroundingHistoryEntry[]>(['entries'], 'readonly', tx => tx.objectStore('entries').getAll())
  return entries.sort((a, b) => b.savedAt.localeCompare(a.savedAt))
}
export async function loadGroundingResult(id: string): Promise<Schema<'GroundedResult'>> {
  const result = await transact<Schema<'GroundedResult'> | undefined>(['results'], 'readonly', tx => tx.objectStore('results').get(id))
  if (!result) throw new Error('Сохранённый результат не найден.')
  return result
}
