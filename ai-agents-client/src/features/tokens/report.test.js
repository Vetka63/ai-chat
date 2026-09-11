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
    expect(text).toContain('только вход, резерв неизвестен')
    expect(text).toContain('не задан, по умолчанию API')
  })
  it('exports the compression savings formula and result', () => {
    const conversation = { token_savings: {
      compared_dialogue_runs: 2, unknown_dialogue_runs: 0,
      full_prompt_tokens: 3000, compressed_prompt_tokens: 1400,
      gross_input_savings_tokens: 1600, summary_usage_tokens: 500,
      summary_runs: 1, unknown_summary_runs: 0,
      net_savings_tokens: 1100, net_savings_percent: 36.67,
      mixed_models: false, by_model: [],
    }, messages: [] }
    const text = markdownReport('Экономия', [], conversation)
    expect(text).toContain('## Эффект сжатия')
    expect(text).toContain('Чистая экономия ≈ 1100 токенов')
    expect(text).toContain('фактические API total_tokens')
  })
})
