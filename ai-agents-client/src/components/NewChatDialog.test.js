import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import NewChatDialog from './NewChatDialog.vue'

describe('new chat configuration', () => {
  it('creates a chat with the selected immutable strategy', async () => {
    const wrapper = mount(NewChatDialog, { props: { open: true } })
    await wrapper.get('.dialog-title input').setValue('Сбор требований')
    await wrapper.get('input[value="sticky_facts"]').setValue(true)
    const window = wrapper.get('.context-fields input[type="number"]')
    expect(window.attributes('step')).toBe('1')
    await window.setValue(3)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')[0][0]).toEqual({
      title: 'Сбор требований',
      contextSettings: { mode: 'sticky_facts', keep_last: 3, summarize_every: 10 },
    })
    expect(wrapper.text()).toContain('Стратегия фиксируется после создания')
  })

  it('resets the draft when opened again', async () => {
    const wrapper = mount(NewChatDialog, { props: { open: true } })
    await wrapper.get('.dialog-title input').setValue('Черновик')
    await wrapper.get('input[value="branching"]').setValue(true)
    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })
    expect(wrapper.get('.dialog-title input').element.value).toBe('Новый чат')
    expect(wrapper.get('input[value="full"]').element.checked).toBe(true)
  })
})
