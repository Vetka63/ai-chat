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
      ],
      ratings: {
        precise: { accuracy: 5, creativity: 2 },
        creative: { accuracy: 4, creativity: 5 },
      },
      diversity: 5,
      winner: 'creative',
      conclusion: 'Высокая температура дала более необычный ответ.',
    })

    expect(report).toContain('Temperature = 0 · Точный')
    expect(report).toContain('Temperature = 1.2 · Творческий')
    expect(report).toContain('temperature = 1.2 (Творческий)')
    expect(report).toContain('Разнообразие ответов: 5 / 5')
    expect(report).toContain('Высокая температура дала более необычный ответ.')
  })
})
