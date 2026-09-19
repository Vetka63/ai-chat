import { computed, effectScope, nextTick, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MemoryPanel from './MemoryPanel.vue'
import NewChatDialog from '../../components/NewChatDialog.vue'
import { useMemoryLayers } from './useMemoryLayers'
import { memoryReport } from '../tokens/report'
import * as api from './api'

vi.mock('./api', () => ({ getMemory: vi.fn(), saveProblem: vi.fn(), saveEntry: vi.fn(), deleteEntry: vi.fn(), proposeMemory: vi.fn(), resolveProposal: vi.fn() }))
const workspace = () => ({
  task: { id: 'task-1', revision: 1, problem: { statement: 'Two Sum' } },
  profile: { id: 'local', name: 'Учебный', memory_revision: 1 },
  keep_last: 2, history_message_count: 2,
  short_term: [{ id: 7, role: 'user', content: 'Изучаю Python' }, { id: 8, role: 'assistant', content: 'Обсудим задачу' }],
  working: [], long_term: [],
  proposals: [{ id: 'p1', layer: 'long_term', key: 'знание', value: 'Изучаю Python', status: 'pending', source_excerpt: 'user: Изучаю Python', reason: 'Будущие задачи' }],
})
const button = (wrapper, text) => wrapper.findAll('button').find(b => b.text() === text)
afterEach(() => vi.clearAllMocks())

describe('Day 11 memory UI', () => {
  it('creates a task with a condition and fixed short-term window', async () => {
    const wrapper = mount(NewChatDialog, { props: { open: true, memoryLayers: true } })
    expect(wrapper.find('input[value="full"]').exists()).toBe(false)
    await wrapper.get('textarea').setValue('Two Sum')
    await wrapper.get('input[type="number"]').setValue(3)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')[0][0]).toMatchObject({
      contextSettings: { mode: 'sliding_window', keep_last: 3 }, problem: { statement: 'Two Sum' },
    })
    wrapper.unmount()
  })

  it('never saves a proposal merely by rendering and explicitly emits confirmation', async () => {
    const wrapper = mount(MemoryPanel, { props: { workspace: workspace() } })
    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.text()).toContain('в ответы агента не попадают')
    await button(wrapper, 'Подтвердить').trigger('click')
    expect(wrapper.emitted('resolve')[0]).toEqual(['p1', 'accept'])
    await button(wrapper, 'Предложить, что запомнить').trigger('click')
    expect(wrapper.emitted('propose')[0]).toEqual([7])
    wrapper.unmount()
  })

  it('manual form specifies the layer and leaves unrelated memory untouched', async () => {
    const wrapper = mount(MemoryPanel, { props: { workspace: workspace() } })
    const form = wrapper.findAll('form')[1]
    await form.get('select').setValue('long_term')
    await form.get('input').setValue('Приём')
    await form.get('textarea').setValue('Искать дополнение через словарь')
    await form.trigger('submit')
    expect(wrapper.emitted('save')[0][0]).toEqual({
      layer: 'long_term', key: 'Приём', value: 'Искать дополнение через словарь', source_message_id: null,
    })
    expect(wrapper.emitted('problem')).toBeUndefined()
    wrapper.unmount()
  })

  it('disables writes during a request', () => {
    const wrapper = mount(MemoryPanel, { props: { workspace: workspace(), busy: true } })
    expect(wrapper.findAll('button').every(b => b.attributes('disabled') !== undefined)).toBe(true)
    wrapper.unmount()
  })

  it('does not apply an old chat memory response to a new chat', async () => {
    const scope = effectScope()
    const chat = { agent: ref({ capabilities: ['memory_layers'] }), agentId: ref('algorithm_coach'),
      conversation: ref(null), sending: ref(false) }
    chat.messages = computed(() => chat.conversation.value?.messages || [])
    let finishOld
    api.getMemory.mockImplementation((_a, id) => id === 'old' ? new Promise(resolve => { finishOld = resolve }) : Promise.resolve({ ...workspace(), task: { id: 'new' } }))
    const memory = scope.run(() => useMemoryLayers(chat))
    chat.conversation.value = { id: 'old', messages: [] }
    await nextTick()
    chat.conversation.value = { id: 'new', messages: [] }
    await flushPromises()
    finishOld(workspace())
    await flushPromises()
    expect(memory.workspace.value.task.id).toBe('new')
    scope.stop()
  })

  it('sends versions and accounts for the extra proposal call', async () => {
    const scope = effectScope()
    const chat = { agent: ref({ capabilities: ['memory_layers'] }), agentId: ref('algorithm_coach'),
      conversation: ref(null), sending: ref(false) }
    chat.messages = computed(() => chat.conversation.value?.messages || [])
    api.getMemory.mockResolvedValue(workspace())
    const run = { id: 'run', purpose: 'memory_proposals' }
    api.proposeMemory.mockResolvedValue({ workspace: workspace(), run })
    const memory = scope.run(() => useMemoryLayers(chat))
    chat.conversation.value = { id: 'chat', messages: [], runs: [] }
    await flushPromises()
    await memory.propose(7)
    expect(api.proposeMemory).toHaveBeenCalledWith('algorithm_coach', 'chat', { task_revision: 1, profile_revision: 1, preferences_revision: 1, source_message_id: 7 })
    expect(chat.conversation.value.runs).toEqual([run])
    scope.stop()
  })

  it('exports a distinct day-11 report with memory and call snapshots', () => {
    const text = memoryReport('Задача', [{ purpose: 'memory_proposals', status: 'error', estimate: {},
      memory_context: { task_revision: 2 }, error_code: 'invalid_memory_proposals' }], { messages: [] }, workspace())
    expect(text).toContain('День 11')
    expect(text).toContain('Предложения памяти')
    expect(text).toContain('"task_revision": 2')
    expect(text).toContain('invalid_memory_proposals')
    expect(text).toContain('Вызовов без usage: 1')
  })
})
