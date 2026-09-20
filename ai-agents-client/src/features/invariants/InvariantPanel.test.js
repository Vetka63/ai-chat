import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import InvariantPanel from './InvariantPanel.vue'

const props = {
  conversationId: 'chat-1',
  workspace: { revision: 1, rules: [], checks: [] },
  workflowState: { phase: 'planning', status: 'active' },
  busy: false, sending: false, error: '',
}

describe('Day 14 invariant panel', () => {
  it('adds a task-scoped rule and submits an explicit revisioned edit', async () => {
    const wrapper = mount(InvariantPanel, { props })
    expect(wrapper.text()).toContain('Пока не заданы')
    await wrapper.get('.invariant-bar button').trigger('click')
    await wrapper.get('.rule-actions button').trigger('click')
    await wrapper.get('.invariant-rule input:not([type="checkbox"])').setValue('Язык решения')
    await wrapper.get('.invariant-rule textarea').setValue('Python')
    await wrapper.get('.invariant-rule select').setValue('language')
    await wrapper.get('.save-rules').trigger('click')
    expect(wrapper.emitted('save')[0][0]).toEqual([{ id: null, kind: 'language',
      label: 'Язык решения', value: 'Python', active: true }])
  })

  it('shows a conflict and prevents edits after the plan has been accepted', async () => {
    const wrapper = mount(InvariantPanel, { props: {
      ...props, workflowState: { phase: 'execution', status: 'active' },
      workspace: { revision: 2, rules: [{ id: 'r1', kind: 'language', label: 'Язык',
        value: 'Python', active: true, revision: 1 }], checks: [{ id: 'check-1', stage: 'input', revision: 2,
        verdict: 'conflict', created_at: '2026-01-01', rules: [{ id: 'r1', label: 'Язык' }],
        checks: [{ rule_id: 'r1', verdict: 'conflict', reason: 'Запрошен JavaScript' }] }] },
    } })
    expect(wrapper.text()).toContain('Конфликт')
    await wrapper.get('.invariant-bar button').trigger('click')
    expect(wrapper.text()).toContain('только во время активного планирования')
    expect(wrapper.get('.save-rules').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.invariant-rule textarea').attributes('disabled')).toBeDefined()
  })

  it.each([{ busy: true }, { sending: true }])('does not show a phase warning during temporary activity: %j', async activity => {
    const wrapper = mount(InvariantPanel, { props: { ...props, ...activity } })
    await wrapper.get('.invariant-bar button').trigger('click')
    expect(wrapper.text()).not.toContain('только во время активного планирования')
    expect(wrapper.get('.save-rules').attributes('disabled')).toBeDefined()
  })
})
