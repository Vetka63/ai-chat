import { describe, it, expect } from 'vitest'
import { conversationMarkdown } from './report'
import type { Schema } from '../../api/client'
describe('chat export', () => {
  it('includes source provenance and no unverified model text', () => {
    const d = { conversation: { title: 'Git', settings: { indexId: 'index' }, snapshotId: 'snapshot' }, memory: { facts: [{ layer: 'GOAL', key: 'goal', value: 'Сохранить файлы', sourceTurnId: 't1', quote: 'сохранить файлы' }] }, turns: [{ question: 'Как?', status: 'COMPLETED', issue: null, result: null, preparation: { rawJson: 'UNVERIFIED' }, includedHistoryTurnIds: [], omittedHistoryTurnCount: 2 }] } as unknown as Schema<'ConversationDetail'>
    const md = conversationMarkdown(d)
    expect(md).toContain('t1'); expect(md).toContain('сохранить файлы'); expect(md).toContain('вне prompt: 2'); expect(md).not.toContain('UNVERIFIED')
  })
})
