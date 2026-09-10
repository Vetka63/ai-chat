import { describe, expect, it } from 'vitest'
import { totals, money, markdownReport } from './report'
describe('token report', () => {
  it('does not turn unavailable usage into free requests', () => {
    expect(totals([{ usage: { total_tokens: 25 }, estimated_cost_usd: '0.1' }, { usage: null }])).toEqual({ tokens: 25, cost: .1, known: 1, unknown: 1 })
    expect(money(null)).toBe('неизвестно')
  })
  it('exports price source and error code', () => {
    const text = markdownReport('Тест', [{ requested_model: 'test', status: 'error', error_code: 'rate_limit',
      estimate: { history_tokens: 12, current_message_tokens: 3, prompt_tokens: 20, method: 'approx' },
      pricing: { source: 'https://example.com/pricing', checked_at: '2026-09-10' } }])
    expect(text).toContain('rate_limit')
    expect(text).toContain('https://example.com/pricing')
    expect(text).toContain('неизвестно')
  })
})
