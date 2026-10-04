import { expect, test } from '@playwright/test'
import { writeFile } from 'node:fs/promises'

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

test('one repaired draft stays collapsed while only final evidence is public', async ({ page }) => {
  const rejected = 'ИСХОДНЫЙ ЛОЖНЫЙ ВЫВОД'
  const generation = { model: 'draft-fixture', finishReason: 'stop', milliseconds: 1, usage: null, estimatedCost: null, messages: [], rawJson: JSON.stringify({ claims: [{ text: rejected }] }) }
  await page.route('**/api/v1/grounded-answers', r => r.fulfill({ json: {
    ...result(r.request().postDataJSON()), llmStagesAttempted: 4,
    repair: { originalGeneration: generation, originalSupportCheck: { status: 'REJECTED', claims: [{ claimIndex: 0, verdict: 'UNSUPPORTED', reason: 'Утверждение не подтверждено цитатой.' }], issues: [], generation: { ...generation, model: 'judge-fixture', rawJson: '{}' } } },
    supportCheck: { status: 'PASSED', claims: [{ claimIndex: 0, verdict: 'SUPPORTED', reason: 'Подтверждено.' }], issues: [], generation: { ...generation, rawJson: '{}' } },
  } }))
  await page.goto('/'); await page.getByRole('button', { name: 'Источники и цитаты' }).click()
  await expect(page.getByRole('button', { name: 'Ответить с цитатами · до 22 API-вызовов', exact: true })).toBeVisible()
  await page.getByRole('checkbox', { name: /Переформулировать запрос поиска/ }).check()
  await expect(page.getByRole('button', { name: 'Ответить с цитатами · до 23 API-вызовов', exact: true })).toBeVisible()
  await page.getByRole('button', { name: /^Ответить с цитатами/ }).click()
  await expect(page.locator('.grounded-claim')).toContainText('Используйте -u.')
  await expect(page.locator('.grounded-claim')).not.toContainText(rejected)
  const diagnostic = page.locator('.grounding-repair')
  await expect(diagnostic.locator('> summary')).toHaveText('Исправление черновика · 1 попытка')
  await expect(diagnostic).not.toHaveAttribute('open', '')
  for (const text of await page.getByText(rejected, { exact: false }).all()) await expect(text).not.toBeVisible()
  await diagnostic.locator('> summary').click()
  await expect(diagnostic.locator('.rejected-draft-claim')).toContainText(rejected)
  await expect(diagnostic).toContainText('Утверждение не подтверждено цитатой.')
  await expect(diagnostic).toContainText('judge-fixture')
  await diagnostic.locator('> summary').click()
  const mdDownload = page.waitForEvent('download'); await page.getByRole('button', { name: 'Скачать ответ с цитатами MD' }).click()
  const stream = await (await mdDownload).createReadStream(); const chunks: Buffer[] = []
  for await (const chunk of stream!) chunks.push(Buffer.from(chunk))
  const md = Buffer.concat(chunks).toString('utf8')
  expect(md).toContain('Исправление черновика · 1 попытка'); expect(md).not.toContain(rejected)
  const jsonDownload = page.waitForEvent('download'); await page.getByRole('button', { name: 'Скачать ответ с цитатами JSON' }).click()
  const jsonStream = await (await jsonDownload).createReadStream(); const jsonChunks: Buffer[] = []
  for await (const chunk of jsonStream!) jsonChunks.push(Buffer.from(chunk))
  const exported = JSON.parse(Buffer.concat(jsonChunks).toString('utf8'))
  expect(exported.repair.originalGeneration.rawJson).toContain(rejected)
  expect(exported.supportCheck.status).toBe('PASSED')
  expect(exported.claims[0].text).toBe('Используйте -u.')
})

test('live grounded quote opens correct immutable document @live', async ({ page }, testInfo) => {
  test.skip(process.env.RAG_LIVE !== 'true', 'Opt-in: up to 22 paid DeepSeek calls including one bounded repair and source/scope guards')
  test.setTimeout(900_000)
  await page.goto('/'); await page.getByRole('button', { name: 'Источники и цитаты' }).click()
  const cap = Number(process.env.RAG_LIVE_MAX_OUTPUT_TOKENS ?? '6000')
  expect(Number.isInteger(cap) && cap > 0 && cap <= 16384).toBe(true)
  await page.getByLabel('Лимит structured ответа').fill(String(cap))
  const responsePromise = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/grounded-answers') && r.request().method() === 'POST', { timeout: 840_000 })
  await page.getByRole('button', { name: /^Ответить с цитатами/ }).click()
  const response = await responsePromise, trace = await response.json()
  const tracePath = testInfo.outputPath(`day24-live-response-${Date.now()}.json`)
  await writeFile(tracePath, JSON.stringify(trace, null, 2), 'utf8')
  await testInfo.attach('day24-live-response', { path: tracePath, contentType: 'application/json' })
  console.log(`Day24 live: HTTP ${response.status()}, status=${trace.status}, llmStagesAttempted=${trace.llmStagesAttempted ?? 'unknown'}, repair=${!!trace.repair}`)
  await expect(page.locator('.grounded-result')).toHaveAttribute('data-status', 'ANSWERED', { timeout: 840_000 })
  await expect(page.locator('.grounded-result')).toContainText(/-u|include-untracked/)
  await page.getByRole('button', { name: 'Открыть цитату в источнике' }).first().click()
  await expect(page.getByRole('dialog')).toContainText('Цитата совпала с snapshot')
  await page.keyboard.press('Escape'); await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: 'test-results/day24-desktop.png', fullPage: false })
})
