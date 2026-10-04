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
  })
})
