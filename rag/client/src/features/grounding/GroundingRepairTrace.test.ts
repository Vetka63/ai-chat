import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import type { Schema } from '../../api/client'
import GroundingRepairTrace from './GroundingRepairTrace.vue'

const generation = (model: string, rawJson: string): Schema<'GroundingGeneration'> => ({ model, rawJson, finishReason: 'stop', milliseconds: 20, usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15, cacheHitTokens: null, cacheMissTokens: null }, estimatedCost: null, messages: [] })

describe('bounded repair diagnostics', () => {
  it('keeps original rejected claims and reasons inside a closed diagnostic block and escapes markup', () => {
    const rejected = '<script>window.fake=true</script>Отклонённый вывод'
    const repair: Schema<'GroundingRepair'> = {
      originalGeneration: generation('draft-model', JSON.stringify({ claims: [{ text: rejected }, { text: 'Другой пункт' }] })),
      originalSupportCheck: { status: 'REJECTED', claims: [{ claimIndex: 0, verdict: 'UNSUPPORTED', reason: 'Не учтено условие источника.' }, { claimIndex: 1, verdict: 'SUPPORTED', reason: 'Подтверждено.' }], issues: [], generation: generation('judge-model', '{}') },
    }
    const wrapper = mount(GroundingRepairTrace, { props: { repair } })
    expect(wrapper.element.tagName).toBe('DETAILS')
    expect(wrapper.attributes('open')).toBeUndefined()
    expect(wrapper.find('summary').text()).toBe('Исправление черновика · 1 попытка')
    expect(wrapper.findAll('.rejected-draft-claim')).toHaveLength(1)
    expect(wrapper.find('.rejected-draft-claim').text()).toContain(rejected)
    expect(wrapper.find('.rejected-draft-claim').text()).toContain('Не учтено условие источника.')
    expect(wrapper.text()).toContain('draft-model'); expect(wrapper.text()).toContain('judge-model')
    expect(wrapper.text()).toContain('10 / 5 / 15')
    expect(wrapper.text()).toContain('уже учтён в общей сумме')
    expect(wrapper.findAll('script')).toHaveLength(0)
    expect(wrapper.html()).toContain('&lt;script&gt;')
  })

  it('handles unavailable draft parsing and unknown usage without inventing text', () => {
    const repair: Schema<'GroundingRepair'> = {
      originalGeneration: { ...generation('draft', 'invalid JSON'), usage: null },
      originalSupportCheck: { status: 'REJECTED', claims: [{ claimIndex: 0, verdict: 'CONTRADICTED', reason: 'Есть противоречие.' }], issues: [], generation: { ...generation('judge', '{}'), usage: null } },
    }
    const wrapper = mount(GroundingRepairTrace, { props: { repair } })
    expect(wrapper.attributes('open')).toBeUndefined()
    expect(wrapper.text()).toContain('Текст пункта доступен в исходном JSON.')
    expect(wrapper.text()).toContain('Неизвестно')
  })
})
