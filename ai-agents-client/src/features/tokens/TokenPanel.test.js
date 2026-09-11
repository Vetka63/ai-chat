import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import TokenPanel from './TokenPanel.vue'

const props = {
  models: [{ id: 'flash', title: 'Flash', available: true, context_window: 1000000, pricing: { source: 'https://example.com' } },
    { id: 'mistral', title: 'Mistral', available: true, context_window: 131072, pricing: { source: 'https://example.com' } }],
  modelId: 'flash', runs: [], busy: false,
}
describe('token panel', () => {
  it('warns without blocking when estimated context overflows', () => {
    const wrapper = mount(TokenPanel, { props: { ...props, estimate: { exceeds_context: true, occupancy_percent: 120, method: 'approx' } } })
    expect(wrapper.text()).toContain('Отправка разрешена')
    wrapper.unmount()
  })
  it('shows unknown usage rather than zero on provider error', () => {
    const wrapper = mount(TokenPanel, { props: { ...props, runs: [{ id: 'failed', status: 'error', requested_model: 'flash', usage: null, estimated_cost_usd: null }] } })
    expect(wrapper.text()).toContain('Без usage: 1')
    expect(wrapper.text()).toContain('—')
    expect(wrapper.text()).not.toContain('$0.000000')
    wrapper.unmount()
  })
  it('shows net token savings and explains when compression paid off', () => {
    const token_savings = {
      compared_dialogue_runs: 3, unknown_dialogue_runs: 0,
      full_prompt_tokens: 5000, compressed_prompt_tokens: 2400,
      gross_input_savings_tokens: 2600, summary_runs: 1,
      summary_usage_tokens: 600, unknown_summary_runs: 0,
      net_savings_tokens: 2000, net_savings_percent: 40,
      complete: true, mixed_models: false, by_model: [],
    }
    const wrapper = mount(TokenPanel, { props: { ...props, conversation: { token_savings } } })
    expect(wrapper.text()).toContain('Чистая экономия ≈')
    expect(wrapper.text()).toContain('2 000')
    expect(wrapper.text()).toContain('Сжатие окупилось')
    expect(wrapper.text()).toContain('40%')
    wrapper.unmount()
  })
  it('does not claim savings when summary usage is unknown', () => {
    const token_savings = {
      compared_dialogue_runs: 1, unknown_dialogue_runs: 0,
      full_prompt_tokens: 1000, compressed_prompt_tokens: 400,
      gross_input_savings_tokens: 600, summary_runs: 1,
      summary_usage_tokens: 0, unknown_summary_runs: 1,
      net_savings_tokens: null, net_savings_percent: null,
      complete: false, mixed_models: false, by_model: [],
    }
    const wrapper = mount(TokenPanel, { props: { ...props, conversation: { token_savings } } })
    expect(wrapper.text()).toContain('Чистый итог неизвестен')
    expect(wrapper.text()).toContain('сжатий — 1')
    wrapper.unmount()
  })
})
