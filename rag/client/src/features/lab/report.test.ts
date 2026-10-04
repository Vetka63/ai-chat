import { describe, expect, it } from 'vitest'
import { comparisonMarkdown } from './report'
import type { Schema } from '../../api/client'

describe('comparison report', () => {
  it('does not pretend diagnostic search is an LLM answer', () => {
    const comparison: Schema<'IndexComparison'> = { comparable: false, notes: ['Корпус отличается'], indexes: [] }
    const text = comparisonMarkdown(comparison, [])
    expect(text).toContain('сравнение корпуса/модели: нет')
    expect(text).toContain('не оценка ответов LLM')
    expect(text).toContain('Размеры — символы, не токены')
    expect(text).toContain('Корпус отличается')
  })
})
