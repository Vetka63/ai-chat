import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OptimizationView from './OptimizationView.vue'
import { optimizationApi as api, score, comparisonWarnings, type Experiment } from './api'
import { HttpFailure } from '../../api'
vi.mock('./api', async () => ({ ...await vi.importActual('./api'), optimizationApi: { settings: vi.fn(), cases: vi.fn(), list: vi.fn(), get: vi.fn(), start: vi.fn(), note: vi.fn() } }))
const experiment: Experiment = {
  id: 'saved', createdAt: '2026-10-08T12:00:00Z', model: 'qwen3:4b-q4_K_M', identity: { name: 'qwen3:4b-q4_K_M', digest: 'hash', quantization: 'Q4_K_M', parameterSize: '4B', fileBytes: 2600000000 }, runtimeVersion: 'test', promptVersion: 'v1', skill: null, status: 'COMPLETED', note: 'saved conclusion',
  request: { id: 'saved', provider: 'LOCAL', cloudModel: 'deepseek-v4-pro', promptProfile: 'BASELINE', skillEnabled: false, parameters: { temperature: .7, maxTokens: 1024, contextSize: 8192, thinking: false, topP: .8, topK: 20, seed: 42 }, caseIds: ['c01'], input: '', repetitions: 1 },
  items: [{ id: 'c01-1', caseId: 'c01', title: 'Full outage', repetition: 1, input: 'incident', expected: { service: 'orders-api', environment: 'production', priority: 'P1', started_at: null, root_cause: null }, messages: [{ role: 'SYSTEM', content: 'contract' }, { role: 'USER', content: 'incident' }], promptSha256: 'prompt-hash', status: 'COMPLETED', generation: { answer: '{"priority":"P1"}', thinking: null, doneReason: 'stop', inputTokens: 100, outputTokens: 20, totalMilliseconds: 1000, loadMilliseconds: 50, tokensPerSecond: 20 }, elapsedMilliseconds: 1100, resources: { samples: 4, peakAllocatedBytes: 3000000000, peakVramBytes: 3000000000, peakNonVramBytes: 0, observedContextSize: 8192, placement: 'GPU', source: 'ps' }, evaluation: { validJson: true, validSchema: true, matchedFields: 5, totalFields: 5, exactFields: true, mismatches: [], card: {} }, error: null }],
}
beforeEach(() => {
  vi.clearAllMocks(); localStorage.clear(); window.location.hash = '#optimization'
  vi.mocked(api.settings).mockResolvedValue({ localModel: experiment.model, runtimeConnected: true, installed: true, identity: experiment.identity, cloudEnabled: true, cloudReady: true, cloudModels: ['deepseek-v4-pro'], skill: { id: 'incident-triage', version: '1', title: 'skill', sha256: 'skill-hash' } })
  vi.mocked(api.cases).mockResolvedValue([{ id: 'c01', title: 'Full outage', split: 'calibration', input: 'incident', expected: experiment.items[0]!.expected! }])
  vi.mocked(api.list).mockResolvedValue([{ id: 'saved', createdAt: experiment.createdAt, provider: 'LOCAL', model: experiment.model, profile: 'BASELINE', skillEnabled: false, status: 'COMPLETED', finished: 1, total: 1 }])
  vi.mocked(api.get).mockResolvedValue(experiment); vi.mocked(api.start).mockResolvedValue(experiment); vi.mocked(api.note).mockResolvedValue(experiment)
})
describe('optimization lab', () => {
  it('candidate preset sets explicit specialized profile skill and safe context', async () => {
    const wrapper = mount(OptimizationView); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === 'Кандидат оптимизации')!.trigger('click'); await flushPromises()
    expect(wrapper.find('select[aria-label="Шаблон задания"]').element).toHaveProperty('value', 'SPECIALIZED')
    expect(wrapper.find('select[aria-label="Окно контекста"]').element).toHaveProperty('value', '6144')
    expect(wrapper.find('input[aria-label="Максимум выходных токенов"]').element).toHaveProperty('value', '512')
    expect(wrapper.find('.opt-check input').element).toHaveProperty('checked', true); wrapper.unmount()
  })
  it('changing visible dataset clears hidden selections from the previous dataset', async () => {
    const wrapper = mount(OptimizationView); await flushPromises(); await wrapper.find('.opt-cases input').setValue(true)
    await wrapper.find('.opt-case-toolbar select').setValue('holdout'); await flushPromises()
    expect(wrapper.find('.opt-case-picker summary').text()).toContain('выбрано 0')
    expect(wrapper.find('button[type=submit]').attributes('disabled')).toBeDefined(); wrapper.unmount()
  })
  it('opens persisted results without generation and can save a conclusion', async () => {
    window.location.hash = '#optimization/saved'
    const wrapper = mount(OptimizationView); await flushPromises()
    expect(api.start).not.toHaveBeenCalled(); expect(wrapper.find('.opt-answer-text').text()).toContain('P1')
    expect(wrapper.find('.opt-note textarea').element).toHaveProperty('value', 'saved conclusion')
    await wrapper.find('.opt-note textarea').setValue('my conclusion')
    await wrapper.findAll('button').find(b => b.text() === 'Сохранить вывод')!.trigger('click'); await flushPromises()
    expect(api.note).toHaveBeenCalledWith('saved', 'my conclusion'); wrapper.unmount()
  })
  it('sends explicit provider settings and selected cases with a UUID', async () => {
    const wrapper = mount(OptimizationView); await flushPromises()
    await wrapper.find('.opt-cases input').setValue(true)
    await wrapper.find('select[aria-label="Провайдер"]').setValue('DEEPSEEK')
    expect(wrapper.find('select[aria-label="Окно контекста"]').attributes('disabled')).toBeDefined()
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(api.start).toHaveBeenCalledWith(expect.objectContaining({ provider: 'DEEPSEEK', caseIds: ['c01'], input: '', id: expect.any(String) }))
    expect(localStorage.getItem('day29-receipt')).toBeNull(); wrapper.unmount()
  })
  it('shows a failed run honestly and escapes model HTML', async () => {
    const altered = structuredClone(experiment); altered.items[0]!.generation!.answer = '<script>alert(1)</script>'
    altered.items[0]!.evaluation = { validJson: false, validSchema: false, matchedFields: 0, totalFields: 5, exactFields: false, mismatches: ['invalid JSON'], card: null }
    vi.mocked(api.get).mockResolvedValue(altered); window.location.hash = '#optimization/saved'
    const wrapper = mount(OptimizationView); await flushPromises()
    expect(wrapper.find('.opt-answer script').exists()).toBe(false); expect(wrapper.text()).toContain('invalid JSON')
    expect(wrapper.text()).toContain('0/5'); wrapper.unmount()
  })
  it('definite HTTP rejection clears the receipt and allows correction without hidden retry', async () => {
    vi.mocked(api.start).mockRejectedValue(new HttpFailure(400, 'invalid parameters'))
    const wrapper = mount(OptimizationView); await flushPromises(); await wrapper.find('textarea').setValue('q')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('invalid parameters'); expect(api.start).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('day29-receipt')).toBeNull(); expect(wrapper.find('button[type=submit]').attributes('disabled')).toBeUndefined(); wrapper.unmount()
  })
  it('shows empty truncated answer with reasoning and keeps token metrics', async () => {
    const altered = structuredClone(experiment); altered.status = 'PARTIAL'; altered.items[0]!.status = 'TRUNCATED'
    altered.items[0]!.generation = { ...altered.items[0]!.generation!, answer: '', thinking: 'partial reasoning', doneReason: 'length', outputTokens: 64 }
    vi.mocked(api.get).mockResolvedValue(altered); window.location.hash = '#optimization/saved'
    const wrapper = mount(OptimizationView); await flushPromises()
    expect(wrapper.text()).toContain('закончились до итогового ответа')
    expect(wrapper.find('.opt-thinking').text()).toContain('partial reasoning')
    expect(wrapper.text()).toContain('64'); expect(api.start).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('opens another saved UUID after a hash change without a new model call', async () => {
    window.location.hash = '#optimization/saved'
    const wrapper = mount(OptimizationView); await flushPromises()
    const next = structuredClone(experiment); next.id = 'next'; next.note = 'next saved note'
    vi.mocked(api.get).mockResolvedValue(next)
    window.location.hash = '#optimization/next'; window.dispatchEvent(new HashChangeEvent('hashchange')); await flushPromises()
    expect(api.get).toHaveBeenCalledWith('next'); expect(api.start).not.toHaveBeenCalled()
    expect(wrapper.find('.opt-note textarea').element).toHaveProperty('value', 'next saved note'); wrapper.unmount()
  })
  it('unknown transport result is reconciled by GET not regenerated', async () => {
    vi.mocked(api.start).mockRejectedValue(new Error('network'))
    const wrapper = mount(OptimizationView); await flushPromises(); await wrapper.find('textarea').setValue('q')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(api.get).toHaveBeenCalled(); expect(api.start).toHaveBeenCalledTimes(1); expect(wrapper.text()).toContain('P1')
    expect(window.location.hash).toBe('#optimization/saved'); wrapper.unmount()
  })
  it('counts failures in quality denominator and warns about confounded comparisons', () => {
    const failed = structuredClone(experiment); failed.items[0]!.status = 'FAILED'; failed.items[0]!.evaluation = null
    expect(score(failed).fieldTotal).toBe(5); expect(score(failed).fields).toBe(0); expect(score(failed).successful).toBe(0)
    expect(comparisonWarnings(experiment, { ...experiment, identity: { ...experiment.identity!, quantization: 'F16' }, model: 'qwen3:4b-fp16' })).toEqual([])
    const modified = structuredClone(experiment); modified.request.parameters.contextSize = 4096
    expect(comparisonWarnings(experiment, modified).join(' ')).toContain('Настройки различаются')
  })
})
