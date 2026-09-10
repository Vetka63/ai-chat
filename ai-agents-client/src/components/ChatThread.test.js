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
  })
})
