import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { api, type Run, type GenerateRequest } from './api'
vi.mock('./api', () => ({ api: { runtime: vi.fn(), scenarios: vi.fn(), runs: vi.fn(), run: vi.fn(), generate: vi.fn() } }))
const request: GenerateRequest = { model: 'qwen3:8b', prompt: 'Столица?', temperature: .7, thinking: false, maxOutputTokens: null, scenarioId: null }
const run: Run = { id: 'saved', createdAt: '2026-10-05T00:00:00Z', request, status: 'COMPLETED', generation: { answer: 'Париж', thinking: null, doneReason: 'stop', inputTokens: 10, outputTokens: 3, totalMilliseconds: 20, loadMilliseconds: 1, tokensPerSecond: 50 }, elapsedMilliseconds: 30, error: null }
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(api.runtime).mockResolvedValue({ connected: true, version: 'test', configuredModels: ['qwen3:8b'], models: [{ name: 'qwen3:8b', sizeBytes: 5200000000, parameterSize: '8B', quantization: 'Q4_K_M' }], error: null })
  vi.mocked(api.scenarios).mockResolvedValue([{ id: 'simple', title: 'Короткий ответ', difficulty: 'Простой', prompt: 'Столица?', expectation: 'Париж' }])
  vi.mocked(api.runs).mockResolvedValue([])
  vi.mocked(api.generate).mockResolvedValue(run)
  vi.mocked(api.run).mockResolvedValue(run)
  HTMLElement.prototype.scrollIntoView = vi.fn()
})
describe('local generation UI', () => {
  it('opens a server saved result after mount without generating again', async () => {
    vi.mocked(api.runs).mockResolvedValue([{ id: run.id, createdAt: run.createdAt, prompt: run.request.prompt, model: run.request.model, status: run.status }])
    const wrapper = mount(App); await flushPromises()
    expect(wrapper.find('.answer').text()).toBe('Париж')
    expect(api.generate).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Ответ получен')
    wrapper.unmount()
  })
  it('sends selected local model and omits an application limit when cleared', async () => {
    const wrapper = mount(App); await flushPromises()
    await wrapper.find('.scenarios button').trigger('click')
    const limit = wrapper.find('input[placeholder="По умолчанию Ollama"]')
    await limit.setValue('100'); await limit.setValue('')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(api.generate).toHaveBeenCalledWith({ ...request, scenarioId: 'simple' })
    expect(wrapper.text()).toContain('Входные токены')
    wrapper.unmount()
  })
  it('shows a failed run without inventing an answer', async () => {
    vi.mocked(api.generate).mockResolvedValue({ ...run, status: 'FAILED', generation: null, error: 'Ollama offline' })
    const wrapper = mount(App); await flushPromises(); await wrapper.find('textarea').setValue('q'); await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('Ollama offline')
    expect(wrapper.find('.answer').exists()).toBe(false)
    wrapper.unmount()
  })
  it('marks truncated output and renders model content as text', async () => {
    vi.mocked(api.generate).mockResolvedValue({ ...run, status: 'TRUNCATED', generation: { ...run.generation!, answer: '<script>alert(1)</script>', doneReason: 'length' } })
    const wrapper = mount(App); await flushPromises(); await wrapper.find('textarea').setValue('q'); await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('Ответ обрезан лимитом')
    expect(wrapper.find('.answer script').exists()).toBe(false)
    expect(wrapper.find('.answer').text()).toContain('<script>')
    wrapper.unmount()
  })
  it('disables generation without an installed model', async () => {
    vi.mocked(api.runtime).mockResolvedValue({ connected: true, version: 'test', configuredModels: ['qwen3:8b'], models: [], error: null })
    const wrapper = mount(App); await flushPromises(); await wrapper.find('textarea').setValue('q')
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Нет установленной разрешённой модели')
    wrapper.unmount()
  })
})
