import { describe, expect, it } from 'vitest'
import { experimentMarkdown, usageText } from './report'
import type { Schema } from '../../api/client'

describe('experiment report', () => {
  it('keeps original question no context and unknown usage explicit', () => {
    const comparison: Schema<'ExperimentComparison'> = { request: { question: 'Вопрос', indexId: 'index', modes: ['FILTERED'], candidateTopK: 10, finalTopK: 5, similarityThreshold: 1, contextMaxCharacters: 16000, generateAnswers: true, maxOutputTokens: null }, snapshotId: 'snapshot', rewrite: null, rewriteError: null, results: [{ mode: 'FILTERED', status: 'NO_CONTEXT', message: 'Контекста нет', error: null, pipeline: { originalQuestion: 'Вопрос', searchQuery: 'Поиск', filterApplied: true, thresholdApplied: 1, rawCandidates: [], selectedCandidates: [], decisions: [], retrievalMilliseconds: 1, embeddingInputTokens: 2 }, answer: null, generationAttempted: false }], totalMilliseconds: 1, llmStagesAttempted: 0, totalUsage: null, estimatedCost: null, warnings: ['Cosine не вероятность'] }
    const md = experimentMarkdown(comparison)
    expect(md).toContain('NO_CONTEXT'); expect(md).toContain('Поисковый запрос: Поиск')
    expect(md).toContain('передано LLM=0'); expect(md).toContain('max output=не задан')
    expect(usageText(null)).toBe('Неизвестно')
  })
})
