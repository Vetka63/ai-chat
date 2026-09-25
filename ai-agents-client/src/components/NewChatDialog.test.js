import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import NewChatDialog from './NewChatDialog.vue'

vi.mock('../features/mcp/api', () => ({ listMcpServers: vi.fn(async () => [
  { id: 'local-demo', name: 'Демо', chat_enabled: false },
  { id: 'games-mock', name: 'Каталог игр', description: 'Mock API',
    chat_enabled: true, agent_ids: ['mcp_games'] },
  { id: 'java-games-mock', name: 'Каталог игр · Java / Spring Boot', description: 'HTTP MCP',
    chat_enabled: true, agent_ids: ['mcp_games'] },
  { id: 'game-reports', name: 'Отчёты об играх', description: 'Pipeline',
    chat_enabled: true, agent_ids: ['game_reports'] },
]) }))

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

  it('sets editable initial MCP servers for a new chat', async () => {
    const wrapper = mount(NewChatDialog, { props: { open: true, mcpTools: true, agentId: 'mcp_games' } })
    await flushPromises()
    expect(wrapper.text()).toContain('Каталог игр')
    expect(wrapper.text()).toContain('Java / Spring Boot')
    expect(wrapper.text()).not.toContain('Демо')
    expect(wrapper.find('.strategy-grid').exists()).toBe(false)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')[0][0].mcpServerIds).toEqual(['games-mock'])
    await wrapper.get('.mcp-server-option input').setValue(false)
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')[1][0].mcpServerIds).toEqual([])
  })

  it('allows choosing only the Java MCP server for comparison', async () => {
    const wrapper = mount(NewChatDialog, { props: { open: true, mcpTools: true, agentId: 'mcp_games' } })
    await flushPromises()
    const [python, java] = wrapper.findAll('.mcp-server-option input')
    expect(python.element.checked).toBe(true)
    expect(java.element.checked).toBe(false)
    await python.setValue(false)
    await java.setValue(true)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')[0][0].mcpServerIds).toEqual(['java-games-mock'])
  })

  it('creates a day 18 digest chat without dialogue-only settings', async () => {
    const wrapper = mount(NewChatDialog, { props: { open: true, scheduledReports: true, agentName: 'Игровые сводки' } })
    expect(wrapper.text()).toContain('Новый чат сводок')
    expect(wrapper.find('fieldset').exists()).toBe(false)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')[0][0]).toEqual({
      title: 'Новый чат',
      contextSettings: { mode: 'full', keep_last: 10, summarize_every: 10 },
    })
  })

  it('offers only the report pipeline when creating a day 19 chat', async () => {
    const wrapper = mount(NewChatDialog, {
      props: { open: true, mcpTools: true, agentId: 'game_reports', agentName: 'Отчёты по играм' },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Отчёты об играх')
    expect(wrapper.text()).not.toContain('Каталог игр')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')[0][0].mcpServerIds).toEqual(['game-reports'])
  })
})
