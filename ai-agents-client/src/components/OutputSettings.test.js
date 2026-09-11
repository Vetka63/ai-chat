import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import OutputSettings from './OutputSettings.vue'

describe('optional output limit', () => {
  it('defaults to provider settings and enables 1200 explicitly', async () => {
    const wrapper = mount(OutputSettings, { props: { limit: null, model: { max_output_tokens: 131072 } } })
    expect(wrapper.find('input[type="range"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('max_tokens не отправляется')
    await wrapper.get('input[type="checkbox"]').setValue(true)
    expect(wrapper.emitted('change')).toEqual([[1200]])
    wrapper.unmount()
  })
  it('supports values above 8192 and can disable the limit', async () => {
    const wrapper = mount(OutputSettings, { props: { limit: 1200, model: { max_output_tokens: 131072 } } })
    await wrapper.get('input[type="range"]').setValue('9000')
    expect(wrapper.emitted('change').at(-1)).toEqual([9000])
    await wrapper.get('input[type="checkbox"]').setValue(false)
    expect(wrapper.emitted('change').at(-1)).toEqual([null])
    wrapper.unmount()
  })
  it('warns about smaller models and disables controls while sending', async () => {
    const wrapper = mount(OutputSettings, { props: { limit: 9000, model: { max_output_tokens: 4096 } } })
    expect(wrapper.text()).toContain('Лимит выше потолка')
    await wrapper.setProps({ busy: true })
    expect(wrapper.findAll('input').every(input => input.attributes('disabled') !== undefined)).toBe(true)
    wrapper.unmount()
  })

  it('updates the slider immediately when typing an exact value', async () => {
    const wrapper = mount(OutputSettings, { props: { limit: 1200, model: { max_output_tokens: 131072 },
      onChange: value => wrapper.setProps({ limit: value }) } })
    await wrapper.get('input[type="number"]').setValue('9000')
    expect(wrapper.get('input[type="range"]').element.value).toBe('9000')
    wrapper.unmount()
  })
})
