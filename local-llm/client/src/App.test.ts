import { shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import App from './App.vue'
import ChatView from './features/chat/ChatView.vue'

beforeEach(() => { window.location.hash = '' })
describe('local app sections', () => {
  it('responds to direct hash navigation and defers it while busy', async () => {
    const wrapper = shallowMount(App)
    wrapper.findComponent(ChatView).vm.$emit('busy', true); await nextTick()
    window.location.hash = '#rag/saved'; window.dispatchEvent(new HashChangeEvent('hashchange')); await nextTick()
    expect(wrapper.find('chat-view-stub').exists()).toBe(true)
    wrapper.findComponent(ChatView).vm.$emit('busy', false); await nextTick()
    expect(wrapper.find('rag-view-stub').exists()).toBe(true)
    window.location.hash = '#lab'; window.dispatchEvent(new HashChangeEvent('hashchange')); await nextTick()
    expect(wrapper.find('lab-view-stub').exists()).toBe(true); wrapper.unmount()
  })
  it('opens day28 direct links and keeps old sections available', () => {
    window.location.hash = '#rag/example'
    const wrapper = shallowMount(App)
    expect(wrapper.find('rag-view-stub').exists()).toBe(true)
    expect(wrapper.findAll('nav button')).toHaveLength(3)
    wrapper.unmount()
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
})
