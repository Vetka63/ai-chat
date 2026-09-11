import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ChatThread from './ChatThread.vue'

describe('ChatThread', () => {
  it('scrolls to the bottom when a new message appears', async () => {
    const wrapper = mount(ChatThread, {
      props: { messages: [], agentName: 'Агент', sending: false },
    })
    const thread = wrapper.get('.thread').element
    Object.defineProperty(thread, 'scrollHeight', { configurable: true, value: 640 })

    await wrapper.setProps({
      messages: [{ role: 'user', content: 'Новое сообщение' }],
      sending: true,
    })
    await wrapper.vm.$nextTick()

    expect(thread.scrollTop).toBe(640)
    wrapper.unmount()
  })

  it('shows compression as one service event, not a duplicated message', () => {
    const messages = Array.from({ length: 16 }, (_, index) => ({ role: index % 2 ? 'assistant' : 'user', content: `message-${index}` }))
    const wrapper = mount(ChatThread, { props: { messages, runs: [{ id: 'summary-1', purpose: 'summary', user_index: 14, status: 'success', estimate: {}, pricing: {},
      compression: { segment_start: 1, segment_end: 4, retained_messages: 10, keep_last: 10, summarize_every: 4, revision: 1 } }] } })
    expect(wrapper.findAll('article.message')).toHaveLength(16)
    expect(wrapper.findAll('.summary-event')).toHaveLength(1)
    expect(wrapper.get('.summary-event').text()).toContain('ваш запрос №8 · сообщение истории №15')
    expect(wrapper.get('.summary-event').text()).toContain('№1–4')
    wrapper.unmount()
  })
})
