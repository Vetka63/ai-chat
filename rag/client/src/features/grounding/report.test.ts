import { describe, expect, it } from 'vitest'
import type { Schema } from '../../api/client'
import { groundedMarkdown } from './report'

describe('grounded report', () => {
  it('exports only public validated content and preserves unknown metrics', () => {
    const r: Schema<'GroundedResult'> = {
      request: { question: 'Вопрос', indexId: 'idx', candidateTopK: 10, finalTopK: 5, similarityThreshold: .65, contextMaxCharacters: 16000, useRewrite: false, maxOutputTokens: null },
      snapshotId: 'snapshot', status: 'INVALID_EVIDENCE', answer: 'Ответ не прошёл проверку', clarification: null, claims: [], sources: [],
      issues: [{ code: 'unknown_evidence_id', message: 'Неизвестный ID', claimIndex: 0, citationIndex: 0 }], retrieval: null, rewrite: null,
      generation: { model: 'deepseek-flash', finishReason: 'stop', milliseconds: 10, usage: null, estimatedCost: null, messages: [], rawJson: 'СЕКРЕТНОЕ НЕПРОВЕРЕННОЕ УТВЕРЖДЕНИЕ' },
      llmStagesAttempted: 1, totalUsage: null, estimatedCost: null, totalMilliseconds: 10, warnings: ['Точная цитата не доказывает смысл'],
    }
    const md = groundedMarkdown(r)
    expect(md).toContain('INVALID_EVIDENCE'); expect(md).toContain('неизвестно')
    expect(md).toContain('unknown_evidence_id'); expect(md).toContain('max output=не задан')
    expect(md).not.toContain('СЕКРЕТНОЕ НЕПРОВЕРЕННОЕ УТВЕРЖДЕНИЕ')
    expect(md).not.toContain('Исправление черновика')
  })
  it('reports repair metadata and actual totals without publishing the rejected draft or judge reasons', () => {
    const generation: Schema<'GroundingGeneration'> = { model: 'draft-model', finishReason: 'stop', milliseconds: 10, usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15, cacheHitTokens: null, cacheMissTokens: null }, estimatedCost: null, messages: [], rawJson: 'ОШИБОЧНЫЙ ИСХОДНЫЙ ВЫВОД' }
    const r: Schema<'GroundedResult'> = {
      request: { question: 'Вопрос', indexId: 'idx', candidateTopK: 10, finalTopK: 5, similarityThreshold: .65, contextMaxCharacters: 16000, useRewrite: false, maxOutputTokens: null },
      snapshotId: 'snapshot', status: 'ANSWERED', answer: 'Итоговый проверенный вывод.', clarification: null,
      claims: [{ text: 'Итоговый проверенный вывод.', citations: [] }], sources: [], issues: [], retrieval: null, rewrite: null,
      generation: { ...generation, rawJson: '{}' }, supportCheck: { status: 'PASSED', claims: [], issues: [], generation: { ...generation, model: 'judge-model', rawJson: '{}' } },
      repair: { originalGeneration: generation, originalSupportCheck: { status: 'REJECTED', claims: [{ claimIndex: 0, verdict: 'UNSUPPORTED', reason: 'НЕПРОВЕРЕННАЯ ПРИЧИНА' }], issues: [], generation: { ...generation, model: 'judge-model', rawJson: 'НЕПРОВЕРЕННЫЙ JUDGE' } } },
      llmStagesAttempted: 4, totalUsage: { promptTokens: 40, completionTokens: 20, totalTokens: 60, cacheHitTokens: null, cacheMissTokens: null }, estimatedCost: { minimumUsd: .01, maximumUsd: .02, source: 'test', verifiedOn: '2026-10-04', note: 'Estimate' }, totalMilliseconds: 40, warnings: [],
    }
    const md = groundedMarkdown(r)
    expect(md).toContain('Итоговый проверенный вывод.')
    expect(md).toContain('Исправление черновика · 1 попытка')
    expect(md).toContain('Первая проверка=REJECTED')
    expect(md).toContain('LLM-стадий=4'); expect(md).toContain('total API tokens=60')
    expect(md).toContain('Общая оценка USD: 0.01–0.02')
    expect(md).not.toContain('ОШИБОЧНЫЙ ИСХОДНЫЙ ВЫВОД')
    expect(md).not.toContain('НЕПРОВЕРЕННАЯ ПРИЧИНА'); expect(md).not.toContain('НЕПРОВЕРЕННЫЙ JUDGE')
    const failed = groundedMarkdown({ ...r, status: 'ERROR', answer: 'Ошибка стадии.', claims: [], generation: null, supportCheck: null, llmStagesAttempted: 3, totalUsage: null, estimatedCost: null })
    expect(failed).toContain('Исправление черновика · 1 попытка')
    expect(failed).toContain('total API tokens=неизвестно')
    expect(failed).not.toContain('ОШИБОЧНЫЙ ИСХОДНЫЙ ВЫВОД')
  })
})
