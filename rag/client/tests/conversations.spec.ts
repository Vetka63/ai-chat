import { expect, test, type Page } from '@playwright/test'

const quote = 'Git сохраняет состояние файла на момент git add.'
const source = { chunkId: 'c1', documentId: 'doc', source: 'git.asc', title: 'Git', section: 'Индекс' }
function result(question: string, indexId: string, invalid = false) {
  return { request: { question, indexId }, snapshotId: 'snap', status: invalid ? 'INVALID_EVIDENCE' : 'ANSWERED', answer: invalid ? 'Ответ не прошёл проверку источников.' : 'Сохраняется подготовленная версия.', clarification: null, claims: invalid ? [] : [{ text: 'Сохраняется подготовленная версия.', citations: [{ source, quote, startInChunk: 0, endInChunkExclusive: quote.length, canonicalStart: 0, canonicalEndExclusive: quote.length }] }], sources: invalid ? [] : [source], issues: [], retrieval: { rawCandidates: [], included: [], omittedChunkIds: [] }, rewrite: null, generation: { rawJson: 'UNVERIFIED <script>window.fake=true</script>' }, llmStagesAttempted: 1, totalUsage: null, estimatedCost: null, totalMilliseconds: 2, warnings: [] }
}
async function fixture(page: Page, options = { invalid: false, lostResponse: false }) {
  const state: Record<string, any> = {}
  await page.route(/\/api\/v1\/conversations(?:\/|$)/, async r => {
    const path = new URL(r.request().url()).pathname, method = r.request().method()
    if (path.endsWith('/conversations')) {
      if (method === 'GET') return r.fulfill({ json: Object.values(state).map(d => d.conversation) })
      const body = r.request().postDataJSON(), id = `chat-${Object.keys(state).length + 1}`
      const conversation = { id, title: body.title, settings: body.settings, snapshotId: 'snap', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), revision: 0 }
      state[id] = { conversation, memory: { facts: [] }, turns: [] }
      return r.fulfill({ status: 201, json: state[id] })
    }
    const id = path.split('/')[4]!, d = state[id]
    if (!d) return r.fulfill({ status: 404, json: { message: 'Не найден' } })
    if (method === 'DELETE') { delete state[id]; return r.fulfill({ status: 204 }) }
    if (path.endsWith('/turns')) {
      const body = r.request().postDataJSON(), turnId = `turn-${d.turns.length + 1}`
      if (!d.memory.facts.length) d.memory.facts.push({ layer: 'GOAL', key: 'goal', value: 'Сохранить изменения', sourceTurnId: turnId, quote: body.question })
      d.turns.push({ id: turnId, requestId: body.requestId, question: body.question, createdAt: new Date().toISOString(), status: 'COMPLETED', preparation: { query: 'Как сохранить изменения Git?', changes: [], rawJson: 'UNVERIFIED', usage: null }, result: result(body.question, d.conversation.settings.indexId, options.invalid), issue: null, memoryAfter: d.memory, includedHistoryTurnIds: [], omittedHistoryTurnCount: 0, totalUsage: null, estimatedCost: null, llmStagesAttempted: 2 })
      d.conversation.revision += 2
      if (options.lostResponse) return r.abort('failed')
      return r.fulfill({ json: d })
    }
    return r.fulfill({ json: d })
  })
  await page.route('**/api/v1/indexes/*/documents/doc', r => r.fulfill({ json: { id: 'doc', title: 'Git', text: quote, sections: [] } }))
  return state
}
async function start(page: Page, name = 'Моя задача') {
  await page.goto('/'); await page.getByRole('button', { name: 'Чат с RAG и памятью' }).click()
  await expect(page.getByRole('button', { name: 'Новый чат' })).toBeEnabled()
  if (!await page.getByRole('textbox', { name: 'Название чата' }).isVisible()) await page.getByRole('button', { name: 'Новый чат' }).click()
  await page.getByRole('textbox', { name: 'Название чата' }).fill(name)
  await page.getByRole('button', { name: 'Создать чат', exact: true }).click()
  await expect(page.locator('.rag-chat-heading h2')).toHaveText(name)
}
async function send(page: Page, q: string) { await page.getByRole('textbox', { name: 'Сообщение по задаче' }).fill(q); await page.getByRole('button', { name: 'Отправить', exact: true }).click(); await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeVisible() }
test('chat memory isolation reload sources and deletion', async ({ page }) => {
  const state = await fixture(page); await start(page)
  await send(page, 'Цель: сохранить изменения. Как работает git add?')
  await expect(page.locator('.rag-memory-fact')).toContainText('Сохранить изменения')
  await page.locator('.rag-chat-claim summary').click(); await page.getByRole('button', { name: 'Открыть цитату в книге' }).click()
  await expect(page.getByRole('dialog')).toContainText('Цитата совпала'); await page.keyboard.press('Escape')
  const download = page.waitForEvent('download'); await page.getByRole('button', { name: 'Скачать чат MD' }).click(); expect((await download).suggestedFilename()).toBe('day25-chat.md')
  await page.reload(); await page.getByRole('button', { name: 'Чат с RAG и памятью' }).click(); await expect(page.locator('.rag-user-message')).toContainText('Цель: сохранить')
  await page.getByRole('button', { name: 'Новый чат' }).click(); await page.getByRole('textbox', { name: 'Название чата' }).fill('Изолированный чат'); await page.getByRole('button', { name: 'Создать чат', exact: true }).click()
  await expect(page.locator('.rag-memory-fact')).toHaveCount(0); await expect(page.locator('.rag-chat-turn')).toHaveCount(0)
  page.once('dialog', d => d.accept()); await page.getByRole('button', { name: 'Удалить чат' }).click()
  expect(Object.keys(state)).toHaveLength(1)
})
test('auto scroll tail after many turns and no horizontal overflow', async ({ page }) => {
  await fixture(page); await start(page)
  for (let n = 0; n < 10; n++) await send(page, `Сообщение ${n}`)
  await expect(page.locator('.rag-chat-turn')).toHaveCount(10)
  expect(await page.locator('.rag-chat-messages').evaluate(el => el.scrollHeight - el.scrollTop - el.clientHeight < 5)).toBe(true)
  await page.screenshot({ path: 'test-results/day25-desktop.png', fullPage: false })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/day25-mobile.png', fullPage: true })
})
test('new chat cannot race initial conversation load', async ({ page }) => {
  await fixture(page)
  await page.route(/\/api\/v1\/conversations$/, async r => { if (r.request().method() === 'GET') await new Promise(resolve => setTimeout(resolve, 900)); await r.fallback() })
  await page.goto('/'); await page.getByRole('button', { name: 'Чат с RAG и памятью' }).click()
  await expect(page.getByRole('button', { name: 'Новый чат' })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Новый чат' })).toBeEnabled()
  await page.getByRole('textbox', { name: 'Название чата' }).fill('После загрузки')
  await page.getByRole('button', { name: 'Создать чат', exact: true }).click()
  await expect(page.locator('.rag-chat-heading h2')).toHaveText('После загрузки')
})
test('invalid output remains quarantined and settings cannot mutate existing chat', async ({ page }) => {
  await fixture(page, { invalid: true, lostResponse: false }); await start(page); await send(page, 'Что делает add?')
  await expect(page.locator('.rag-chat-turn')).toHaveCount(1)
  await expect(page.locator('.rag-chat-claim')).toHaveCount(0)
  for (const raw of await page.getByText('UNVERIFIED', { exact: false }).all()) await expect(raw).not.toBeVisible()
  await expect(page.getByRole('spinbutton')).toHaveCount(0)
  expect(await page.evaluate(() => (window as any).fake)).toBeUndefined()
})
test('lost HTTP response recovers durable message without second POST', async ({ page }) => {
  const state = await fixture(page, { invalid: false, lostResponse: true }); await start(page); await send(page, 'Мой вопрос')
  await expect(page.locator('.rag-chat-turn')).toHaveCount(1)
  expect(state['chat-1'].turns).toHaveLength(1)
  await expect(page.getByRole('textbox', { name: 'Сообщение по задаче' })).toHaveValue('')
})
test('live chat resolves followup keeps goal and opens snapshot source @live', async ({ page }) => {
  test.skip(process.env.RAG_LIVE !== 'true', 'Opt-in: up to 4 paid DeepSeek calls')
  test.setTimeout(300000)
  await start(page, `UI день25 ${Date.now()}`)
  await send(page, 'Моя цель — отменить локальный коммит и сохранить изменения. Что делает git reset --soft?')
  await expect(page.locator('.rag-chat-turn').first()).toHaveAttribute('data-status', 'ANSWERED', { timeout: 150000 })
  await send(page, 'А что станет с индексом?')
  await expect(page.locator('.rag-chat-turn').last()).toHaveAttribute('data-status', 'ANSWERED', { timeout: 150000 })
  await expect(page.locator('.rag-memory-fact').first()).toContainText(/сохранить/)
  await page.locator('.rag-chat-turn').last().locator('.rag-turn-trace > summary').click()
  await expect(page.locator('.rag-chat-turn').last().locator('.rag-turn-trace')).toContainText(/reset/)
  await page.screenshot({ path: 'test-results/day25-live-chat.png', fullPage: false })
  await page.locator('.rag-chat-turn').last().locator('.rag-chat-claim summary').first().click()
  await page.locator('.rag-chat-turn').last().getByRole('button', { name: 'Открыть цитату в книге' }).first().click(); await expect(page.getByRole('dialog')).toContainText('Цитата совпала')
})
