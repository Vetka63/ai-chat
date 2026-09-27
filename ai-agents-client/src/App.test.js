import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

enableAutoUnmount(afterEach)

function response(body, ok = true, status = 200) {
  return Promise.resolve({ ok, status, json: () => Promise.resolve(body) })
}

const agent = { id: 'dialogue', name: 'Агент', description: 'Тест' }
const summary = {
  id: 'chat-1', agent_id: 'dialogue', title: 'Новый чат',
  created_at: '2026-01-01T00:00:00Z', updated_at: '2026-01-01T00:00:00Z',
  context_settings: { mode: 'full', keep_last: 10, summarize_every: 10 },
}

async function createConfiguredChat(wrapper, mode = 'full') {
  await wrapper.get('.new-chat').trigger('click')
  await wrapper.get(`.new-chat-dialog input[value="${mode}"]`).setValue(true)
  await wrapper.get('.new-chat-dialog form').trigger('submit')
  await flushPromises()
}

describe('Day 10 client', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('confirm', vi.fn(() => true))
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url, options = {}) => {
      if (url.endsWith('/models')) return response({ models: [{ id: 'flash', title: 'Flash', available: true, pricing: {} }], default_model_id: 'flash' })
      if (url.endsWith('/preview')) return response({})
      if (url.endsWith('/agents')) return response({ agents: [agent] })
      if (url.endsWith('/mcp/servers')) return response([{ id: 'local-demo', name: 'Учебный MCP', description: 'Демо', transport: 'stdio' }])
      if (url.endsWith('/conversations') && !options.method) return response([])
      if (url.endsWith('/conversations') && options.method === 'POST') {
        const body = JSON.parse(options.body)
        return response({ ...summary, title: body.title, context_settings: body.context_settings }, true, 201)
      }
      if (url.includes('/runs')) return response({ reply: 'Ответ', model: 'deepseek-test', source: 'llm' })
      throw new Error(`Unexpected request: ${url}`)
    }))
  })

  afterEach(() => vi.unstubAllGlobals())

  it('sends checked MCP servers only for the current message', async () => {
    const mcpAgent = { id: 'mcp_games', name: 'Игровой агент', capabilities: ['mcp_tools'] }
    const servers = [
      { id: 'games-mock', name: 'Python MCP', chat_enabled: true },
      { id: 'java-games-mock', name: 'Java MCP', chat_enabled: true },
    ]
    let created = null
    fetch.mockImplementation((url, options = {}) => {
      if (url.endsWith('/models')) return response({ models: [{ id: 'flash', title: 'Flash', available: true, pricing: {} }], default_model_id: 'flash' })
      if (url.endsWith('/agents')) return response({ agents: [mcpAgent] })
      if (url.endsWith('/mcp/servers')) return response(servers)
      if (url.endsWith('/conversations') && options.method === 'POST') {
        const body = JSON.parse(options.body)
        created = { ...summary, agent_id: 'mcp_games', mcp_server_ids: body.mcp_server_ids, context_settings: body.context_settings }
        return response(created, true, 201)
      }
      if (url.endsWith('/conversations')) return response(created ? [created] : [])
      if (url.includes('/runs')) return response({ reply: 'Ответ', model: 'flash', source: 'llm' })
      throw new Error(`Unexpected request: ${url}`)
    })
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('.new-chat').trigger('click')
    await flushPromises()
    await wrapper.get('.new-chat-dialog form').trigger('submit')
    await flushPromises()
    await wrapper.get('.mcp-trigger').trigger('click')
    await wrapper.findAll('.mcp-menu-option input')[0].setValue(false)
    await wrapper.findAll('.mcp-menu-option input')[1].setValue(true)
    await wrapper.get('textarea').setValue('Найди игру')
    await wrapper.get('form.composer').trigger('submit')
    await flushPromises()
    expect(JSON.parse(fetch.mock.calls.find(([url]) => url.includes('/runs'))[1].body).mcp_server_ids).toEqual(['java-games-mock'])
    expect(wrapper.text()).toContain('MCP: java-games-mock')
    await wrapper.get('.mcp-trigger').trigger('click')
    await wrapper.findAll('.mcp-menu-option input')[1].setValue(false)
    await wrapper.get('textarea').setValue('А теперь без каталога')
    await wrapper.get('form.composer').trigger('submit')
    await flushPromises()
    const runCalls = fetch.mock.calls.filter(([url]) => url.includes('/runs'))
    expect(JSON.parse(runCalls[1][1].body).mcp_server_ids).toEqual([])
  })

  it('connects an MCP server after creating a chat without one', async () => {
    const mcpAgent = { id: 'mcp_games', name: 'Игровой агент', capabilities: ['mcp_tools'] }
    const servers = [{ id: 'games-mock', name: 'Python MCP', chat_enabled: true }]
    let created = null
    fetch.mockImplementation((url, options = {}) => {
      if (url.endsWith('/models')) return response({ models: [{ id: 'flash', title: 'Flash', available: true, pricing: {} }], default_model_id: 'flash' })
      if (url.endsWith('/agents')) return response({ agents: [mcpAgent] })
      if (url.endsWith('/mcp/servers')) return response(servers)
      if (url.endsWith('/conversations') && options.method === 'POST') {
        const body = JSON.parse(options.body)
        created = { ...summary, agent_id: 'mcp_games', mcp_server_ids: body.mcp_server_ids, context_settings: body.context_settings }
        return response(created, true, 201)
      }
      if (url.endsWith('/conversations')) return response(created ? [created] : [])
      if (url.includes('/runs')) return response({ reply: 'Игра найдена', model: 'flash', source: 'llm' })
      throw new Error(`Unexpected request: ${url}`)
    })
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('.new-chat').trigger('click')
    await flushPromises()
    await wrapper.get('.new-chat-dialog .mcp-server-option input').setValue(false)
    await wrapper.get('.new-chat-dialog form').trigger('submit')
    await flushPromises()
    expect(created.mcp_server_ids).toEqual([])
    expect(wrapper.get('.mcp-trigger').text()).toContain('MCP выкл.')
    await wrapper.get('.mcp-trigger').trigger('click')
    await wrapper.get('.mcp-menu-option input').setValue(true)
    await wrapper.get('textarea').setValue('Найди игру')
    await wrapper.get('form.composer').trigger('submit')
    await flushPromises()
    const runCall = fetch.mock.calls.find(([url]) => url.includes('/runs'))
    expect(JSON.parse(runCall[1].body).mcp_server_ids).toEqual(['games-mock'])
    expect(wrapper.get('.mcp-context-copy').text()).toContain('Python MCP')
  })

  it('keeps a different MCP selection for each chat before sending a message', async () => {
    const mcpAgent = { id: 'mcp_games', name: 'Игровой агент', capabilities: ['mcp_tools'] }
    const chatA = { ...summary, id: 'chat-a', title: 'Первый чат', agent_id: 'mcp_games', mcp_server_ids: [] }
    const chatB = { ...summary, id: 'chat-b', title: 'Второй чат', agent_id: 'mcp_games', mcp_server_ids: [] }
    fetch.mockImplementation((url) => {
      if (url.endsWith('/models')) return response({ models: [{ id: 'flash', title: 'Flash', available: true, pricing: {} }], default_model_id: 'flash' })
      if (url.endsWith('/agents')) return response({ agents: [mcpAgent] })
      if (url.endsWith('/mcp/servers')) return response([{ id: 'games-mock', name: 'Python MCP', chat_enabled: true }])
      if (url.endsWith('/conversations')) return response([chatA, chatB])
      if (url.endsWith('/conversations/chat-a')) return response({ ...chatA, messages: [] })
      if (url.endsWith('/conversations/chat-b')) return response({ ...chatB, messages: [] })
      throw new Error(`Unexpected request: ${url}`)
    })

    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('.mcp-trigger').trigger('click')
    await wrapper.get('.mcp-menu-option input').setValue(true)
    expect(wrapper.get('.mcp-trigger').text()).toContain('MCP · 1')
    await wrapper.findAll('.conversation-title')[1].trigger('click')
    await flushPromises()
    expect(wrapper.get('.mcp-trigger').text()).toContain('MCP выкл.')
    await wrapper.findAll('.conversation-title')[0].trigger('click')
    await flushPromises()
    expect(wrapper.get('.mcp-trigger').text()).toContain('MCP · 1')
    expect(JSON.parse(localStorage.getItem('agents:mcp-selection:chat-a'))).toEqual(['games-mock'])

    wrapper.unmount()
    const restored = mount(App)
    await flushPromises()
    expect(restored.get('.mcp-trigger').text()).toContain('MCP · 1')
  })

  it('separates chats from settings and keeps the model beside the composer', async () => {
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.find('.chat-sidebar .conversation-list').exists()).toBe(true)
    expect(wrapper.find('.chat-sidebar .token-panel').exists()).toBe(false)
    expect(wrapper.find('.composer .model-picker').exists()).toBe(true)
    expect(wrapper.find('.settings-sidebar .agent-card').exists()).toBe(true)
    expect(wrapper.get('.theme-section').attributes('open')).toBeUndefined()
    await createConfiguredChat(wrapper)
    await wrapper.get('form.composer textarea').setValue('Не потерять черновик')
    await wrapper.get('.settings-toggle').trigger('click')
    expect(wrapper.get('.settings-toggle').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('#inspector-pane-agent').isVisible()).toBe(true)
    await wrapper.get('#inspector-tab-context').trigger('click')
    expect(wrapper.get('#inspector-tab-context').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('#inspector-tab-agent').attributes('aria-selected')).toBe('false')
    await wrapper.get('#inspector-tab-metrics').trigger('click')
    expect(wrapper.get('#inspector-pane-metrics').isVisible()).toBe(true)
    await wrapper.get('[aria-label="Скрыть настройки"]').trigger('click')
    expect(wrapper.get('.settings-toggle').attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('form.composer textarea').element.value).toBe('Не потерять черновик')
    wrapper.unmount()
  })

  it('shows chats inside their agent and restores the selected agent', async () => {
    const gameAgent = { id: 'mcp_games', name: 'Игровой агент', description: 'Каталог игр', capabilities: ['mcp_tools'] }
    const dialogueChat = { ...summary, id: 'dialogue-chat', title: 'Обычный разговор' }
    const gamesChat = { ...summary, id: 'games-chat', agent_id: 'mcp_games', title: 'Поиск игр', mcp_server_ids: [] }
    fetch.mockImplementation((url) => {
      if (url.endsWith('/models')) return response({ models: [{ id: 'flash', title: 'Flash', available: true, pricing: {} }], default_model_id: 'flash' })
      if (url.endsWith('/agents')) return response({ agents: [agent, gameAgent] })
      if (url.endsWith('/mcp/servers')) return response([])
      if (url.endsWith('/agents/dialogue/conversations')) return response([dialogueChat])
      if (url.endsWith('/agents/mcp_games/conversations')) return response([gamesChat])
      if (url.endsWith('/agents/dialogue/conversations/dialogue-chat')) return response({ ...dialogueChat, messages: [] })
      if (url.endsWith('/agents/mcp_games/conversations/games-chat')) return response({ ...gamesChat, messages: [] })
      throw new Error(`Unexpected request: ${url}`)
    })

    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.findAll('.agent-folder')).toHaveLength(2)
    expect(wrapper.get('.conversation-title strong').text()).toBe('Обычный разговор')
    await wrapper.findAll('.agent-folder')[1].trigger('click')
    await flushPromises()
    expect(wrapper.get('.conversation-title strong').text()).toBe('Поиск игр')
    expect(wrapper.get('.chat-heading strong').text()).toBe('Поиск игр')
    expect(localStorage.getItem('agents:selected-agent')).toBe('mcp_games')

    wrapper.unmount()
    const restored = mount(App)
    await flushPromises()
    expect(restored.get('.agent-folder[aria-expanded="true"]').text()).toContain('Игровой агент')
    expect(restored.get('.conversation-title strong').text()).toBe('Поиск игр')
  })

  it('keeps the day 18 digest flow in the redesigned agent workspace', async () => {
    const digestAgent = { id: 'game_digest', name: 'Игровые сводки', description: 'Расписание', capabilities: ['scheduled_reports'] }
    let created = null
    fetch.mockImplementation((url, options = {}) => {
      if (url.endsWith('/models')) return response({ models: [{ id: 'flash', title: 'Flash', available: true, pricing: {} }], default_model_id: 'flash' })
      if (url.endsWith('/agents')) return response({ agents: [agent, digestAgent] })
      if (url.endsWith('/agents/dialogue/conversations')) return response([])
      if (url.endsWith('/agents/game_digest/conversations') && options.method === 'POST') {
        const body = JSON.parse(options.body)
        created = { ...summary, id: 'digest-1', agent_id: 'game_digest', title: body.title, context_settings: body.context_settings }
        return response(created, true, 201)
      }
      if (url.endsWith('/agents/game_digest/conversations')) return response(created ? [created] : [])
      if (url.endsWith('/digest-1/schedule') && options.method === 'PUT') return response({ watch: { status: 'active', next_run_at: '2026-09-24T12:00:00Z' } })
      if (url.endsWith('/digest-1/schedule')) return response({ watch: null })
      if (url.endsWith('/digest-1/reports')) return response({ reports: [] })
      throw new Error(`Unexpected request: ${url}`)
    })

    const wrapper = mount(App)
    await flushPromises()
    await wrapper.findAll('.agent-folder')[1].trigger('click')
    await flushPromises()
    expect(wrapper.get('.digest-panel').text()).toContain('Создайте чат')
    expect(wrapper.find('form.composer').exists()).toBe(false)
    await wrapper.get('.new-chat').trigger('click')
    expect(wrapper.get('.new-chat-dialog h2').text()).toBe('Новый чат сводок')
    expect(wrapper.find('.new-chat-dialog .strategy-grid').exists()).toBe(false)
    await wrapper.get('.new-chat-dialog form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('.digest-panel .digest-controls').exists()).toBe(true)
    expect(wrapper.find('form.composer').exists()).toBe(false)
    await wrapper.get('.digest-panel form').trigger('submit')
    await flushPromises()
    const scheduled = fetch.mock.calls.find(([url, options = {}]) => url.endsWith('/digest-1/schedule') && options.method === 'PUT')
    expect(JSON.parse(scheduled[1].body)).toEqual({ interval_seconds: 300 })
  })

  it('opens the separate MCP section and returns to the chat', async () => {
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('.mcp-nav').trigger('click')
    await flushPromises()
    expect(wrapper.get('.mcp-hero h1').text()).toBe('Подключение к инструментам')
    expect(wrapper.find('form.composer').exists()).toBe(false)
    expect(wrapper.get('.mcp-nav').attributes('aria-current')).toBe('page')
    await wrapper.get('.mcp-nav').trigger('click')
    expect(wrapper.find('.mcp-hero').exists()).toBe(false)
    expect(wrapper.find('form.composer').exists()).toBe(true)
    expect(wrapper.get('.mcp-nav').attributes('aria-current')).toBeUndefined()
    await wrapper.get('.mcp-nav').trigger('click')
    await wrapper.get('.new-chat').trigger('click')
    expect(wrapper.find('.mcp-hero').exists()).toBe(false)
    expect(wrapper.find('form.composer').exists()).toBe(true)
  })

  it('returns keyboard focus to the MCP navigation button after closing the mobile drawer', async () => {
    vi.stubGlobal('innerWidth', 390)
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()
    await wrapper.get('.mcp-nav').trigger('click')
    await flushPromises()
    await wrapper.get('.mcp-header .menu-button').trigger('click')
    expect(wrapper.get('.chat-sidebar').attributes('role')).toBe('dialog')
    await wrapper.get('[aria-label="Закрыть список чатов"]').trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('.mcp-header .menu-button').element)
  })

  it('expands the chat without losing the draft and restores the side panels', async () => {
    const wrapper = mount(App)
    await flushPromises()
    await createConfiguredChat(wrapper)
    await wrapper.get('form.composer textarea').setValue('Черновик сообщения')
    await wrapper.get('.focus-toggle').trigger('click')
    expect(wrapper.get('.app-shell').classes()).toContain('focus-mode')
    expect(wrapper.get('.focus-toggle').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('.chat-sidebar').isVisible()).toBe(false)
    expect(wrapper.get('.settings-sidebar').isVisible()).toBe(false)
    expect(localStorage.getItem('agents:focus-mode')).toBe('true')
    await wrapper.get('.focus-toggle').trigger('click')
    expect(wrapper.get('.app-shell').classes()).not.toContain('focus-mode')
    expect(wrapper.get('.chat-sidebar').attributes('style') || '').not.toContain('display: none')
    expect(wrapper.get('.chat-sidebar').attributes('aria-hidden')).toBeUndefined()
    expect(wrapper.get('form.composer textarea').element.value).toBe('Черновик сообщения')
  })

  it('opens mobile drawers one at a time and restores focus on Escape', async () => {
    vi.stubGlobal('innerWidth', 390)
    const wrapper = mount(App, { attachTo: document.body })
    await flushPromises()
    expect(wrapper.get('.settings-sidebar').isVisible()).toBe(false)
    await wrapper.get('.menu-button').trigger('click')
    expect(wrapper.get('.chat-sidebar').attributes('role')).toBe('dialog')
    expect(wrapper.get('main').attributes('aria-hidden')).toBe('true')
    await wrapper.get('[aria-label="Закрыть список чатов"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.get('.chat-sidebar').classes()).not.toContain('open')
    expect(document.activeElement).toBe(wrapper.get('.menu-button').element)
    await wrapper.get('.settings-toggle').trigger('click')
    expect(wrapper.get('.settings-sidebar').attributes('role')).toBe('dialog')
    expect(wrapper.get('.chat-sidebar').attributes('aria-hidden')).toBe('true')
    await wrapper.get('[aria-label="Скрыть настройки"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.get('.settings-sidebar').isVisible()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('.settings-toggle').element)
  })

  it('creates a conversation and sends its id with the current message', async () => {
    const wrapper = mount(App)
    await flushPromises()
    await createConfiguredChat(wrapper, 'sticky_facts')
    await wrapper.get('textarea').setValue('Привет')
    await wrapper.get('form.composer').trigger('submit')
    await flushPromises()

    const runCall = fetch.mock.calls.find(([url]) => url.includes('/runs'))
    expect(JSON.parse(runCall[1].body)).toEqual({ conversation_id: 'chat-1', message: 'Привет', model_id: 'flash', max_output_tokens: null })
    const createCall = fetch.mock.calls.find(([url, options]) => url.endsWith('/conversations') && options.method === 'POST')
    expect(JSON.parse(createCall[1].body).context_settings.mode).toBe('sticky_facts')
    expect(wrapper.text()).toContain('Ответ')
  })

  it('opens creation settings instead of creating a chat immediately', async () => {
    const wrapper = mount(App)
    await flushPromises()
    expect(wrapper.get('textarea').attributes('disabled')).toBeDefined()
    await wrapper.get('.new-chat').trigger('click')
    expect(wrapper.get('.new-chat-dialog').attributes('role')).toBe('dialog')
    expect(fetch.mock.calls.filter(([url, options = {}]) => url.endsWith('/conversations') && options.method === 'POST')).toHaveLength(0)
  })

  it('loads persisted messages when the page starts', async () => {
    fetch.mockImplementation((url) => {
      if (url.endsWith('/models')) return response({ models: [{ id: 'flash', title: 'Flash', available: true, pricing: {} }], default_model_id: 'flash' })
      if (url.endsWith('/preview')) return response({})
      if (url.endsWith('/agents')) return response({ agents: [agent] })
      if (url.endsWith('/conversations')) return response([summary])
      if (url.endsWith('/conversations/chat-1')) {
        return response({
          ...summary,
          messages: [
            { role: 'user', content: 'Запомни слово клевер' },
            { role: 'assistant', content: 'Запомнил' },
          ],
        })
      }
      throw new Error(`Unexpected request: ${url}`)
    })

    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.text()).toContain('Запомни слово клевер')
    expect(wrapper.text()).toContain('Запомнил')
    expect(wrapper.text()).toContain('Сохранённые чаты')
  })

  it('sends the explicitly enabled 1200 limit', async () => {
    const wrapper = mount(App)
    await flushPromises()
    await createConfiguredChat(wrapper)
    await wrapper.get('.output-settings input[type="checkbox"]').setValue(true)
    await wrapper.get('textarea').setValue('Короткий тест')
    await wrapper.get('form.composer').trigger('submit')
    await flushPromises()
    const runCall = fetch.mock.calls.find(([url]) => url.includes('/runs'))
    expect(JSON.parse(runCall[1].body).max_output_tokens).toBe(1200)
  })

  it('prevents a second request while the first one is pending', async () => {
    let resolveRun
    fetch.mockImplementation((url, options = {}) => {
      if (url.endsWith('/models')) return response({ models: [{ id: 'flash', title: 'Flash', available: true, pricing: {} }], default_model_id: 'flash' })
      if (url.endsWith('/preview')) return response({})
      if (url.endsWith('/agents')) return response({ agents: [agent] })
      if (url.endsWith('/conversations') && !options.method) return response([])
      if (url.endsWith('/conversations') && options.method === 'POST') return response(summary, true, 201)
      if (url.includes('/runs')) {
        return new Promise((resolve) => {
          resolveRun = () => resolve({ ok: true, status: 200, json: () => Promise.resolve({ reply: 'Ответ', model: 'test', source: 'llm' }) })
        })
      }
      throw new Error(`Unexpected request: ${url}`)
    })

    const wrapper = mount(App)
    await flushPromises()
    await createConfiguredChat(wrapper)
    await wrapper.get('textarea').setValue('Запрос')
    await wrapper.get('form.composer').trigger('submit')
    await flushPromises()
    await wrapper.get('form.composer').trigger('submit')
    expect(fetch.mock.calls.filter(([url]) => url.includes('/runs'))).toHaveLength(1)
    resolveRun()
    await flushPromises()
  })

  it('keeps the persisted user message visible when the LLM request fails', async () => {
    fetch.mockImplementation((url, options = {}) => {
      if (url.endsWith('/models')) return response({ models: [{ id: 'flash', title: 'Flash', available: true, pricing: {} }], default_model_id: 'flash' })
      if (url.endsWith('/preview')) return response({})
      if (url.endsWith('/agents')) return response({ agents: [agent] })
      if (url.endsWith('/conversations') && !options.method) return response([])
      if (url.endsWith('/conversations') && options.method === 'POST') return response(summary, true, 201)
      if (url.includes('/runs')) {
        return response({ code: 'empty_output', error: 'Модель вернула пустой ответ' }, false, 502)
      }
      if (url.endsWith('/conversations/chat-1')) {
        return response({ ...summary, messages: [{ role: 'user', content: 'Какой город самый горячий?' }] })
      }
      throw new Error(`Unexpected request: ${url}`)
    })

    const wrapper = mount(App)
    await flushPromises()
    await createConfiguredChat(wrapper)
    await wrapper.get('textarea').setValue('Какой город самый горячий?')
    await wrapper.get('form.composer').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Какой город самый горячий?')
    expect(wrapper.text()).toContain('Модель вернула пустой ответ')
  })
})

