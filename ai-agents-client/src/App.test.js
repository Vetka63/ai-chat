import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

function response(body, ok = true, status = 200) {
  return Promise.resolve({ ok, status, json: () => Promise.resolve(body) })
}

describe('Day 6 client', () => {
  beforeEach(() => {
    vi.stubGlobal('crypto', { randomUUID: vi.fn(() => String(Math.random())) })
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url) => {
      if (url.endsWith('/agents')) return response({ agents: [{ id: 'dialogue', name: 'Агент', description: 'Тест' }] })
      return response({ agent_id: 'dialogue', reply: 'Ответ', model: 'deepseek-test', source: 'llm' })
    }))
  })

  afterEach(() => vi.unstubAllGlobals())

  it('loads an agent, sends only the current message and clears the local chat', async () => {
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('textarea').setValue('Привет')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const runCall = fetch.mock.calls.find(([url]) => url.includes('/runs'))
    expect(JSON.parse(runCall[1].body)).toEqual({ message: 'Привет' })
    expect(wrapper.text()).toContain('Ответ')

    await wrapper.get('.new-chat').trigger('click')
    expect(wrapper.text()).not.toContain('Ответ')
  })

  it('prevents a second request while the first one is pending', async () => {
    let resolveRun
    fetch.mockImplementation((url) => {
      if (url.endsWith('/agents')) return response({ agents: [{ id: 'dialogue', name: 'Агент', description: 'Тест' }] })
      return new Promise((resolve) => { resolveRun = () => resolve({ ok: true, json: () => Promise.resolve({ reply: 'Ответ', model: 'test', source: 'llm' }) }) })
    })
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('textarea').setValue('Запрос')
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(fetch.mock.calls.filter(([url]) => url.includes('/runs'))).toHaveLength(1)
    resolveRun()
    await flushPromises()
  })
})

