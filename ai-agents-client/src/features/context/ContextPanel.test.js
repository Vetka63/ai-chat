import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ContextPanel from './ContextPanel.vue'
const settings = { mode: 'summary', keep_last: 10, summarize_every: 10 }
describe('context settings', () => {
  it('emits settings and does not trigger a paid summary from preview', async () => {
    const w = mount(ContextPanel, { props: { settings, hasConversation: true, estimate: { pending_summary: true } } })
    expect(w.text()).toContain('сначала создадим новую сводку')
    await w.findAll('input[type=number]')[0].setValue(4)
    await w.get('form').trigger('submit')
    expect(w.emitted('change')[0][0]).toEqual({ ...settings, keep_last: 4 })
    expect(w.emitted('fork')).toBeUndefined()
    w.unmount()
  })
  it('shows stored summary without rendering HTML from model', () => {
    const w = mount(ContextPanel, { props: { settings, summary: { text: '<script>bad()</script>', covered_messages: 10, revision: 2 } } })
    expect(w.text()).toContain('<script>bad()</script>')
    expect(w.find('script').exists()).toBe(false)
    w.unmount()
  })
})
