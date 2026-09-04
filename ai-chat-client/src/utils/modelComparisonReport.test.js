import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createModelComparisonReport,
  downloadModelComparisonReport,
  summarizeModelComparison,
} from './modelComparisonReport.js'

const results = [
  {
    id: 'weak', title: 'Слабая модель', provider: 'mistral', model: 'ministral-3b-2512',
    modelUrl: 'https://docs.mistral.ai/models/ministral-3-3b-25-12',
    pricingUrl: 'https://docs.mistral.ai/inference/pricing', pricingLabel: 'Mistral Standard',
    status: 'success', answer: 'Краткий ответ',
    metrics: { elapsedMs: 180, promptTokens: 100, completionTokens: 20, totalTokens: 120, estimatedCostUsd: 0.000012 },
  },
  {
    id: 'medium', title: 'Средняя модель', provider: 'deepseek', model: 'deepseek-v4-flash',
    modelUrl: 'https://api-docs.deepseek.com/quick_start/pricing/',
    pricingUrl: 'https://api-docs.deepseek.com/quick_start/pricing/', pricingLabel: 'DeepSeek Peak',
    status: 'success', answer: 'Подробный ответ',
    metrics: { elapsedMs: 250, promptTokens: 105, completionTokens: 40, totalTokens: 145, estimatedCostUsd: 0.0001 },
  },
]

const judgeResult = {
  winnerModelId: 'medium',
  winnerTitle: 'Средняя модель',
  model: 'deepseek-v4-pro',
  summary: 'Средняя модель точнее выполнила задачу.',
  metrics: { apiCalls: 1, totalTokens: 240, estimatedCostUsd: 0.0012 },
  evaluations: [{
    modelId: 'medium', title: 'Средняя модель', average: 9.25,
    scores: { accuracy: 10, instructionFollowing: 9, completeness: 9, clarity: 9 },
    strengths: 'Точность', weaknesses: 'Нет примера',
  }],
}

describe('modelComparisonReport', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('builds a Markdown report with answers, metrics, conclusion and official links', () => {
    const report = createModelComparisonReport({
      experimentId: 'models12-0000',
      task: 'Объясни бинарный поиск',
      results,
      ratings: { weak: 3, medium: 5 },
      winner: 'medium',
      conclusion: 'Средняя модель дала лучший баланс.',
      judgeResult,
    })

    expect(report).toContain('# День 5. Сравнение версий моделей')
    expect(report).toContain('[ministral-3b-2512](https://docs.mistral.ai/models/ministral-3-3b-25-12)')
    expect(report).toContain('Время ответа: 180 мс')
    expect(report).toContain('Лучшее качество: Средняя модель (deepseek-v4-flash)')
    expect(report).toContain('Средняя модель дала лучший баланс.')
    expect(report).toContain('## Оценка DeepSeek Pro')
    expect(report).toContain('Средняя модель точнее выполнила задачу.')
    expect(report).toContain('| Средняя модель | 9.25 | 10 | 9 | 9 | 9 |')
  })

  it('finds objective leaders and downloads an md file', () => {
    const leaders = summarizeModelComparison(results)
    expect(leaders.fastest.id).toBe('weak')
    expect(leaders.fewestTokens.id).toBe('weak')
    expect(leaders.cheapest.id).toBe('weak')

    const createObjectURL = vi.fn(() => 'blob:model-report')
    const revokeObjectURL = vi.fn()
    let downloadedFileName = ''
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function click() {
      downloadedFileName = this.download
    })

    downloadModelComparisonReport({ experimentId: 'models12-0000', task: 'Task', results })

    expect(downloadedFileName).toBe('day5-model-comparison-models12.md')
    expect(createObjectURL.mock.calls[0][0].type).toBe('text/markdown;charset=utf-8')
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:model-report')
  })
})
