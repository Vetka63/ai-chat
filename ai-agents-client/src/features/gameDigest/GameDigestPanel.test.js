import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import GameDigestPanel from './GameDigestPanel.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

it('starts a schedule and renders stored reports in the dedicated chat', async () => {
  let active = false
  vi.stubGlobal('fetch', vi.fn(async (url, options = {}) => {
    if (url.endsWith('/schedule') && options.method === 'PUT') {
      active = true
      return { ok: true, status: 200, json: async () => ({ watch: { status: 'active' } }) }
    }
    if (url.endsWith('/schedule')) return { ok: true, status: 200, json: async () => ({ watch: active ? { status: 'active', next_run_at: '2026-09-24T12:00:00Z' } : null }) }
    if (url.endsWith('/reports')) return { ok: true, status: 200, json: async () => ({ reports: active ? [{
      id: 'r1', created_at: '2026-09-24T12:00:00Z', text: 'Новая игра: Лунный сад',
      stats: { source: 'generated', total_games: 1, runs: 1 },
    }] : [] }) }
    throw new Error(url)
  }))
  const wrapper = mount(GameDigestPanel, { props: { conversationId: 'chat-1' } })
  await flushPromises()
  expect(wrapper.text()).toContain('Сбор остановлен')
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(fetch).toHaveBeenCalledWith('/api/v1/agents/game_digest/conversations/chat-1/schedule', expect.objectContaining({ method: 'PUT' }))
  expect(wrapper.text()).toContain('Сбор включён')
  expect(wrapper.text()).toContain('Новая игра: Лунный сад')
})
