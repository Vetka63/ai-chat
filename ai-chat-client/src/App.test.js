import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

const modes = [
  { id: 'free', name: 'Без ограничений', description: 'Обычный текст' },
  { id: 'controlled', name: 'Краткий ответ', description: 'Контроль длины' },
  { id: 'json', name: 'Строгий JSON', description: 'JSON-схема' },
]

const profiles = [
  {
    id: 'general',
    name: 'Обычный чат',
    description: 'Любые вопросы',
    defaultResponseMode: 'free',
    responseModes: modes,
  },
  {
    id: 'recipe',
    name: 'Рецепты',
    description: 'Кулинарный помощник',
    defaultResponseMode: 'free',
    responseModes: modes,
  },
]

describe('App', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sends the selected response mode to the backend', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => profiles })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          reply: 'Краткий тестовый ответ',
          structuredReply: null,
          source: 'fallback',
          profileId: 'general',
          responseMode: 'controlled',
        }),
      }))

    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('[aria-label="Режим ответа"]').setValue('controlled')
    await wrapper.get('textarea').setValue('Привет')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetch).toHaveBeenNthCalledWith(2, '/api/chat', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({
        message: 'Привет',
        profileId: 'general',
        responseMode: 'controlled',
        history: [],
      }),
    }))
    expect(wrapper.text()).toContain('Краткий тестовый ответ')
    expect(wrapper.text()).toContain('controlled')
  })

  it('renders a validated structured recipe response', async () => {
    const structuredReply = {
      dishName: 'Борщ',
      requiredIngredients: ['Свёкла — 2 шт.', 'Капуста — 300 г'],
      cookingTime: '1 час 30 минут',
    }
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => profiles })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          reply: structuredReply.dishName,
          structuredReply,
          source: 'llm',
          profileId: 'recipe',
          responseMode: 'json',
        }),
      }))

    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('[aria-label="Профиль чата"]').setValue('recipe')
    await wrapper.get('[aria-label="Режим ответа"]').setValue('json')
    await wrapper.get('textarea').setValue('Дай рецепт борща')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetch).toHaveBeenNthCalledWith(2, '/api/chat', expect.objectContaining({
      body: JSON.stringify({
        message: 'Дай рецепт борща',
        profileId: 'recipe',
        responseMode: 'json',
        history: [],
      }),
    }))
    const recipe = wrapper.find('.recipe-reply')
    expect(recipe.text()).toContain('Название блюда: Борщ')
    expect(recipe.text()).toContain('Требуемые ингредиенты:')
    expect(recipe.text()).toContain('Свёкла — 2 шт.')
    expect(recipe.text()).toContain('Время готовки: 1 час 30 минут')
    expect(wrapper.find('pre.json-reply').exists()).toBe(false)
  })

  it('shows a recipe guard rejection returned by the backend', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => profiles })
      .mockResolvedValueOnce({
        ok: false,
        status: 422,
        json: async () => ({
          error: 'Профиль рецептов принимает только запросы о приготовлении блюд.',
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          reply: 'Борщ',
          structuredReply: null,
          source: 'llm',
          profileId: 'recipe',
          responseMode: 'free',
        }),
      }))

    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('[aria-label="Профиль чата"]').setValue('recipe')
    await wrapper.get('textarea').setValue('Какая сегодня погода?')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain(
      'Профиль рецептов принимает только запросы о приготовлении блюд.',
    )

    await wrapper.get('textarea').setValue('Дай рецепт борща')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fetch).toHaveBeenNthCalledWith(3, '/api/chat', expect.objectContaining({
      body: JSON.stringify({
        message: 'Дай рецепт борща',
        profileId: 'recipe',
        responseMode: 'free',
        history: [],
      }),
    }))
  })
})
