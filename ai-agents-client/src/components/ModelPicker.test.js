import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ModelPicker from './ModelPicker.vue'

const models = [
  { id: 'flash', title: 'Flash', available: true, context_window: 1000000 },
  { id: 'pro', title: 'Pro', available: true, context_window: 1000000 },
  { id: 'mistral', title: 'Mistral', available: false },
]
describe('model picker beside the composer', () => {
  it('switches the next model and never submits the message form', async () => {
    const wrapper = mount(ModelPicker, { props: { models, modelId: 'flash' } })
    await wrapper.get('.model-trigger').trigger('click')
    const options = wrapper.findAll('[role="menuitemradio"]')
    expect(options[2].attributes('disabled')).toBeDefined()
    expect(options[1].attributes('type')).toBe('button')
    await options[1].trigger('click')
    expect(wrapper.emitted('model')).toEqual([['pro']])
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('supports keyboard navigation, Escape and outside click', async () => {
    const wrapper = mount(ModelPicker, { props: { models, modelId: 'flash' }, attachTo: document.body })
    await wrapper.get('.model-trigger').trigger('keydown', { key: 'ArrowDown' })
    const options = wrapper.findAll('[role="menuitemradio"]')
    expect(document.activeElement).toBe(options[0].element)
    await options[0].trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement).toBe(options[1].element)
    await options[1].trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement).toBe(options[0].element)
    await options[0].trigger('keydown', { key: 'Escape' })
    expect(document.activeElement).toBe(wrapper.get('.model-trigger').element)
    await wrapper.get('.model-trigger').trigger('click')
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('disables selection and closes the menu while sending', async () => {
    const wrapper = mount(ModelPicker, { props: { models, modelId: 'flash' } })
    await wrapper.get('.model-trigger').trigger('click')
    await wrapper.setProps({ busy: true })
    expect(wrapper.get('.model-trigger').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
