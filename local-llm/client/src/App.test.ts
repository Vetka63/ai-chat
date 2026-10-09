import { shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import App from './App.vue'
import ChatView from './features/chat/ChatView.vue'

beforeEach(() => { window.location.hash = '' })
describe('local app sections', () => {
  it('opens day29 directly without importing RAG', () => {
    window.location.hash = '#optimization/saved'
    const wrapper = shallowMount(App)
    expect(wrapper.find('optimization-view-stub').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('День 28'); wrapper.unmount()
  })
  it('opens chat by default and keeps day26 reachable through navigation and direct URL', async () => {
    const wrapper = shallowMount(App)
    expect(wrapper.find('chat-view-stub').exists()).toBe(true)
    await wrapper.findAll('nav button')[1]!.trigger('click')
    expect(wrapper.find('lab-view-stub').exists()).toBe(true)
    expect(window.location.hash).toBe('#lab')
    wrapper.unmount()
    const reloaded = shallowMount(App)
    expect(reloaded.find('lab-view-stub').exists()).toBe(true)
    reloaded.unmount()
  })
  it('cannot abandon a generation by switching sections until busy releases', async () => {
    const wrapper = shallowMount(App)
    wrapper.findComponent(ChatView).vm.$emit('busy', true); await nextTick()
    expect(wrapper.findAll('nav button').every(b => b.attributes('disabled') !== undefined)).toBe(true)
    wrapper.findComponent(ChatView).vm.$emit('busy', false); await nextTick()
    expect(wrapper.findAll('nav button').every(b => b.attributes('disabled') === undefined)).toBe(true)
    wrapper.unmount()
  })
  it('follows direct fragment changes and browser history between sections', async () => {
    const wrapper = shallowMount(App)
    window.location.hash = '#optimization/saved'; window.dispatchEvent(new HashChangeEvent('hashchange')); await nextTick()
    expect(wrapper.find('optimization-view-stub').exists()).toBe(true)
    window.location.hash = '#lab'; window.dispatchEvent(new HashChangeEvent('hashchange')); await nextTick()
    expect(wrapper.find('lab-view-stub').exists()).toBe(true); wrapper.unmount()
  })
  it('defers external section change until the current generation releases', async () => {
    const wrapper = shallowMount(App); wrapper.findComponent(ChatView).vm.$emit('busy', true); await nextTick()
    window.location.hash = '#optimization/saved'; window.dispatchEvent(new HashChangeEvent('hashchange')); await nextTick()
    expect(wrapper.find('chat-view-stub').exists()).toBe(true)
    wrapper.findComponent(ChatView).vm.$emit('busy', false); await nextTick()
    expect(wrapper.find('optimization-view-stub').exists()).toBe(true); wrapper.unmount()
  })
})
