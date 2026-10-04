import { expect, test } from '@playwright/test'

const source = { chunkId: 'evidence-1', documentId: 'doc', source: 'chapter/stash.asc', title: 'Stash', section: 'Untracked' }
const quote = 'Чтобы сохранить untracked-файлы, используйте git stash -u.'
function result(request: Record<string, any>, status = 'ANSWERED') {
  return { request, snapshotId: 'snapshot', status, answer: status === 'ANSWERED' ? 'Используйте -u.' : 'Ответ не прошёл проверку источников и цитат.', clarification: null,
    claims: status === 'ANSWERED' ? [{ text: 'Используйте -u.', citations: [{ source, quote, startInChunk: 0, endInChunkExclusive: quote.length, canonicalStart: 4, canonicalEndExclusive: 4 + quote.length }] }] : [], sources: status === 'ANSWERED' ? [source] : [],
    issues: status === 'ANSWERED' ? [] : [{ code: 'unknown_evidence_id', message: 'Неизвестный ID', claimIndex: 0, citationIndex: 0 }], retrieval: null, rewrite: null,
    generation: { model: 'test', finishReason: 'stop', milliseconds: 1, usage: null, estimatedCost: null, messages: [], rawJson: '<script>window.fake = true</script>НЕПРОВЕРЕННЫЙ ТЕКСТ' }, llmStagesAttempted: 1, totalUsage: null, estimatedCost: null, totalMilliseconds: 2, warnings: ['Точное совпадение не доказывает смысл.'] }
}
test('grounded sources snapshot highlight exports and tab persistence', async ({ page }) => {
  let body: Record<string, any> = {}
  await page.route('**/api/v1/grounded-answers', r => { body = r.request().postDataJSON(); return r.fulfill({ json: result(body) }) })
  await page.route('**/api/v1/indexes/*/documents/doc', r => r.fulfill({ json: { id: 'doc', title: 'Stash', source: source.source, text: 'До: ' + quote + ' После', sections: [] } }))
  await page.goto('/'); await page.getByRole('button', { name: 'Источники и цитаты' }).click()
  await expect(page.locator('.day-badge')).toHaveText('День 24')
  await page.getByRole('button', { name: /^Ответить с цитатами/ }).click()
  await expect(page.locator('.grounded-claim')).toContainText(quote)
  expect(body.maxOutputTokens).toBeNull(); expect(body.useRewrite).toBe(false)
  await page.getByRole('button', { name: 'Открыть цитату в источнике' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByText('Документ целиком с подсветкой цитаты', { exact: true }).click()
  await expect(page.locator('mark')).toHaveText(quote)
  await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0)
  const download = page.waitForEvent('download'); await page.getByRole('button', { name: 'Скачать ответ с цитатами MD' }).click()
  expect((await download).suggestedFilename()).toBe('day24-grounded.md')
  await page.getByRole('button', { name: 'Корпус документов' }).click(); await page.getByRole('button', { name: 'Источники и цитаты' }).click()
  await expect(page.locator('.grounded-claim')).toBeVisible()
})
test('invalid output quarantined mobile contained and unknown explicit', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.route('**/api/v1/grounded-answers', r => r.fulfill({ json: result(r.request().postDataJSON(), 'INVALID_EVIDENCE') }))
  await page.goto('/'); await page.getByRole('button', { name: 'Источники и цитаты' }).click()
  await page.getByRole('button', { name: /^Ответить с цитатами/ }).click()
  await expect(page.locator('.grounded-claim')).toHaveCount(0)
  await expect(page.getByText('НЕПРОВЕРЕННЫЙ ТЕКСТ', { exact: false })).not.toBeVisible()
  await page.locator('.unverified-diagnostics summary').click()
  await expect(page.locator('.unverified-diagnostics')).toContainText('НЕПРОВЕРЕННЫЙ ТЕКСТ')
  expect(await page.evaluate(() => (window as any).fake)).toBeUndefined()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/day24-mobile.png', fullPage: true })
  await page.route('**/api/v1/grounded-answers', r => r.fulfill({ json: { ...result(r.request().postDataJSON(), 'UNKNOWN'), answer: 'Не знаю по найденным материалам.', clarification: 'Уточните вопрос.', issues: [], generation: null, llmStagesAttempted: 0 } }))
  await page.getByRole('button', { name: /^Ответить с цитатами/ }).click()
  await expect(page.locator('.grounded-result')).toContainText('LLM не вызывалась')
  await expect(page.locator('.clarification')).toContainText('Уточните вопрос')
})
test('semantic verdict is visible and unsupported claim stays quarantined', async ({ page }) => {
  await page.route('**/api/v1/grounded-answers', r => r.fulfill({ json: {
    ...result(r.request().postDataJSON(), 'INVALID_EVIDENCE'), llmStagesAttempted: 2,
    supportCheck: { status: 'REJECTED', claims: [{ claimIndex: 0, verdict: 'UNSUPPORTED', reason: 'Цитата относится к другой опции.' }], issues: [], generation: { usage: null } },
  } }))
  await page.goto('/'); await page.getByRole('button', { name: 'Источники и цитаты' }).click()
  await page.getByRole('button', { name: /^Ответить с цитатами/ }).click()
  await expect(page.locator('.grounded-claim')).toHaveCount(0)
  await page.locator('summary').filter({ hasText: 'Проверка смысловой поддержки' }).click()
  await expect(page.getByText('Цитата относится к другой опции.', { exact: false })).toBeVisible()
})

test('live grounded quote opens correct immutable document @live', async ({ page }) => {
  test.skip(process.env.RAG_LIVE !== 'true', 'Opt-in: up to 2 paid DeepSeek calls')
  test.setTimeout(180_000)
  await page.goto('/'); await page.getByRole('button', { name: 'Источники и цитаты' }).click()
  await page.getByRole('button', { name: /^Ответить с цитатами/ }).click()
  await expect(page.locator('.grounded-result')).toHaveAttribute('data-status', 'ANSWERED', { timeout: 150_000 })
  await expect(page.locator('.grounded-result')).toContainText(/-u|include-untracked/)
  await page.getByRole('button', { name: 'Открыть цитату в источнике' }).first().click()
  await expect(page.getByRole('dialog')).toContainText('Цитата совпала с snapshot')
  await page.keyboard.press('Escape'); await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: 'test-results/day24-desktop.png', fullPage: false })
})
