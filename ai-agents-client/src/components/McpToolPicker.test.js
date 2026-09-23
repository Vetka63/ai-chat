import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import McpToolPicker from './McpToolPicker.vue'

const servers = [
  { id: 'games-mock', name: 'Python MCP', description: 'Игры' },
  { id: 'java-games-mock', name: 'Java MCP', description: 'Игры' },
]

describe('MCP per-message picker', () => {
  it('allows selecting multiple servers and disabling all', async () => {
    const wrapper = mount(McpToolPicker, { props: { servers, selectedIds: ['games-mock'] } })
    await wrapper.get('.mcp-trigger').trigger('click')
    expect(wrapper.get('.mcp-trigger').text()).toContain('MCP · 1')
    await wrapper.findAll('.mcp-menu-option input')[1].setValue(true)
    expect(wrapper.emitted('change')[0][0]).toEqual(['games-mock', 'java-games-mock'])
    await wrapper.setProps({ selectedIds: ['games-mock', 'java-games-mock'] })
    await wrapper.get('.mcp-menu-actions button').trigger('click')
    expect(wrapper.emitted('change')[1][0]).toEqual([])
  })

  it('can show empty or unavailable servers without blocking the composer', async () => {
    const wrapper = mount(McpToolPicker, { props: { servers: [], selectedIds: [] } })
    await wrapper.get('.mcp-trigger').trigger('click')
    expect(wrapper.text()).toContain('Нет доступных MCP-серверов')
    await wrapper.get('.mcp-refresh').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })
})
