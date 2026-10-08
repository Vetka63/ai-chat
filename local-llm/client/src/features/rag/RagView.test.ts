import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import RagView from './RagView.vue'
import { ragApi, metric, type Experiment } from './api'

vi.mock('../../api', () => ({ api: { runtime: vi.fn(async () => ({ connected: true, models: [{ name: 'qwen3:8b', quantization: 'Q4_K_M' }] })) } }))
vi.mock('./api', async importOriginal => ({ ...await importOriginal<typeof import('./api')>(), ragApi: {
  settings: vi.fn(async () => ({ cloudReady: true, cloudModels: ['deepseek-flash', 'deepseek-v4-pro'], embeddingModel: 'qwen3-embedding:0.6b', contextTokens: 8192 })),
  documents: vi.fn(async () => [{ id: 'd', title: 'Кракен', characters: 1000 }]),
  indexes: vi.fn(async () => [{ id: 'i', status: 'READY', total: 2, documentIds: ['d'] }]),
  experiments: vi.fn(async () => []), experiment: vi.fn(), start: vi.fn(), upload: vi.fn(), build: vi.fn(),
} }))
beforeEach(() => { vi.clearAllMocks(); window.location.hash = '#rag'; localStorage.clear() })
afterEach(() => { vi.useRealTimers() })
const run = (cloudStatus = 'RUNNING'): Experiment => ({ id: 'r', createdAt: 'now', request: { question: 'Вопрос?', mode: 'COMPARE' } as Experiment['request'], status: 'RUNNING', error: null, milliseconds: null,
  retrieval: { included: [], hits: [], milliseconds: 10, contextCharacters: 0, promptSha256: 'same-hash', embeddingTokens: 7 },
  answers: [{ provider: 'LOCAL', requestedModel: 'qwen3:8b', status: 'COMPLETED', answer: 'Ответ локальной модели [S1]', milliseconds: 80, citedRefs: ['S1'], citationStatus: 'VALID' },
    { provider: 'DEEPSEEK', requestedModel: 'deepseek-flash', status: cloudStatus, answer: cloudStatus === 'COMPLETED' ? 'Ответ DeepSeek' : null, citedRefs: [], citationStatus: 'MISSING' }] as Experiment['answers'],
})
describe('day28 RAG', () => {
  it('offers per-question provider selection and independent models', async () => {
    const wrapper = mount(RagView); await flushPromises()
    expect(wrapper.findAll('option').map(o => o.text())).toEqual(expect.arrayContaining(['Локальная LLM', 'DeepSeek', 'Сравнить параллельно']))
    await wrapper.find('.rag-composer-actions select').setValue('COMPARE')
    expect(wrapper.text()).toContain('Облачная модель'); expect(wrapper.text()).toContain('Локальная модель')
    wrapper.unmount()
  })
  it('displays one ready answer before second is finished then polls next answer', async () => {
    vi.useFakeTimers(); window.location.hash = '#rag/r'
    vi.mocked(ragApi.experiment).mockResolvedValueOnce(run()).mockResolvedValueOnce(run()).mockResolvedValue(run('COMPLETED'))
    const wrapper = mount(RagView); await flushPromises()
    expect(wrapper.findAll('.rag-answer-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('Ответ локальной модели'); expect(wrapper.text()).toContain('Модель составляет ответ')
    await vi.advanceTimersByTimeAsync(1000); await flushPromises()
    expect(wrapper.text()).toContain('Ответ DeepSeek'); expect(wrapper.text()).toContain('same-hash')
    wrapper.unmount()
  })
  it('submits selected mode with a durable UUID and null output cap', async () => {
    vi.mocked(ragApi.start).mockResolvedValue({ ...run('COMPLETED'), status: 'COMPLETED' })
    const wrapper = mount(RagView); await flushPromises()
    await wrapper.find('textarea').setValue('Сколько имён?')
    await wrapper.find('.rag-composer-actions select').setValue('COMPARE')
    await wrapper.find('.rag-composer').trigger('submit'); await flushPromises()
    const sent = vi.mocked(ragApi.start).mock.calls[0]![0]
    expect(sent.mode).toBe('COMPARE'); expect(sent.maxOutputTokens).toBeNull(); expect(sent.indexId).toBe('i')
    expect(sent.requestId).toMatch(/^[0-9a-f-]{36}$/)
    wrapper.unmount()
  })
  it('cloud unavailable does not disable local answer', async () => {
    vi.mocked(ragApi.settings).mockResolvedValueOnce({ cloudReady: false, cloudEnabled: false, cloudModels: ['deepseek-flash'], embeddingModel: 'embed', contextTokens: 8192 })
    const wrapper = mount(RagView); await flushPromises()
    expect(wrapper.find('option[value=DEEPSEEK]').attributes('disabled')).toBeDefined()
    await wrapper.find('textarea').setValue('Вопрос')
    expect(wrapper.find('.rag-composer button').attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })
  it('escapes generated HTML and marks truncation explicitly', async () => {
    window.location.hash = '#rag/r'; const result = run(); result.status = 'PARTIAL'; result.answers[0]!.status = 'TRUNCATED'; result.answers[0]!.answer = '<script>alert(1)</script>'
    vi.mocked(ragApi.experiment).mockResolvedValue(result)
    const wrapper = mount(RagView); await flushPromises()
    expect(wrapper.find('script').exists()).toBe(false); expect(wrapper.text()).toContain('Ответ обрезан лимитом')
    wrapper.unmount()
  })
  it('missing metrics never become fake zero', () => { expect(metric(null)).toBe('—'); expect(metric(0, ' мс')).toBe('0 мс') })
})
