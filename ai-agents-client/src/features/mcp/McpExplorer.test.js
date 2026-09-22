import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import McpExplorer from './McpExplorer.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

const server = { id: 'local-demo', name: 'Локальный учебный сервер', description: 'Демо', transport: 'stdio' }
const result = {
  server,
  server_name: 'Day 16 Demo',
  protocol_version: '2026-07-28',
  tools: [{ name: 'add_numbers', title: 'add_numbers', description: 'Сложить два числа', input_schema: { type: 'object' } }],
}

describe('MCP explorer', () => {
  it('connects through the backend and renders discovered tools', async () => {
    vi.stubGlobal('fetch', vi.fn((url, options = {}) => {
      if (url.endsWith('/mcp/servers') && !options.method) return Promise.resolve({ ok: true, json: async () => [server] })
      if (url.endsWith('/mcp/servers/local-demo/discover')) return Promise.resolve({ ok: true, json: async () => result })
      throw new Error(`Unexpected request ${url}`)
    }))
    const wrapper = mount(McpExplorer)
    await flushPromises()
    expect(wrapper.text()).toContain(server.name)
    expect(wrapper.text()).not.toContain('add_numbers')

    await wrapper.get('.mcp-connect').trigger('click')
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('/api/v1/mcp/servers/local-demo/discover', { method: 'POST' })
    expect(wrapper.text()).toContain('add_numbers')
    expect(wrapper.text()).toContain('2026-07-28')
    expect(wrapper.text()).toContain('соединение закрыто')
  })

  it('shows a connection error without claiming success', async () => {
    vi.stubGlobal('fetch', vi.fn((url, options = {}) => {
      if (!options.method) return Promise.resolve({ ok: true, json: async () => [server] })
      return Promise.resolve({ ok: false, json: async () => ({ error: 'Сервер недоступен' }) })
    }))
    const wrapper = mount(McpExplorer)
    await flushPromises()
    await wrapper.get('.mcp-connect').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('Сервер недоступен')
    expect(wrapper.find('.mcp-results').exists()).toBe(false)
  })
})
