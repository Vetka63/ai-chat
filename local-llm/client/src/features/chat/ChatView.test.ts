import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ChatView from './ChatView.vue'
import { api } from '../../api'
import { chatApi, type Chat, type ChatMessage, type SendMessage } from './api'
vi.mock('../../api', () => ({ api: { runtime: vi.fn() } }))
vi.mock('./api', () => ({ chatApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), delete: vi.fn(), send: vi.fn(), retry: vi.fn() } }))
const params: SendMessage = { requestId: 'request', content: 'Меня зовут Сергей', temperature: .7, thinking: false, maxOutputTokens: null }
const user: ChatMessage = { id: 'user', requestId: 'request', role: 'USER', content: params.content, status: 'COMPLETED', createdAt: '2026-10-06T00:00:00Z', parameters: params, generation: null, elapsedMilliseconds: 20, contextMessageCount: 1, error: null }
const assistant: ChatMessage = { ...user, id: 'assistant', role: 'ASSISTANT', content: 'Привет, Сергей!', parameters: null, generation: { answer: 'Привет, Сергей!', thinking: null, doneReason: 'stop', inputTokens: 50, outputTokens: 6, totalMilliseconds: 20, loadMilliseconds: 1, tokensPerSecond: 100 } }
const chat: Chat = { id: 'chat', title: 'Проверка памяти', model: 'qwen3:8b', createdAt: user.createdAt, updatedAt: user.createdAt, systemPrompt: 'system', messages: [user, assistant] }
beforeEach(() => {
  vi.clearAllMocks()
  HTMLElement.prototype.scrollIntoView = vi.fn()
  vi.mocked(api.runtime).mockResolvedValue({ connected: true, version: 'test', configuredModels: ['qwen3:8b'], models: [{ name: 'qwen3:8b', sizeBytes: 5200000000, parameterSize: '8B', quantization: 'Q4_K_M' }], error: null })
  vi.mocked(chatApi.list).mockResolvedValue([{ id: chat.id, title: chat.title, model: chat.model, updatedAt: chat.updatedAt, messageCount: 2 }])
  vi.mocked(chatApi.get).mockResolvedValue(chat)
  vi.mocked(chatApi.create).mockResolvedValue({ ...chat, messages: [] })
  vi.mocked(chatApi.send).mockResolvedValue(chat)
  vi.mocked(chatApi.retry).mockResolvedValue(chat)
  vi.mocked(chatApi.delete).mockResolvedValue(undefined)
})
describe('local chat UI', () => {
  it('restores server chat without generating and scrolls to last message', async () => {
    const wrapper = mount(ChatView); await flushPromises()
    expect(wrapper.text()).toContain('Привет, Сергей!')
    expect(chatApi.send).not.toHaveBeenCalled()
    expect(HTMLElement.prototype.scrollIntoView).toHaveBeenCalled()
    expect(wrapper.find('.chat-composer textarea').exists()).toBe(true)
    wrapper.unmount()
  })
  it('sends only new text with requestId not browser-provided history or model', async () => {
    const wrapper = mount(ChatView); await flushPromises()
    await wrapper.find('textarea').setValue('Как меня зовут?')
    await wrapper.find('.chat-composer').trigger('submit'); await flushPromises()
    expect(chatApi.send).toHaveBeenCalledWith('chat', { requestId: expect.any(String), content: 'Как меня зовут?', temperature: .7, thinking: false, maxOutputTokens: null })
    wrapper.unmount()
  })
  it('keeps failed user visible and explicit retry does not send another user message', async () => {
    vi.mocked(chatApi.get).mockResolvedValue({ ...chat, messages: [{ ...user, status: 'FAILED', error: 'Ollama offline' }] })
    const wrapper = mount(ChatView); await flushPromises()
    expect(wrapper.text()).toContain('Меня зовут Сергей')
    expect(wrapper.text()).toContain('Ollama offline')
    await wrapper.findAll('button').find(b => b.text() === 'Повторить ответ')!.trigger('click'); await flushPromises()
    expect(chatApi.retry).toHaveBeenCalledWith('chat', 'request')
    expect(chatApi.send).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('requires delete confirmation and leaves other history actions unchanged on cancel', async () => {
    const wrapper = mount(ChatView); await flushPromises()
    await wrapper.find('button[aria-label="Удалить чат Проверка памяти"]').trigger('click')
    expect(chatApi.delete).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(b => b.text() === 'Отмена')!.trigger('click')
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
    await wrapper.find('button[aria-label="Удалить чат Проверка памяти"]').trigger('click')
    await wrapper.findAll('button').find(b => b.text() === 'Удалить навсегда')!.trigger('click'); await flushPromises()
    expect(chatApi.delete).toHaveBeenCalledWith('chat')
    wrapper.unmount()
  })
  it('renders LLM HTML as text and exposes truncated status', async () => {
    vi.mocked(chatApi.get).mockResolvedValue({ ...chat, messages: [user, { ...assistant, content: '<img src=x onerror=alert(1)>', status: 'TRUNCATED' }] })
    const wrapper = mount(ChatView); await flushPromises()
    expect(wrapper.find('.chat-message-content img').exists()).toBe(false)
    expect(wrapper.text()).toContain('Ответ обрезан')
    wrapper.unmount()
  })
  it('hides and restores settings without losing conversation', async () => {
    const wrapper = mount(ChatView); await flushPromises()
    await wrapper.find('.chat-header button').trigger('click')
    expect(wrapper.find('.chat-settings').exists()).toBe(false)
    expect(wrapper.text()).toContain('Привет, Сергей!')
    await wrapper.find('.chat-header button').trigger('click')
    expect(wrapper.find('.chat-settings').exists()).toBe(true)
    wrapper.unmount()
  })
  it('prevents duplicate submits while model is answering', async () => {
    let finish!: (value: Chat) => void
    vi.mocked(chatApi.send).mockReturnValue(new Promise(resolve => { finish = resolve }))
    const wrapper = mount(ChatView); await flushPromises()
    await wrapper.find('textarea').setValue('новый вопрос')
    await wrapper.find('.chat-composer').trigger('submit'); await flushPromises()
    await wrapper.find('.chat-composer').trigger('submit')
    expect(chatApi.send).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('новый вопрос')
    finish(chat); await flushPromises(); wrapper.unmount()
  })
  it('lost response retries original requestId instead of a fresh user turn', async () => {
    const wrapper = mount(ChatView); await flushPromises()
    vi.mocked(chatApi.send).mockRejectedValueOnce(new Error('network lost'))
    await wrapper.find('textarea').setValue('новый вопрос'); await wrapper.find('.chat-composer').trigger('submit'); await flushPromises()
    const original = vi.mocked(chatApi.send).mock.calls[0]![1]
    expect(wrapper.text()).toContain('Повторить безопасно')
    await wrapper.findAll('button').find(b => b.text() === 'Повторить безопасно')!.trigger('click'); await flushPromises()
    expect(vi.mocked(chatApi.send).mock.calls[1]![1]).toEqual(original)
    wrapper.unmount()
  })
  it('rate limit rejection restores draft without ambiguous replay or automatic retry', async () => {
    vi.mocked(chatApi.send).mockRejectedValue(Object.assign(new Error('Повторите через 60 с'), { status: 429 }))
    const wrapper = mount(ChatView); await flushPromises()
    await wrapper.find('textarea').setValue('test rate'); await wrapper.find('.chat-composer').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('Повторите через 60 с'); expect(wrapper.text()).not.toContain('Повторить безопасно')
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe('test rate')
    expect(chatApi.send).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
  it('VPN HTTP generates a valid UUID without secure-context randomUUID', async () => {
    const wrapper = mount(ChatView); await flushPromises()
    await wrapper.find('textarea').setValue('test HTTP'); await wrapper.find('.chat-composer').trigger('submit'); await flushPromises()
    expect(vi.mocked(chatApi.send).mock.calls[0]![1].requestId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    wrapper.unmount()
  })
})
