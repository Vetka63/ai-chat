import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ChatSidebar from './ChatSidebar.vue'

describe('Day 10 chat tree', () => {
  it('places branches below their parent and marks context strategies', async () => {
    const conversations = [
      { id: 'other', title: 'Обычный', context_settings: { mode: 'sliding_window' } },
      { id: 'branch-b', title: 'B', branch_name: 'Микросервисы', parent_conversation_id: 'root', context_settings: { mode: 'branching' } },
      { id: 'root', title: 'Исходное ТЗ', context_settings: { mode: 'branching' } },
      { id: 'branch-a', title: 'A', branch_name: 'Монолит', parent_conversation_id: 'root', context_settings: { mode: 'branching' } },
    ]
    const wrapper = mount(ChatSidebar, { props: {
      agents: [{ id: 'dialogue', name: 'Диалоговый агент' }, { id: 'mcp_games', name: 'Игровой агент' }],
      selectedAgent: 'dialogue', conversations, selectedConversation: 'branch-a',
    } })
    expect(wrapper.findAll('.agent-folder')).toHaveLength(2)
    expect(wrapper.get('.agent-folder[aria-expanded="true"]').text()).toContain('Диалоговый агент')
    const labels = wrapper.findAll('.conversation-title strong').map(item => item.text())
    expect(labels).toEqual(['Обычный', 'Исходное ТЗ', 'Микросервисы', 'Монолит'])
    expect(wrapper.findAll('.branch-child')).toHaveLength(2)
    expect(wrapper.findAll('.context-mode-badge').map(item => item.text())).toEqual(['⇥', '⑂', '⑂', '⑂'])
    await wrapper.findAll('.conversation-title').at(2).trigger('click')
    expect(wrapper.emitted('select')[0][0]).toBe('branch-b')
    await wrapper.get('[aria-label="Поиск чатов агента Диалоговый агент"]').setValue('монолит')
    expect(wrapper.findAll('.conversation-title strong').map(item => item.text())).toEqual(['Монолит'])
    await wrapper.get('[aria-label="Поиск чатов агента Диалоговый агент"]').setValue('нет такого')
    expect(wrapper.text()).toContain('По вашему запросу чатов не найдено')
    await wrapper.findAll('.agent-folder')[1].trigger('click')
    expect(wrapper.emitted('select-agent')[0][0]).toBe('mcp_games')
    await wrapper.setProps({ selectedAgent: 'mcp_games', conversations: [] })
    expect(wrapper.get('.agent-folder[aria-expanded="true"]').text()).toContain('Игровой агент')
    expect(wrapper.findAll('.conversation-title')).toHaveLength(0)
    expect(wrapper.text()).toContain('Пока нет чатов')
  })
})
