import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

function response(body, ok = true, status = 200) {
  return Promise.resolve({ ok, status, json: () => Promise.resolve(body) })
}

const agent = { id: 'dialogue', name: 'Агент', description: 'Тест' }
const summary = {
  id: 'chat-1', agent_id: 'dialogue', title: 'Новый чат',
  created_at: '2026-01-01T00:00:00Z', updated_at: '2026-01-01T00:00:00Z',
}

describe('Day 7 client', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubGlobal('confirm', vi.fn(() => true))
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url, options = {}) => {
      if (url.endsWith('/agents')) return response({ agents: [agent] })
      if (url.endsWith('/conversations') && !options.method) return response([])
      if (url.endsWith('/conversations') && options.method === 'POST') return response(summary, true, 201)
      if (url.includes('/runs')) return response({ reply: 'Ответ', model: 'deepseek-test', source: 'llm' })
      throw new Error(`Unexpected request: ${url}`)
    }))
  })

  afterEach(() => vi.unstubAllGlobals())

  it('creates a conversation and sends its id with the current message', async () => {
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('textarea').setValue('Привет')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const runCall = fetch.mock.calls.find(([url]) => url.includes('/runs'))
    expect(JSON.parse(runCall[1].body)).toEqual({ conversation_id: 'chat-1', message: 'Привет' })
    expect(wrapper.text()).toContain('Ответ')
  })

  it('loads persisted messages when the page starts', async () => {
    fetch.mockImplementation((url) => {
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

  it('prevents a second request while the first one is pending', async () => {
    let resolveRun
    fetch.mockImplementation((url, options = {}) => {
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
    await wrapper.get('textarea').setValue('Запрос')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    expect(fetch.mock.calls.filter(([url]) => url.includes('/runs'))).toHaveLength(1)
    resolveRun()
    await flushPromises()
  })

  it('keeps the persisted user message visible when the LLM request fails', async () => {
    fetch.mockImplementation((url, options = {}) => {
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
    await wrapper.get('textarea').setValue('Какой город самый горячий?')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Какой город самый горячий?')
    expect(wrapper.text()).toContain('Модель вернула пустой ответ')
  })
})

