import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import TaskPanel from './TaskPanel.vue'
import { memoryReport } from '../tokens/report'

const button = (w, name) => w.findAll('button').find(b => b.text().startsWith(name))
const plan = { id: 'p2', kind: 'plan', revision: 2, task_revision: 1, invariant_revision: 1, checked_workflow_version: 15, content: { steps: [{ id: 's', title: 'Словарь' }] } }
const flow = extra => ({ task_id: 't', state: { phase: 'planning', status: 'active', revision: 3 }, artifacts: [plan], events: [], allowed_events: ['approve_plan', 'pause'], blocked_events: {}, ...extra })

describe('Day 15 approvals UI', () => {
  it('sends the visible exact plan ID when approving', async () => {
    const w = mount(TaskPanel, { props: { workspace: flow() } })
    expect(button(w, 'Утвердить план').text()).toContain('v2')
    await button(w, 'Утвердить план').trigger('click')
    expect(w.emitted('transition')[0]).toEqual([{ event: 'approve_plan', artifact_id: 'p2' }])
    w.unmount()
  })
  it('shows backend blocking reason instead of an enabled approval', () => {
    const w = mount(TaskPanel, { props: { workspace: flow({ artifacts: [], allowed_events: ['pause'], blocked_events: { approve_plan: 'Сохраните актуальный план' } }) } })
    expect(button(w, 'Утвердить план')).toBeUndefined()
    expect(w.text()).toContain('Сохраните актуальный план')
    w.unmount()
  })
  it('requires explicit blocker review and sends blockers in the report', async () => {
    const w = mount(TaskPanel, { props: { workspace: flow({ state: { phase: 'validation', status: 'active', approved_plan_id: 'p2' }, allowed_events: [] }) } })
    await w.findAll('textarea')[1].setValue('Проверка решения')
    expect(button(w, 'Сохранить новую').attributes('disabled')).toBeDefined()
    await w.findAll('textarea')[2].setValue('Ошибка на пустом входе')
    await w.get('input[type=checkbox]').setValue(true)
    await button(w, 'Сохранить новую').trigger('click')
    expect(w.emitted('save')[0][0].content.blocking_issues).toEqual(['Ошибка на пустом входе'])
    w.unmount()
  })
  it('sends remarks with the selected step on request_changes', async () => {
    const w = mount(TaskPanel, { props: { workspace: flow({ state: { phase: 'validation', status: 'active', current_step_id: 's', approved_plan_id: 'p2' }, allowed_events: ['request_changes'] }) } })
    expect(button(w, 'На доработку').attributes('disabled')).toBeDefined()
    await w.findAll('textarea')[0].setValue('Исправить пустой массив')
    await button(w, 'На доработку').trigger('click')
    expect(w.emitted('transition')[0]).toEqual([{ event: 'request_changes', remarks: 'Исправить пустой массив', step_id: 's' }])
    w.unmount()
  })
  it('exports exact confirmation links and blocked actions', () => {
    const report = memoryReport('Задача', [], {}, { workflow: flow({ state: { phase: 'done', status: 'active', approved_plan_revision: 2, accepted_validation_id: 'r3', validated_solution_revision: 4 } }) })
    expect(report).toContain('Утверждён план v2')
    expect(report).toContain('Принятый отчёт: r3')
    expect(report).toContain('проверенное решение v4')
  })
})
