import { describe, expect, it } from 'vitest'
import type { Schema } from '../../api/client'
import { parseGroundingImport, listGroundingHistory, MAX_IMPORT_BYTES } from './history'

function fixture(): Schema<'GroundedResult'> {
  const source = { chunkId: 'chunk', documentId: 'document', source: 'book/stash.asc', title: 'Припрятывание', section: 'Раздел' }
  return {
    request: { question: 'Вопрос', indexId: 'index', candidateTopK: 20, finalTopK: 10, similarityThreshold: .6, contextMaxCharacters: 32000, useRewrite: false, maxOutputTokens: null },
    snapshotId: 'snapshot', status: 'ANSWERED', answer: 'Ответ', clarification: null,
    claims: [{ text: 'Ответ', citations: [{ source, quote: 'Цитата', startInChunk: 2, endInChunkExclusive: 8, canonicalStart: 4, canonicalEndExclusive: 10 }] }], sources: [source], issues: [], retrieval: null, rewrite: null, generation: null,
    llmStagesAttempted: 0, totalUsage: null, estimatedCost: null, totalMilliseconds: 1, warnings: [],
  }
}
describe('imported grounded results: format is not authenticity', () => {
  it('preserves a complete exported result without modifying its text or status', () => {
    const input = fixture()
    expect(parseGroundingImport(JSON.stringify(input))).toEqual(input)
  })
  it('accepts a refusal without inventing citations', () => {
    const input = { ...fixture(), status: 'UNKNOWN', claims: [], sources: [], clarification: 'Уточните вопрос' }
    expect(parseGroundingImport(JSON.stringify(input)).status).toBe('UNKNOWN')
  })
  it('rejects invalid JSON, aggregate reports, unknown status and unsafe missing nested fields', () => {
    expect(() => parseGroundingImport('{')).toThrow('JSON')
    expect(() => parseGroundingImport(JSON.stringify({ cases: [fixture()] }))).toThrow('GroundedResult')
    expect(() => parseGroundingImport(JSON.stringify({ ...fixture(), status: 'SUCCESS' }))).toThrow('контрактом')
    const value = fixture(); value.claims[0]!.citations[0]!.source = null as never
    expect(() => parseGroundingImport(JSON.stringify(value))).toThrow('контрактом')
    expect(() => parseGroundingImport(JSON.stringify({ ...fixture(), generation: { usage: null } }))).toThrow('контрактом')
  })
  it('rejects malformed coordinates, absent request configuration and public claims for rejected drafts', () => {
    const value = fixture(); value.claims[0]!.citations[0]!.canonicalStart = -1
    expect(() => parseGroundingImport(JSON.stringify(value))).toThrow('координаты')
    expect(() => parseGroundingImport(JSON.stringify({ ...fixture(), request: { question: 'q', indexId: 'i' } }))).toThrow('настройки')
    expect(() => parseGroundingImport(JSON.stringify({ ...fixture(), status: 'INVALID_EVIDENCE' }))).toThrow('публичные')
    expect(() => parseGroundingImport(JSON.stringify({ ...fixture(), sources: [] }))).toThrow('отсутствуют')
  })
  it('reports unavailable browser storage instead of silently losing history', async () => {
    await expect(listGroundingHistory()).rejects.toThrow('IndexedDB')
  })
  it('rejects an oversized individual file before attempting JSON parsing', () => {
    expect(() => parseGroundingImport(' '.repeat(MAX_IMPORT_BYTES + 1))).toThrow('больше 20 МБ')
  })
})
