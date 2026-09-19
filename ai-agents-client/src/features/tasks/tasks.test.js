import { computed, effectScope, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, it, expect, vi, afterEach } from 'vitest'
import TaskPanel from './TaskPanel.vue'
import { useTaskWorkflow } from './useTaskWorkflow'
import { memoryReport } from '../tokens/report'
import { taskCommand } from './api'
import { getConversation } from '../../api/agents'
vi.mock('./api', () => ({ taskCommand: vi.fn() }))
vi.mock('../../api/agents', () => ({ getConversation: vi.fn() }))
afterEach(() => vi.resetAllMocks())
const flow = (extra = {}) => ({ task_id: 't', state: { phase: 'planning', status: 'active', revision: 1, expected_action: 'save_plan' }, artifacts: [], events: [], allowed_events: ['start_execution', 'pause'], ...extra })
const button = (wrapper, label) => wrapper.findAll('button').find(b => b.text() === label)

describe('Day 13 workflow', () => {
  it('allows pause while LLM is running but disables normal transitions and saving', async () => {
    const w = mount(TaskPanel, { props: { workspace: flow(), sending: true } })
    expect(button(w, 'Пауза').attributes('disabled')).toBeUndefined()
    expect(button(w, 'К реализации').attributes('disabled')).toBeDefined()
    await button(w, 'Пауза').trigger('click')
    expect(w.emitted('transition')[0]).toEqual(['pause'])
    expect(w.get('textarea').attributes('disabled')).toBeDefined()
    w.unmount()
  })
  it('displays resumable phase and expected action without resetting the task', async () => {
    const data = flow({ state: { phase: 'execution', status: 'paused', revision: 4, expected_action: 'work_on_step' }, allowed_events: ['resume'] })
    const w = mount(TaskPanel, { props: { workspace: data } })
    expect(w.text()).toContain('После продолжения: Продолжите выбранный шаг')
    expect(w.text()).toContain('На паузе')
    await button(w, 'Продолжить').trigger('click')
    expect(w.emitted('transition')[0]).toEqual(['resume'])
    w.unmount()
  })
  it('saves an explicit structured plan and leaves step IDs to the server', async () => {
    const w = mount(TaskPanel, { props: { workspace: flow() } })
    await w.get('textarea').setValue(' Словарь \n Проверка дубликатов ')
    await button(w, 'Сохранить новую версию').trigger('click')
    expect(w.emitted('save')[0][0]).toEqual({ kind: 'plan', content: { steps: [{ title: 'Словарь' }, { title: 'Проверка дубликатов' }] } })
    w.unmount()
  })
  it('labels LLM review as analysis, not executed tests', async () => {
    const data = flow({ state: { phase: 'validation', status: 'active', revision: 1 }, allowed_events: ['finish', 'pause'] })
    const w = mount(TaskPanel, { props: { workspace: data } })
    await w.get('textarea').setValue('Проверить пустой массив')
    await button(w, 'Сохранить новую версию').trigger('click')
    expect(w.emitted('save')[0][0].content.method).toBe('llm_review')
    expect(w.text()).toContain('код не запускался')
    w.unmount()
  })
  it('clears artifact draft on task changes and shows no edit form after done', async () => {
    const w = mount(TaskPanel, { props: { workspace: flow() } })
    await w.get('textarea').setValue('Не переносить в чужую задачу')
    await w.setProps({ workspace: flow({ task_id: 'other' }) })
    expect(w.get('textarea').element.value).toBe('')
    await w.setProps({ workspace: flow({ state: { phase: 'done', status: 'active', expected_action: 'completed' }, allowed_events: [] }) })
    expect(w.find('textarea').exists()).toBe(false)
    expect(w.text()).toContain('Задача завершена')
    w.unmount()
  })
  it('sends versioned commands even during a running request; blocks chat on pause', async () => {
    const scope = effectScope()
    const chat = { agent: ref({ capabilities: ['task_workflow'] }), agentId: ref('algorithm_coach'), conversation: ref({ id: 'c' }), sending: ref(true), send: vi.fn() }
    const memory = { workspace: ref({ workflow: flow() }), refresh: vi.fn() }
    const task = scope.run(() => useTaskWorkflow(chat, memory))
    taskCommand.mockResolvedValue(flow())
    await task.transition('pause')
    expect(taskCommand).toHaveBeenCalledWith('algorithm_coach', 'c', 'events', expect.objectContaining({ expected_revision: 1, event: 'pause', command_id: expect.any(String) }))
    expect(memory.refresh).toHaveBeenCalledOnce()
    memory.workspace.value.workflow.state.status = 'paused'
    await task.send()
    expect(chat.send).not.toHaveBeenCalled()
    memory.workspace.value.workflow.state.status = 'active'
    await task.send()
    expect(chat.send).toHaveBeenCalledWith(expect.objectContaining({ expected_revision: 1 }))
    scope.stop()
  })
  it('keeps action errors and refreshes server state after a conflict', async () => {
    const scope = effectScope()
    const chat = { agent: ref({ capabilities: ['task_workflow'] }), agentId: ref('a'), conversation: ref({ id: 'c' }) }
    const memory = { workspace: ref({ workflow: flow() }), refresh: vi.fn() }
    const task = scope.run(() => useTaskWorkflow(chat, memory))
    taskCommand.mockRejectedValue(new Error('Обновите этап'))
    await task.transition('start_execution')
    expect(task.error.value).toBe('Обновите этап')
    expect(memory.refresh).toHaveBeenCalledOnce()
    expect(task.busy.value).toBe(false)
    scope.stop()
  })
  it('exports state, exact step, artifacts and transition history', () => {
    const report = memoryReport('Задача', [], { messages: [] }, { workflow: flow({ events: [{ event: 'pause' }] }) })
    expect(report).toContain('День 13')
    expect(report).toContain('Фаза: planning')
    expect(report).toContain('pause')
    expect(report).toContain('approvals Дня 15 пока не включены')
  })
  it('can pause during artifact judge and refreshes usage after refusal', async () => {
    const scope = effectScope()
    const chat = { agent: ref({ capabilities: ['task_workflow'] }), agentId: ref('a'), conversation: ref({ id: 'c', runs: [] }) }
    const memory = { workspace: ref({ workflow: flow() }), refresh: vi.fn() }
    const task = scope.run(() => useTaskWorkflow(chat, memory))
    let rejectArtifact
    taskCommand.mockImplementation((a, c, resource) => resource === 'artifacts' ? new Promise((_, reject) => { rejectArtifact = reject }) : Promise.resolve(flow()))
    getConversation.mockResolvedValue({ id: 'c', runs: [{ purpose: 'invariant_artifact' }] })
    const saving = task.save({ kind: 'plan', content: { steps: [{ title: 'План' }] } })
    expect(task.busy.value).toBe(true)
    await task.transition('pause')
    expect(taskCommand).toHaveBeenCalledTimes(2)
    expect(task.busy.value).toBe(true)
    expect(task.controlBusy.value).toBe(false)
    rejectArtifact(new Error('Этап изменился'))
    await saving
    expect(task.error.value).toBe('Этап изменился')
    expect(chat.conversation.value.runs).toHaveLength(1)
    expect(task.busy.value).toBe(false)
    scope.stop()
  })
})
