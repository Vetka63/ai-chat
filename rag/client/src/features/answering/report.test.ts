import { describe, expect, it } from 'vitest'
import { answersMarkdown } from './report'
import type { Schema } from '../../api/client'

describe('day22 report', () => {
  it('exports actual metrics unknown usage and truncation without invented score', () => {
    const result: Schema<'AnswerResult'> = { id: 'id', createdAt: 'now', question: 'Вопрос', mode: 'BASELINE', model: 'model', temperature: 0, thinking: 'disabled', answer: 'Ответ', finishReason: 'length', truncated: true, maxOutputTokens: null, totalMilliseconds: 20, generationMilliseconds: 20, usage: null, estimatedCost: null, context: { indexId: null, snapshotId: null, retrievedCount: 0, included: [], omittedChunkIds: [], textCharacters: 0, maxCharacters: 16000, retrievalMilliseconds: 0, embeddingInputTokens: null }, messages: [], warnings: ['Неполный ответ'] }
    const md = answersMarkdown([result])
    expect(md).toContain('Без RAG')
    expect(md).toContain('Usage отсутствует')
    expect(md).toContain('не задан приложением')
    expect(md).toContain('неполный ответ: true')
    expect(md).toContain('Ручное сравнение')
  })
})
