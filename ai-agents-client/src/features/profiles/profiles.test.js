import { computed, effectScope, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ProfilePanel from './ProfilePanel.vue'
import NewChatDialog from '../../components/NewChatDialog.vue'
import { defaultPreferences } from './options'
import { useProfiles } from './useProfiles'
import { memoryReport } from '../tokens/report'
import * as api from './api'

vi.mock('./api', () => ({ listProfiles: vi.fn(), createProfile: vi.fn(), updateProfile: vi.fn() }))
const persona = (id = 'beginner') => ({ id, name: id === 'beginner' ? 'Новичок' : 'Опытный',
  revision: 1, memory_revision: 1, preferences: defaultPreferences() })
const button = (wrapper, text) => wrapper.findAll('button').find(b => b.text() === text)
const select = (wrapper, label) => wrapper.findAll('label').find(l => l.text().startsWith(label)).get('select')
afterEach(() => vi.resetAllMocks())

describe('Day 12 profiles', () => {
  it('binds a selected profile only at creation', async () => {
    const wrapper = mount(NewChatDialog, { props: { open: true, memoryLayers: true, personalization: true,
      profiles: [persona(), persona('experienced')], defaultProfileId: 'beginner' } })
    await wrapper.get('select').setValue('experienced')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')[0][0].profileId).toBe('experienced')
    expect(wrapper.text()).toContain('Профиль фиксируется при создании')
    wrapper.unmount()
  })

  it('does not silently create a task while the profile list is unavailable', async () => {
    const wrapper = mount(NewChatDialog, { props: { open: true, memoryLayers: true, personalization: true, profiles: [] } })
    expect(button(wrapper, 'Создать чат').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('create')).toBeUndefined()
    wrapper.unmount()
  })

  it('edits typed preferences with optimistic version and preserves chat binding', async () => {
    const profile = persona()
    const wrapper = mount(ProfilePanel, { props: { profiles: [profile], profile } })
    await select(wrapper, 'Подробность').setValue('detailed')
    await select(wrapper, 'Формат объяснения').setValue('steps')
    await wrapper.get('textarea').setValue(' Без жаргона \n Примеры на маленьких массивах ')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')[0][0]).toMatchObject({
      id: 'beginner', revision: 1, preferences: { detail_level: 'detailed', response_format: 'steps',
        preferred_code_language: null, soft_constraints: ['Без жаргона', 'Примеры на маленьких массивах'] },
    })
    expect(wrapper.text()).toContain('Привязка фиксирована')
    expect(wrapper.emitted('change-profile')).toBeUndefined()
    wrapper.unmount()
  })

  it('creates a profile without copying its source ID or memory', async () => {
    const wrapper = mount(ProfilePanel, { props: { profiles: [persona()], profile: persona() } })
    await button(wrapper, 'Новый профиль').trigger('click')
    await wrapper.get('input').setValue('Мой профиль')
    await wrapper.get('form').trigger('submit')
    const command = wrapper.emitted('save')[0][0]
    expect(command).toEqual({ name: 'Мой профиль', preferences: defaultPreferences() })
    wrapper.unmount()
  })

  it('keeps an unsaved draft visible on a conflict instead of replacing it', async () => {
    const wrapper = mount(ProfilePanel, { props: { profiles: [persona()], profile: persona() } })
    await wrapper.get('input').setValue('Мой черновик')
    await wrapper.setProps({ error: 'Профиль изменён в другом окне' })
    expect(wrapper.get('input').element.value).toBe('Мой черновик')
    expect(wrapper.get('[role="alert"]').text()).toContain('другом окне')
    await wrapper.get('textarea').setValue(Array(9).fill('Строка').join('\n'))
    expect(button(wrapper, 'Сохранить профиль').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('reloads the bound workspace after profile editing and does not send chat requests', async () => {
    const scope = effectScope()
    const chat = { agent: ref({ capabilities: ['personalization'] }), agentId: ref('algorithm_coach'),
      sending: ref(false), conversation: ref({ id: 'chat', messages: [{ content: 'x' }] }) }
    const memory = { busy: ref(false), workspace: ref({ profile: persona() }), refresh: vi.fn().mockResolvedValue() }
    api.listProfiles.mockResolvedValue([persona()])
    api.updateProfile.mockResolvedValue({ ...persona(), revision: 2 })
    const state = scope.run(() => useProfiles(chat, memory))
    await flushPromises()
    await state.save({ id: 'beginner', revision: 1, name: 'Новичок', preferences: defaultPreferences() })
    expect(api.updateProfile).toHaveBeenCalledWith('beginner', { revision: 1, name: 'Новичок', preferences: defaultPreferences() })
    expect(memory.refresh).toHaveBeenCalledOnce()
    expect(state.profiles.value[0].revision).toBe(2)
    expect(chat.conversation.value.messages).toHaveLength(1)
    scope.stop()
  })

  it('does not load profiles for the legacy dialogue agent', async () => {
    const scope = effectScope()
    scope.run(() => useProfiles({ agent: ref({ capabilities: ['context_memory'] }) }, {}))
    await flushPromises()
    expect(api.listProfiles).not.toHaveBeenCalled()
    scope.stop()
  })

  it('exports the exact profile versions used for responses', () => {
    const text = memoryReport('Сравнение', [{ purpose: 'dialogue', status: 'success', estimate: { profile_tokens: 25 },
      memory_context: { profile: { ...persona(), revision: 3 } } }], { messages: [] }, { profile: persona() })
    expect(text).toContain('День 12')
    expect(text).toContain('Новичок, версия 3')
    expect(text).toContain('оценка блока ≈ 25')
    expect(text).toContain('Мягкие предпочтения')
  })
})
