import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ContextPanel from './ContextPanel.vue'

describe('immutable context settings', () => {
  it('shows the saved summary strategy without edit controls', () => {
    const wrapper = mount(ContextPanel, { props: {
      settings: { mode: 'summary', keep_last: 10, summarize_every: 4 },
      estimate: { history_message_count: 14, unsummarized_old_messages: 4, messages_until_summary: 0, retained_message_count: 14, pending_summary: true },
    } })
    expect(wrapper.text()).toContain('Summary + хвост')
    expect(wrapper.text()).toContain('Зафиксировано')
    expect(wrapper.text()).toContain('Последних сообщений (N)10')
    expect(wrapper.text()).toContain('Порог достигнут')
    expect(wrapper.find('input[type=radio]').exists()).toBe(false)
    expect(wrapper.find('input[type=number]').exists()).toBe(false)
  })

  it('shows stored summary without rendering HTML from model', () => {
    const wrapper = mount(ContextPanel, { props: {
      settings: { mode: 'summary', keep_last: 10, summarize_every: 10 },
      summary: { text: '<script>bad()</script>', covered_messages: 10, revision: 2 },
    } })
    expect(wrapper.text()).toContain('<script>bad()</script>')
    expect(wrapper.find('script').exists()).toBe(false)
  })

  it('shows sticky facts and exact window statistics', () => {
    const wrapper = mount(ContextPanel, { props: {
      settings: { mode: 'sticky_facts', keep_last: 3, summarize_every: 10 },
      facts: { facts: { goal: 'Собрать ТЗ', budget: '500000' }, revision: 2, updated_from_message: 7, returned_model: 'deepseek' },
      estimate: { history_message_count: 8, retained_message_count: 3, discarded_message_count: 5, fact_count: 2 },
    } })
    expect(wrapper.text()).toContain('Собрать ТЗ')
    expect(wrapper.text()).toContain('500000')
    expect(wrapper.text()).toContain('Исключено из хвоста5')
  })

  it('emits checkpoint, two branches and branch switching', async () => {
    const conversation = { id: 'root', root_conversation_id: 'root', checkpoints: [{ id: 'cp-1', title: 'Выбор', message_count: 8 }] }
    const conversations = [conversation, { id: 'child', root_conversation_id: 'root', parent_conversation_id: 'root', branch_name: 'Ветка A' }]
    const wrapper = mount(ContextPanel, { props: {
      settings: { mode: 'branching', keep_last: 10, summarize_every: 10 }, conversation, conversations,
      hasConversation: true,
    } })
    const buttons = wrapper.findAll('button')
    await buttons.find(button => button.text().includes('Сохранить checkpoint')).trigger('click')
    await buttons.find(button => button.text().includes('Создать две ветки')).trigger('click')
    await buttons.find(button => button.text() === 'Ветка A').trigger('click')
    expect(wrapper.emitted('checkpoint')[0][0]).toBe('Варианты решения')
    expect(wrapper.emitted('branches')[0][0]).toEqual({ checkpointId: 'cp-1', names: ['Ветка A', 'Ветка B'] })
    expect(wrapper.emitted('select-branch')[0][0]).toBe('child')
  })
})
