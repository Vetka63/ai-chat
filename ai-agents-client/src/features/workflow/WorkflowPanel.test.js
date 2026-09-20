import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import WorkflowPanel from './WorkflowPanel.vue'

const workspace = (phase, candidate = 10, status = 'active') => ({
  task_id: 'task-1', state: { phase, status, current_step_id: null,
    candidate_message_id: candidate, expected_action: 'accept_plan', revision: 2 },
  candidate_text: null, artifacts: [], events: [],
})

describe('Day 13 workflow panel', () => {
  it('prefills a plan from the response and confirms edited steps without copying', async () => {
    const wrapper = mount(WorkflowPanel, { props: {
      workspace: { ...workspace('planning'), candidate_text:
        '## Шаги\n1. Использовать словарь\n2. Найти дополнение\n## Сложность\n- O(n)' },
    } })
    expect(wrapper.get('textarea').element.value).toBe('Использовать словарь\nНайти дополнение')
    await wrapper.get('textarea').setValue('Словарь\nЦикл')
    await wrapper.get('.confirm').trigger('click')
    expect(wrapper.emitted('apply')[0]).toEqual(['accept_plan', { content: { steps: ['Словарь', 'Цикл'] } }])
  })

  it('shows pause and blocks confirmation while paused', async () => {
    const wrapper = mount(WorkflowPanel, { props: {
      workspace: { ...workspace('execution', 11, 'paused'), candidate_text: 'def two_sum(): pass' },
    } })
    expect(wrapper.text()).toContain('На паузе')
    expect(wrapper.get('.confirm').attributes('disabled')).toBeDefined()
    await wrapper.get('.workflow-actions button').trigger('click')
    expect(wrapper.emitted('apply')[0]).toEqual(['resume'])
  })

  it('keeps the chat compact until materials are opened and lets the user expand them', async () => {
    const wrapper = mount(WorkflowPanel, { props: {
      workspace: { ...workspace('execution', null), state: {
        ...workspace('execution', null).state, expected_action: 'work_on_step', current_step_id: 'step-1',
      }, artifacts: [{ id: 'artifact-1', kind: 'plan', revision: 1,
        created_at: '2026-01-01', content: { steps: [{ id: 'step-1', title: 'Найти дополнение' }] } }],
      events: [{ action: 'accept_plan', revision: 1, from_phase: 'planning', to_phase: 'execution',
        to_status: 'active', created_at: '2026-01-01' }] },
    } })
    expect(wrapper.get('.phases [aria-current="step"]').text()).toContain('Реализация')
    expect(wrapper.find('.task-tray').exists()).toBe(false)
    await wrapper.get('.task-tabs button:nth-child(1)').trigger('click')
    expect(wrapper.get('.task-tray').text()).toContain('Найти дополнение')
    await wrapper.get('[aria-label="Растянуть область материалов"]').trigger('click')
    expect(wrapper.get('.task-tray').classes()).toContain('expanded')
    await wrapper.get('[aria-label="Свернуть материалы задачи"]').trigger('click')
    expect(wrapper.find('.task-tray').exists()).toBe(false)
    await wrapper.get('.task-tabs button:nth-child(2)').trigger('click')
    expect(wrapper.get('.task-tray').text()).toContain('План принят')
  })
})
