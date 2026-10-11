import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AuthBoundary from './AuthBoundary.vue'
import { privateApi } from '../../api'
vi.mock('../../api', () => ({ privateApi: { settings: vi.fn(), login: vi.fn(), logout: vi.fn() } }))
const config = { enabled: true, authenticated: false, contextTokens: 8192, contextBytes: 24576, generationsPerMinute: 10, maxConcurrentGenerations: 1, sessionSeconds: 28800 }
beforeEach(() => {
  vi.clearAllMocks(); vi.mocked(privateApi.settings).mockResolvedValue(config)
  vi.mocked(privateApi.login).mockResolvedValue({}); vi.mocked(privateApi.logout).mockResolvedValue(undefined)
})
describe('private access', () => {
  it('does not mount chat or fetch its history until authenticated', async () => {
    const wrapper = mount(AuthBoundary, { slots: { default: '<p>private history</p>' } }); await flushPromises()
    expect(wrapper.text()).not.toContain('private history'); expect(wrapper.find('input[type=password]').exists()).toBe(true)
    await wrapper.find('input').setValue('local test password')
    vi.mocked(privateApi.settings).mockResolvedValue({ ...config, authenticated: true })
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(privateApi.login).toHaveBeenCalledWith('local test password'); expect(wrapper.text()).toContain('private history')
    expect(localStorage.length).toBe(0); wrapper.unmount()
  })
  it('bad password stays on login and is cleared, no automatic retry', async () => {
    vi.mocked(privateApi.login).mockRejectedValue(new Error('Неверный пароль'))
    const wrapper = mount(AuthBoundary); await flushPromises(); await wrapper.find('input').setValue('wrong')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('Неверный пароль'); expect((wrapper.find('input').element as HTMLInputElement).value).toBe('')
    expect(privateApi.login).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
  it('session expiry immediately hides private history', async () => {
    vi.mocked(privateApi.settings).mockResolvedValue({ ...config, authenticated: true })
    const wrapper = mount(AuthBoundary, { slots: { default: 'private history' } }); await flushPromises()
    window.dispatchEvent(new Event('private-session-expired')); await flushPromises()
    expect(wrapper.text()).not.toContain('private history'); wrapper.unmount()
  })
  it('network failure is fail closed and has explicit reconnect action', async () => {
    vi.mocked(privateApi.settings).mockRejectedValue(new Error('offline'))
    const wrapper = mount(AuthBoundary, { slots: { default: 'private history' } }); await flushPromises()
    expect(wrapper.text()).not.toContain('private history'); expect(wrapper.text()).toContain('Проверить подключение'); wrapper.unmount()
  })
})
