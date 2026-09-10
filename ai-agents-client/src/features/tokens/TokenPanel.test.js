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
})
