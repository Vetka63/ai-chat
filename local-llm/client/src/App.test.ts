import { shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import App from './App.vue'
import ChatView from './features/chat/ChatView.vue'

const mountApp = () => shallowMount(App, { global: { stubs: { AuthBoundary: { props: ['busy'], emits: ['ready'], template: '<slot />' } } } })
beforeEach(() => { window.location.hash = '' })
describe('local app sections', () => {
  it('opens chat by default and keeps day26 reachable through navigation and direct URL', async () => {
    const wrapper = mountApp()
    expect(wrapper.find('chat-view-stub').exists()).toBe(true)
    await wrapper.findAll('nav button')[1]!.trigger('click')
    expect(wrapper.find('lab-view-stub').exists()).toBe(true)
    expect(window.location.hash).toBe('#lab')
    wrapper.unmount()
    const reloaded = mountApp()
    expect(reloaded.find('lab-view-stub').exists()).toBe(true)
    reloaded.unmount()
  })
  it('cannot abandon a generation by switching sections until busy releases', async () => {
    const wrapper = mountApp()
    wrapper.findComponent(ChatView).vm.$emit('busy', true); await nextTick()
    expect(wrapper.findAll('nav button').every(b => b.attributes('disabled') !== undefined)).toBe(true)
    wrapper.findComponent(ChatView).vm.$emit('busy', false); await nextTick()
    expect(wrapper.findAll('nav button').every(b => b.attributes('disabled') === undefined)).toBe(true)
    wrapper.unmount()
  })
})
