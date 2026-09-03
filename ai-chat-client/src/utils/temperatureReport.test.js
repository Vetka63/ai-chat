import { describe, expect, it } from 'vitest'
import { createTemperatureReport } from './temperatureReport.js'

describe('createTemperatureReport', () => {
  it('includes all temperatures, ratings and the selected winner', () => {
    const report = createTemperatureReport({
      experimentId: 'experiment-1',
      task: 'Придумай название продукта',
      results: [
        { id: 'precise', temperature: 0, title: 'Точный', answer: 'Фокус' },
        { id: 'creative', temperature: 1.2, title: 'Творческий', answer: 'Лунный клевер' },
        { id: 'experimental', temperature: 2, title: 'Экспериментальный', answer: 'Космический гербарий' },
      ],
      ratings: {
        precise: { accuracy: 5, creativity: 2 },
        creative: { accuracy: 4, creativity: 5 },
        experimental: { accuracy: 2, creativity: 5 },
      },
      diversity: 5,
      winner: 'creative',
      conclusion: 'Высокая температура дала более необычный ответ.',
      judgeResult: {
        model: 'deepseek-v4-pro',
        winnerTemperature: 1.2,
        winnerTitle: 'Творческий',
        diversityScore: 8,
        diversityExplanation: 'Ответы заметно различаются.',
        explanation: 'Творческий вариант лучше сочетает качество и оригинальность.',
        evaluations: [
          {
            temperature: 1.2,
            title: 'Творческий',
            average: 9,
            scores: { accuracy: 8, creativity: 10, instructionFollowing: 9 },
            strengths: 'Оригинальность',
            weaknesses: 'Риск неточности',
          },
        ],
      },
    })

    expect(report).toContain('Temperature = 0 · Точный')
    expect(report).toContain('Temperature = 1.2 · Творческий')
    expect(report).toContain('Temperature = 2 · Экспериментальный')
    expect(report).toContain('temperature = 1.2 (Творческий)')
    expect(report).toContain('Разнообразие ответов: 5 / 5')
    expect(report).toContain('Высокая температура дала более необычный ответ.')
    expect(report).toContain('## Оценка AI-судьи')
    expect(report).toContain('Модель: deepseek-v4-pro')
    expect(report).toContain('Разнообразие: 8 / 10')
  })
})
