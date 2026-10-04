import { expect, test } from '@playwright/test'

const settings = { configured: true, model: 'test-model', temperature: 0, thinking: 'disabled', defaultContextMaxCharacters: 16000, maxOutputTokensDefault: null, priceSource: 'https://api-docs.deepseek.com/quick_start/pricing/' }
function answer(body: Record<string, unknown>) {
  return { id: 'id', createdAt: 'now', question: body.question, mode: body.mode, model: 'test-model', temperature: 0, thinking: 'disabled', answer: `${body.mode}: <script>window.evil=true</script>`, finishReason: 'stop', truncated: false, maxOutputTokens: body.maxOutputTokens, totalMilliseconds: 10, generationMilliseconds: 10, usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15, cacheHitTokens: 0, cacheMissTokens: 10 }, estimatedCost: null, context: { indexId: null, snapshotId: null, retrievedCount: 0, included: [], omittedChunkIds: [], textCharacters: 0, maxCharacters: 16000, retrievalMilliseconds: 0, embeddingInputTokens: null }, messages: [], warnings: [] }
}

test('answer comparison snapshots settings displays safe text and exports', async ({ page }) => {
  const requests: Record<string, unknown>[] = []
  await page.route('**/api/v1/answer-settings', route => route.fulfill({ json: settings }))
  await page.route('**/api/v1/answers', async route => {
    const body = route.request().postDataJSON(); requests.push(body)
    expect(route.request().headers().authorization).toBeUndefined()
    await route.fulfill({ json: answer(body) })
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'Ответы с RAG / без RAG' }).click()
  await expect(page.getByRole('combobox', { name: 'Контрольный вопрос' }).locator('option')).toHaveCount(11)
  await page.getByRole('textbox', { name: 'Вопрос для сравнения' }).fill('Тестовый вопрос')
  await page.getByRole('button', { name: 'Сравнить ответы · 2 API-вызова' }).click()
  await expect(page.locator('.answer-text')).toHaveCount(2)
  expect(requests).toHaveLength(2)
  expect(requests[0]?.question).toBe(requests[1]?.question)
  expect(requests[0]?.maxOutputTokens).toBeNull()
  expect(await page.evaluate(() => (window as unknown as { evil?: boolean }).evil)).toBeUndefined()
  const downloaded = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Скачать сравнение MD' }).click()
  expect((await downloaded).suggestedFilename()).toMatch(/\.md$/)
  await page.getByRole('button', { name: 'Корпус документов' }).click()
  await page.getByRole('button', { name: 'Ответы с RAG / без RAG' }).click()
  await expect(page.locator('.answer-text')).toHaveCount(2)
})

test('one failed mode keeps successful answer and mobile is contained', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/v1/answer-settings', route => route.fulfill({ json: settings }))
  await page.route('**/api/v1/answers', route => {
    const body = route.request().postDataJSON()
    return body.mode === 'RAG' ? route.fulfill({ status: 502, json: { code: 'llm_provider_error', message: 'Тестовая ошибка провайдера' } }) : route.fulfill({ json: answer(body) })
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'Ответы с RAG / без RAG' }).click()
  await page.getByRole('button', { name: 'Сравнить ответы · 2 API-вызова' }).click()
  await expect(page.locator('.answer-text')).toHaveCount(1)
  await expect(page.getByRole('alert')).toContainText('Тестовая ошибка')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/day22-mobile.png', fullPage: true })
})

test('live UI comparison and persisted source @live', async ({ page }) => {
  test.skip(process.env.RAG_LIVE !== 'true', 'Opt-in: two paid DeepSeek requests')
  test.setTimeout(180_000)
  await page.goto('/')
  await page.getByRole('button', { name: 'Ответы с RAG / без RAG' }).click()
  await page.getByRole('combobox', { name: 'Контрольный вопрос' }).selectOption('stash')
  await page.getByRole('spinbutton', { name: 'Лимит ответа, токены' }).fill('1200')
  await page.getByRole('button', { name: 'Сравнить ответы · 2 API-вызова' }).click()
  await expect(page.locator('.answer-text')).toHaveCount(2, { timeout: 150_000 })
  await expect(page.locator('.answer-card[data-mode=RAG] .answer-text')).toContainText(/-u|include-untracked/)
  await page.locator('.answer-card[data-mode=RAG]').getByText('Переданный контекст', { exact: false }).click()
  await page.getByRole('button', { name: 'Открыть источник ответа' }).first().click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('button', { name: 'Закрыть источник ответа' }).click()
  await page.screenshot({ path: 'test-results/day22-desktop.png', fullPage: true })
})
