import { expect, test } from '@playwright/test'

const settings = { configured: true, model: 'test', temperature: 0, thinking: 'disabled', defaultContextMaxCharacters: 16000, maxOutputTokensDefault: null, priceSource: '' }
function response(request: Record<string, any>) {
  return { request, snapshotId: 'snapshot', rewrite: null, rewriteError: null, totalMilliseconds: 10, llmStagesAttempted: 0, totalUsage: null, estimatedCost: null, warnings: ['Cosine не вероятность'], results: request.modes.map((mode: string) => ({ mode, status: 'NO_CONTEXT', message: 'После отбора не осталось фрагментов. Ответ LLM не запрашивался.', error: null, generationAttempted: false, answer: null, pipeline: { originalQuestion: request.question, searchQuery: request.question, filterApplied: mode.includes('FILTERED'), thresholdApplied: mode.includes('FILTERED') ? request.similarityThreshold : null, rawCandidates: [], selectedCandidates: [], decisions: [], retrievalMilliseconds: 1, embeddingInputTokens: 1 } })) }
}
test('experiment snapshots settings exports persists and blocks invalid K', async ({ page }) => {
  let body: Record<string, any> = {}
  await page.route('**/api/v1/answer-settings', r => r.fulfill({ json: settings }))
  await page.route('**/api/v1/experiments/compare', r => { body = r.request().postDataJSON(); return r.fulfill({ json: response(body) }) })
  await page.goto('/')
  await page.getByRole('button', { name: 'Фильтр и rewrite' }).click()
  await page.getByRole('spinbutton', { name: 'Кандидатов до отбора' }).fill('2')
  await expect(page.getByRole('button', { name: /^Сравнить ответы/ })).toBeDisabled()
  await page.getByRole('spinbutton', { name: 'Кандидатов до отбора' }).fill('10')
  await page.getByRole('button', { name: /^Сравнить ответы/ }).click()
  await expect(page.locator('.experiment-results .answer-card')).toHaveCount(3)
  expect(body.maxOutputTokens).toBeNull(); expect(body.generateAnswers).toBe(true)
  await expect(page.locator('.experiment-results')).toContainText('LLM не запрашивался')
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Скачать эксперимент MD' }).click()
  expect((await download).suggestedFilename()).toMatch(/\.md$/)
  await page.getByRole('button', { name: 'Корпус документов' }).click()
  await page.getByRole('button', { name: 'Фильтр и rewrite' }).click()
  await expect(page.locator('.experiment-results .answer-card')).toHaveCount(3)
})
test('mobile preview clearly states cost and one mode error remains isolated', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/v1/answer-settings', r => r.fulfill({ json: settings }))
  await page.route('**/api/v1/experiments/compare', r => {
    const result = response(r.request().postDataJSON()); result.results[0].status = 'ERROR'; result.results[0].error = { code: 'stage_error', message: 'Тестовая ошибка' } as any
    return r.fulfill({ json: result })
  })
  await page.goto('/')
  await page.getByRole('button', { name: 'Фильтр и rewrite' }).click()
  await page.getByRole('button', { name: /^Только поиск/ }).click()
  await expect(page.getByRole('alert')).toContainText('Тестовая ошибка')
  await expect(page.locator('.experiment-results .answer-card')).toHaveCount(3)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/day23-mobile.png', fullPage: true })
})
test('live four modes share rewrite and open sources @live', async ({ page }) => {
  test.skip(process.env.RAG_LIVE !== 'true', 'Opt-in: up to 5 paid DeepSeek calls')
  test.setTimeout(240_000)
  await page.goto('/')
  await page.getByRole('button', { name: 'Фильтр и rewrite' }).click()
  await page.getByRole('combobox', { name: 'Контрольный вопрос дня 23' }).selectOption('stash')
  await page.getByRole('checkbox', { name: 'RAG + rewrite', exact: true }).check()
  await page.getByRole('spinbutton', { name: 'Лимит ответа, токены' }).fill('1200')
  await page.getByRole('button', { name: /^Сравнить ответы/ }).click()
  await expect(page.locator('.experiment-results .answer-text')).toHaveCount(4, { timeout: 180_000 })
  await expect(page.locator('.rewrite-panel')).toContainText('Общий rewrite')
  await expect(page.locator('.experiment-results .answer-card[data-mode=FILTERED]')).toContainText(/-u|include-untracked/)
  await page.locator('.experiment-results .answer-card').first().getByText('Кандидаты до и после отбора', { exact: true }).click()
  await page.locator('.experiment-results .answer-card').first().locator('details details').first().locator('summary').click()
  await page.getByRole('button', { name: 'Открыть источник эксперимента' }).first().click()
  await expect(page.getByRole('dialog')).toBeVisible(); await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0)
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: 'test-results/day23-desktop.png', fullPage: false })
})
