import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ContextPanel from './ContextPanel.vue'
const settings = { mode: 'summary', keep_last: 10, summarize_every: 10 }
describe('context settings', () => {
  it('emits settings and does not trigger a paid summary from preview', async () => {
    const w = mount(ContextPanel, { props: { settings, hasConversation: true, estimate: { pending_summary: true } } })
    expect(w.text()).toContain('сначала создадим новую сводку')
    await w.findAll('input[type=number]')[0].setValue(4)
    expect(w.text()).toContain('Изменения ещё не применены')
    await w.get('form').trigger('submit')
    expect(w.emitted('change')[0][0]).toEqual({ ...settings, keep_last: 4 })
    expect(w.emitted('fork')).toBeUndefined()
    w.unmount()
  })

  it('shows server progress and the applied threshold independently of draft settings', async () => {
    const w = mount(ContextPanel, { props: { settings: { mode: 'summary', keep_last: 10, summarize_every: 4 },
      estimate: { history_message_count: 14, unsummarized_old_messages: 4, messages_until_summary: 0, retained_message_count: 14, pending_summary: true } } })
    expect(w.get('.memory-progress').text()).toContain('Порог достигнут')
    expect(w.text()).toContain('минимум 10 целиком, порог 4')
    await w.findAll('input[type=number]')[1].setValue(8)
    expect(w.text()).toContain('Изменения ещё не применены')
    expect(w.get('.memory-progress').text()).toContain('4 / 4')
    w.unmount()
  })
  it('shows stored summary without rendering HTML from model', () => {
    const w = mount(ContextPanel, { props: { settings, summary: { text: '<script>bad()</script>', covered_messages: 10, revision: 2 } } })
    expect(w.text()).toContain('<script>bad()</script>')
    expect(w.find('script').exists()).toBe(false)
    w.unmount()
  })
})
