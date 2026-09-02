import { describe, expect, it } from 'vitest'
import {
  combineMetrics,
  createExperimentSnapshot,
  renderExperimentMarkdown,
} from './experimentReport.js'

describe('experiment report', () => {
  it('aggregates metrics and creates a self-contained current snapshot', () => {
    const result = {
      strategy: 'direct',
      title: 'Прямой ответ',
      answer: '42',
      metrics: {
        apiCalls: 1,
        elapsedMs: 120,
        apiDurationMs: 110,
        promptTokens: 20,
        completionTokens: 5,
        totalTokens: 25,
        estimatedCostUsd: 0.00002,
      },
    }
    const snapshot = createExperimentSnapshot({
      experimentId: 'abcd1234-rest',
      profile: { id: 'day3-reasoning', name: 'День 3' },
      task: 'Найди ответ',
      results: [result],
      ratings: { direct: { correctness: 5, clarity: 4 } },
      winner: 'direct',
      evaluationComment: 'Проверено',
      judgeResult: null,
      generatedAt: '2026-09-02T00:00:00.000Z',
    })

    expect(snapshot.persistence).toBe('none')
    expect(snapshot.metrics.solutions.totalTokens).toBe(25)
    expect(snapshot.manualEvaluation.ratings[0].average).toBe(4.5)
    expect(renderExperimentMarkdown(snapshot))
      .toContain('# День 3 — сравнение способов рассуждения')
      .toContain('Победитель: **Прямой ответ**')
      .toContain('Хранение истории: отключено')
  })

  it('keeps cost unavailable until prices are configured', () => {
    expect(combineMetrics([
      { apiCalls: 1, totalTokens: 10, estimatedCostUsd: null },
      { apiCalls: 2, totalTokens: 20 },
    ])).toMatchObject({
      apiCalls: 3,
      totalTokens: 30,
      estimatedCostUsd: null,
    })
  })
})
