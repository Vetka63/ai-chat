import { describe, expect, it } from 'vitest'
import { compressionDescription, requestNumber } from './compressionDisplay'

describe('compression explanations', () => {
  it('distinguishes request 8 from history message 15', () => {
    const messages = Array.from({ length: 15 }, (_, i) => ({ role: i % 2 ? 'assistant' : 'user' }))
    expect(requestNumber(messages, 14)).toBe(8)
    expect(requestNumber([{ role: 'user' }, { role: 'user' }], 1)).toBe(2)
  })
  it('describes exact saved ranges but does not claim failed attempts succeeded', () => {
    const run = { status: 'success', compression: { segment_start: 5, segment_end: 8, revision: 2, retained_messages: 10, keep_last: 10, summarize_every: 4 } }
    expect(compressionDescription(run)).toContain('Сжаты сообщения истории №5–8')
    expect(compressionDescription({ ...run, status: 'error' })).toContain('Попытка сжать')
    expect(compressionDescription({ ...run, status: 'error' })).not.toContain('Сжаты сообщения')
  })
  it('uses only recorded evidence for older events', () => {
    const run = { purpose: 'summary', user_index: 14, status: 'success' }
    expect(compressionDescription(run)).toContain('не сохранены')
    const text = compressionDescription(run, [{ purpose: 'dialogue', user_index: 14, estimate: { summarized_messages: 4 } }])
    expect(text).toContain('№1–4')
    expect(text).toContain('целиком передано 10')
    expect(text).toContain('Диапазон новой порции и настройки не сохранены')
  })
})
