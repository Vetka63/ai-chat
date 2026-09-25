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

  it('shows the MCP call between the user question and the answer', () => {
    const wrapper = mount(ChatThread, { props: {
      messages: [{ role: 'user', content: 'Игры про космос?' }, { role: 'assistant', content: 'Звёздные тропы' }],
      toolEvents: [{ id: 'event-1', user_index: 0, server_id: 'games-mock', tool_name: 'search_games',
        status: 'success', arguments: { query: 'космос' }, result: { games: [{ title: 'Звёздные тропы' }] } }],
    } })
    expect(wrapper.findAll('article.message')).toHaveLength(2)
    expect(wrapper.findAll('article.tool-event')).toHaveLength(1)
    expect(wrapper.text()).toContain('games-mock / search_games')
    expect(wrapper.text()).toContain('Результат получен')
  })

  it('shows the three report stages and a Markdown download action', () => {
    const wrapper = mount(ChatThread, { props: {
      mcpPipeline: true,
      messages: [{ role: 'user', content: 'Создай отчёт' }, { role: 'assistant', content: 'Готово' }],
      toolEvents: [
        { id: 'search-1', user_index: 0, server_id: 'game-reports', tool_name: 'search_games',
          status: 'success', arguments: { query: 'космос' }, result: { query: 'космос', games: [] } },
        { id: 'summary-1', user_index: 0, server_id: 'game-reports', tool_name: 'summarize_games',
          status: 'success', arguments: { search_result: { query: 'космос', games: [] } },
          result: { query: 'космос', game_count: 0, markdown: '# Космос' } },
        { id: 'save-1', user_index: 0, server_id: 'game-reports', tool_name: 'save_report',
          status: 'success', arguments: { summary_result: { query: 'космос', game_count: 0, markdown: '# Космос' } },
          result: { file_name: 'report-1.md', game_count: 0, report_markdown: '# Космос' } },
      ],
    } })
    expect(wrapper.findAll('article.tool-event')).toHaveLength(3)
    expect(wrapper.text()).toContain('Поиск')
    expect(wrapper.text()).toContain('Обработка')
    expect(wrapper.text()).toContain('Сохранение')
    expect(wrapper.get('button.report-button').text()).toContain('Скачать')
  })
})
