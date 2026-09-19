import { effectScope, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, it, expect, vi, afterEach } from 'vitest'
import InvariantPanel from './InvariantPanel.vue'
import TaskPanel from '../tasks/TaskPanel.vue'
import { useInvariants } from './useInvariants'
import { saveInvariants } from './api'
import { callType } from '../context/compressionDisplay'
import { memoryReport } from '../tokens/report'
vi.mock('./api', () => ({ saveInvariants: vi.fn() }))
afterEach(() => vi.resetAllMocks())
const workspace = () => ({ revision: 1, rules: [], checks: [] })
const button = (w, label) => w.findAll('button').find(b => b.text() === label)

describe('Day 14 invariants', () => {
  it('does not enable example rules without explicit save', async () => {
    const w = mount(InvariantPanel, { props: { workspace: workspace(), state: { phase: 'planning' }, taskId: 'a' } })
    await button(w, 'Черновик правил Two Sum').trigger('click')
    expect(w.emitted('save')).toBeUndefined()
    expect(w.findAll('textarea')).toHaveLength(4)
    await button(w, 'Сохранить правила и перепланировать').trigger('click')
    expect(w.emitted('save')[0][0]).toHaveLength(4)
    expect(w.emitted('save')[0][0][0]).toEqual({ kind: 'language', label: 'Только Python', value: 'python', active: true })
    w.unmount()
  })
  it('preserves draft on audit refresh but resets when task changes', async () => {
    const w = mount(InvariantPanel, { props: { workspace: workspace(), taskId: 'a' } })
    await button(w, 'Добавить правило').trigger('click')
    await w.get('textarea').setValue('Не изменять nums')
    await w.setProps({ workspace: workspace() })
    expect(w.get('textarea').element.value).toBe('Не изменять nums')
    await w.setProps({ taskId: 'b' })
    expect(w.find('textarea').exists()).toBe(false)
    w.unmount()
  })
  it('explains conflicts using the checked rule snapshot and error code', () => {
    const data = workspace()
    data.checks = [{ id: 'c', stage: 'input', revision: 1, verdict: 'uncertain', error_code: 'rate_limit', rules: [{ id: 'r', label: 'Без изменения входа' }], checks: [{ rule_id: 'r', verdict: 'uncertain', reason: 'Нет результата judge' }] }]
    const w = mount(InvariantPanel, { props: { workspace: data } })
    expect(w.text()).toContain('Без изменения входа — Не удалось подтвердить: Нет результата judge')
    expect(w.text()).toContain('rate_limit')
    expect(w.text()).not.toContain('Локальная проверка, без LLM-вызова.')
    w.unmount()
  })
  it('blocks changes after done but allows rule edits while paused', async () => {
    const w = mount(InvariantPanel, { props: { workspace: workspace(), state: { phase: 'done' } } })
    expect(button(w, 'Добавить правило').attributes('disabled')).toBeDefined()
    await w.setProps({ state: { phase: 'execution', status: 'paused' } })
    expect(button(w, 'Добавить правило').attributes('disabled')).toBeUndefined()
    w.unmount()
  })
  it('sends explicit command ID and workflow version', async () => {
    const scope = effectScope()
    const chat = { agent: ref({ capabilities: ['invariants'] }), agentId: ref('a'), conversation: ref({ id: 'c' }) }
    const memory = { workspace: ref({ workflow: { state: { revision: 7 } } }), refresh: vi.fn() }
    const state = scope.run(() => useInvariants(chat, memory))
    await state.save([])
    expect(saveInvariants).toHaveBeenCalledWith('a', 'c', { command_id: expect.any(String), expected_revision: 7, rules: [] })
    expect(memory.refresh).toHaveBeenCalledOnce()
    scope.stop()
  })
  it('hides stale artifact from active step and allows pause during artifact judge', () => {
    const w = mount(TaskPanel, { props: { invariantRevision: 2, artifactBusy: true, workspace: {
      task_id: 't', state: { phase: 'planning', status: 'active' }, allowed_events: ['pause', 'start_execution'], events: [],
      artifacts: [{ id: 'p', kind: 'plan', invariant_revision: 1, content: { steps: [{ id: 's', title: 'Старый план' }] } }],
    } } })
    expect(w.find('select').exists()).toBe(false)
    expect(w.text()).toContain('Артефакт исключён из контекста')
    expect(button(w, 'Пауза').attributes('disabled')).toBeUndefined()
    expect(button(w, 'К реализации').attributes('disabled')).toBeDefined()
    w.unmount()
  })
  it('labels all judge calls and exports rules and audit', () => {
    for (const stage of ['input', 'output', 'artifact']) expect(callType({ purpose: 'invariant_' + stage })).toContain('Judge')
    const report = memoryReport('Правила', [], { messages: [] }, { invariants: workspace() })
    expect(report).toContain('День 14')
    expect(report).toContain('Judge: 0 вызовов')
    expect(report).toContain('Отказ — решение policy')
  })
})
