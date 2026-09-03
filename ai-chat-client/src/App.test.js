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
  {
    id: 'day3-reasoning',
    name: 'День 3 · Решение задачи',
    description: 'Сравнение четырёх способов рассуждения',
    experienceType: 'reasoning-experiment',
    defaultResponseMode: 'experiment',
    responseModes: [
      { id: 'experiment', name: 'Сравнение подходов', description: 'Четыре способа' },
    ],
  },
  {
    id: 'day4-temperature',
    name: 'День 4 · Температура',
    description: 'Сравнение ответов с разной температурой',
    experienceType: 'temperature-experiment',
    defaultResponseMode: 'experiment',
    responseModes: [
      { id: 'experiment', name: 'Сравнение температур', description: 'Три температуры' },
    ],
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

  it('runs the Day 3 experiment and renders all strategy shapes', async () => {
    const metrics = (apiCalls, totalTokens) => ({
      apiCalls,
      elapsedMs: apiCalls * 100,
      apiDurationMs: apiCalls * 120,
      promptTokens: totalTokens - 20,
      completionTokens: 20,
      totalTokens,
      promptCacheHitTokens: 0,
      promptCacheMissTokens: totalTokens - 20,
      reasoningTokens: 0,
      estimatedCostUsd: null,
    })
    const experiment = {
      experimentId: 'abcd1234-0000-0000-0000-000000000000',
      profileId: 'day3-reasoning',
      task: 'Реши логическую задачу',
      results: [
        {
          strategy: 'direct',
          title: 'Прямой ответ',
          description: 'Без дополнительных инструкций',
          status: 'success',
          answer: 'Прямое решение',
          experts: [],
          metrics: metrics(1, 100),
          model: 'deepseek-chat',
        },
        {
          strategy: 'step-by-step',
          title: 'Пошаговое решение',
          description: 'С инструкцией',
          status: 'success',
          answer: 'Пошаговое решение задачи',
          experts: [],
          metrics: metrics(1, 120),
          model: 'deepseek-chat',
        },
        {
          strategy: 'meta-prompt',
          title: 'Промпт для решения',
          description: 'Сначала промпт, затем решение',
          status: 'success',
          answer: 'Решение по созданному промпту',
          generatedPrompt: 'Проверь данные и найди строгий ответ',
          experts: [],
          metrics: metrics(2, 220),
          model: 'deepseek-chat',
        },
        {
          strategy: 'expert-panel',
          title: 'Группа экспертов',
          description: 'Три точки зрения',
          status: 'success',
          answer: 'Общий ответ',
          experts: [
            { role: 'Аналитик', solution: 'Аналитическое решение' },
            { role: 'Инженер', solution: 'Практическое решение' },
            { role: 'Критик', solution: 'Проверка решения' },
          ],
          consensus: 'Согласованный итог',
          comparison: 'Аналитик и инженер совпали, критик проверил крайний случай',
          confidence: 'high',
          metrics: metrics(4, 440),
          model: 'deepseek-chat',
        },
      ],
    }
    const judgeResult = {
      winnerStrategy: 'expert-panel',
      winnerTitle: 'Группа экспертов',
      evaluations: experiment.results.map((result, index) => ({
        strategy: result.strategy,
        title: result.title,
        scores: {
          correctness: 7 + index,
          clarity: 7,
          completeness: 8,
          efficiency: 7,
          edgeCases: 8,
        },
        average: 7.4 + index * 0.2,
        strengths: 'Корректное решение',
        weaknesses: 'Можно сократить объяснение',
      })),
      explanation: 'Экспертная комиссия лучше проверила крайние случаи',
      metrics: metrics(1, 180),
      model: 'deepseek-v4-pro',
      finishReason: 'stop',
    }
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => profiles })
      .mockResolvedValueOnce({ ok: true, json: async () => experiment })
      .mockResolvedValueOnce({ ok: true, json: async () => judgeResult }))

    const wrapper = mount(App)
    await flushPromises()
    await chooseDropdownOption(wrapper, 'Профиль чата', 'День 3')

    expect(wrapper.find('.experiment-workspace').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Режим ответа"]').exists()).toBe(false)

    await wrapper.get('#reasoning-task').setValue('Реши логическую задачу')
    await wrapper.get('.experiment-form').trigger('submit')
    await flushPromises()

    expect(fetch).toHaveBeenNthCalledWith(2, '/api/reasoning-experiments', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({
        task: 'Реши логическую задачу',
        profileId: 'day3-reasoning',
        strategies: [],
      }),
    }))
    expect(wrapper.findAll('.strategy-card')).toHaveLength(4)
    expect(wrapper.text()).toContain('Прямое решение')
    expect(wrapper.text()).toContain('Проверь данные и найди строгий ответ')
    expect(wrapper.text()).toContain('Аналитик')
    expect(wrapper.text()).toContain('Согласованный итог')
    expect(wrapper.text()).toContain('Высокая уверенность')
    expect(wrapper.text()).toContain('критик проверил крайний случай')
    expect(wrapper.findAll('.strategy-metrics')).toHaveLength(4)
    expect(wrapper.findAll('.experiment-metrics strong')[0].text()).toBe('8')
    expect(wrapper.findAll('.experiment-metrics strong')[1].text()).toContain('880')
    expect(wrapper.text()).toContain('На сервере история экспериментов не создаётся')

    const correctnessFive = wrapper.get(
      '[aria-label="Правильность: 5 из 5 для Прямой ответ"]',
    )
    await correctnessFive.trigger('click')
    await wrapper.get('.winner-option[data-strategy="direct"]').trigger('click')
    await wrapper.get('.evaluation-comment textarea').setValue(
      'Ответ правильный и хорошо объяснён',
    )

    expect(correctnessFive.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('.winner-option[data-strategy="direct"]').attributes('aria-checked'))
      .toBe('true')
    expect(wrapper.text()).toContain('Лучшим выбран способ «Прямой ответ»')
    expect(wrapper.get('.evaluation-comment textarea').element.value)
      .toBe('Ответ правильный и хорошо объяснён')

    await wrapper.get('.judge-button').trigger('click')
    await flushPromises()

    expect(fetch).toHaveBeenNthCalledWith(
      3,
      '/api/reasoning-experiments/judge',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          task: 'Реши логическую задачу',
          profileId: 'day3-reasoning',
          candidates: experiment.results.map((result) => ({
            strategy: result.strategy,
            answer: result.answer,
          })),
        }),
      }),
    )
    expect(wrapper.text()).toContain('Рекомендация модели')
    expect(wrapper.text()).toContain('Группа экспертов')
    expect(wrapper.text()).toContain('Экспертная комиссия лучше проверила крайние случаи')
    expect(wrapper.text()).toContain('Отличается от вашего выбора')
    expect(wrapper.get('.judge-metrics').text()).toContain('deepseek-v4-pro')
    expect(wrapper.get('.judge-metrics').text()).toContain('1 API-выз.')
  })

  it('runs the Day 4 experiment and supports a manual comparison', async () => {
    const metrics = {
      apiCalls: 3,
      elapsedMs: 900,
      apiDurationMs: 840,
      promptTokens: 300,
      completionTokens: 240,
      totalTokens: 540,
      promptCacheHitTokens: 0,
      promptCacheMissTokens: 300,
      reasoningTokens: 0,
      estimatedCostUsd: null,
    }
    const variants = [
      { id: 'precise', title: 'Точный', description: 'Предсказуемый', temperature: 0 },
      { id: 'balanced', title: 'Сбалансированный', description: 'Баланс', temperature: 0.7 },
      { id: 'creative', title: 'Творческий', description: 'Необычные идеи', temperature: 1.2 },
    ]
    const experiment = {
      experimentId: 'temp1234-0000-0000-0000-000000000000',
      profileId: 'day4-temperature',
      task: 'Придумай название приложения',
      results: variants.map((variant) => ({
        ...variant,
        status: 'success',
        answer: `Ответ ${variant.temperature}`,
        metrics: { ...metrics, apiCalls: 1, totalTokens: 180 },
        model: 'deepseek-v4-flash',
        finishReason: 'stop',
      })),
      metrics,
    }
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => profiles })
      .mockResolvedValueOnce({ ok: true, json: async () => experiment }))

    const wrapper = mount(App)
    await flushPromises()
    await chooseDropdownOption(wrapper, 'Профиль чата', 'День 4')

    expect(wrapper.find('.temperature-workspace').exists()).toBe(true)
    expect(wrapper.find('[aria-label="Режим ответа"]').exists()).toBe(false)

    await wrapper.get('#temperature-task').setValue('Придумай название приложения')
    await wrapper.get('.temperature-form').trigger('submit')
    await flushPromises()

    expect(fetch).toHaveBeenNthCalledWith(
      2,
      '/api/temperature-experiments',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          task: 'Придумай название приложения',
          profileId: 'day4-temperature',
        }),
      }),
    )
    expect(wrapper.findAll('.temperature-card')).toHaveLength(3)
    expect(wrapper.text()).toContain('Ответ 0')
    expect(wrapper.text()).toContain('Ответ 0.7')
    expect(wrapper.text()).toContain('Ответ 1.2')
    expect(wrapper.get('.experiment-metrics').text()).toContain('540')

    const creativeFive = wrapper.get('[aria-label="Креативность: 5 из 5 для Творческий"]')
    await creativeFive.trigger('click')
    await wrapper.get('.temperature-card--creative .temperature-winner').trigger('click')
    await wrapper.get('[aria-label="Разнообразие: 5 из 5"]').trigger('click')
    await wrapper.get('.temperature-conclusion textarea').setValue('Творческий ответ разнообразнее')

    expect(creativeFive.attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('.temperature-card--creative .temperature-winner').text())
      .toContain('Выбран лучшим')
    expect(wrapper.get('[aria-label="Разнообразие: 5 из 5"]').attributes('aria-pressed'))
      .toBe('true')
  })
})
