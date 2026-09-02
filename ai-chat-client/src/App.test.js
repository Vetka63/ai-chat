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

async function chooseDropdownOption(wrapper, label, optionName) {
  await wrapper.get('[aria-label="' + label + '"]').trigger('click')
  const option = wrapper.findAll('[role="option"]')
    .find((item) => item.text().includes(optionName))
  await option.trigger('click')
}

describe('App', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    window.localStorage.clear()
  })

  it('applies and remembers the autumn experience', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => profiles,
    }))

    const wrapper = mount(App)
    await flushPromises()
    await chooseDropdownOption(wrapper, 'Тема оформления', 'Осень')

    expect(wrapper.get('main').attributes('data-theme')).toBe('autumn')
    expect(wrapper.get('.season-story').text()).toContain('Время тёплых разговоров')
    expect(wrapper.find('.seasonal-effects--autumn').exists()).toBe(true)
    expect(window.localStorage.getItem('klever-theme')).toBe('autumn')
  })

  it('renders the winter scene with a garland and festive details', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => profiles,
    }))

    const wrapper = mount(App)
    await flushPromises()
    await chooseDropdownOption(wrapper, 'Тема оформления', 'Зима')

    expect(wrapper.get('main').attributes('data-theme')).toBe('winter')
    expect(wrapper.get('.season-story--winter').text()).toContain('Новогодняя мастерская')
    expect(wrapper.find('.winter-garland').exists()).toBe(true)
    expect(wrapper.findAll('.winter-light')).toHaveLength(12)
    expect(wrapper.find('.winter-scene').text()).toContain('🎄')
    expect(window.localStorage.getItem('klever-theme')).toBe('winter')
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
    await chooseDropdownOption(wrapper, 'Режим ответа', 'Краткий ответ')
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
    expect(wrapper.text()).toContain('Краткий ответ')
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
    await chooseDropdownOption(wrapper, 'Профиль чата', 'Рецепты')
    await chooseDropdownOption(wrapper, 'Режим ответа', 'Строгий JSON')
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
    expect(recipe.text()).toContain('Готовый рецепт')
    expect(recipe.text()).toContain('Борщ')
    expect(recipe.text()).toContain('Ингредиенты')
    expect(recipe.text()).toContain('Свёкла — 2 шт.')
    expect(recipe.text()).toContain('Время готовки')
    expect(recipe.text()).toContain('1 час 30 минут')
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
    await chooseDropdownOption(wrapper, 'Профиль чата', 'Рецепты')
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
