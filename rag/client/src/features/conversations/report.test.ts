import { describe, it, expect } from 'vitest'
import { conversationMarkdown } from './report'
import type { Schema } from '../../api/client'
describe('chat export', () => {
  it('includes source provenance and no unverified model text', () => {
    const d = { conversation: { title: 'Git', settings: { indexId: 'index' }, snapshotId: 'snapshot' }, memory: { facts: [{ layer: 'GOAL', key: 'goal', value: 'Сохранить файлы', sourceTurnId: 't1', quote: 'сохранить файлы' }] }, turns: [{ question: 'Как?', status: 'COMPLETED', issue: null, result: null, preparation: { rawJson: 'UNVERIFIED' }, includedHistoryTurnIds: [], omittedHistoryTurnCount: 2 }] } as unknown as Schema<'ConversationDetail'>
    const md = conversationMarkdown(d)
    expect(md).toContain('t1'); expect(md).toContain('сохранить файлы'); expect(md).toContain('вне prompt: 2'); expect(md).not.toContain('UNVERIFIED')
  })
  it('includes one repair notice and keeps total conversation usage distinct from grounding subtotal', () => {
    const generation = { model: 'test', usage: null, rawJson: 'REJECTED DRAFT SECRET' }
    const result = { request: { question: 'Как?', indexId: 'index' }, snapshotId: 'snapshot', status: 'ANSWERED', answer: 'Проверенный итог.', claims: [], sources: [], issues: [], warnings: [], llmStagesAttempted: 4, totalMilliseconds: 4, totalUsage: { totalTokens: 40 }, repair: { originalGeneration: generation, originalSupportCheck: { status: 'REJECTED', generation, claims: [{ reason: 'REJECTED REASON SECRET' }] } } }
    const d = { conversation: { title: 'Git', settings: { indexId: 'index' }, snapshotId: 'snapshot' }, memory: { facts: [] }, turns: [{ question: 'Как?', result, llmStagesAttempted: 5, totalUsage: { totalTokens: 50 }, estimatedCost: { minimumUsd: .05, maximumUsd: .1 }, includedHistoryTurnIds: [], omittedHistoryTurnCount: 0 }] } as unknown as Schema<'ConversationDetail'>
    const md = conversationMarkdown(d)
    expect(md.match(/Исправление черновика · 1 попытка/g)).toHaveLength(1)
    expect(md).toContain('total API tokens=40')
    expect(md).toContain('Всего на сообщение: LLM-стадий=5; API tokens=50')
    expect(md).toContain('Общая оценка сообщения USD: 0.05–0.1')
    expect(md).not.toContain('API tokens=90')
    expect(md).not.toContain('REJECTED DRAFT SECRET'); expect(md).not.toContain('REJECTED REASON SECRET')
  })
})
