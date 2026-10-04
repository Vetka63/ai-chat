import { expect, test, type Page } from '@playwright/test'
import { writeFile } from 'node:fs/promises'

const quote = 'Git сохраняет состояние файла на момент git add.'
const source = { chunkId: 'c1', documentId: 'doc', source: 'git.asc', title: 'Git', section: 'Индекс' }
function result(question: string, indexId: string, invalid = false) {
  return { request: { question, indexId }, snapshotId: 'snap', status: invalid ? 'INVALID_EVIDENCE' : 'ANSWERED', answer: invalid ? 'Ответ не прошёл проверку источников.' : 'Сохраняется подготовленная версия.', clarification: null, claims: invalid ? [] : [{ text: 'Сохраняется подготовленная версия.', citations: [{ source, quote, startInChunk: 0, endInChunkExclusive: quote.length, canonicalStart: 0, canonicalEndExclusive: quote.length }] }], sources: invalid ? [] : [source], issues: [], retrieval: { rawCandidates: [], included: [], omittedChunkIds: [] }, rewrite: null, generation: { rawJson: 'UNVERIFIED <script>window.fake=true</script>' }, llmStagesAttempted: 1, totalUsage: null, estimatedCost: null, totalMilliseconds: 2, warnings: [] }
}
type FixtureOptions = { invalid?: boolean; repair?: boolean; lostResponse?: boolean; lostRecoveryResponse?: boolean; absentFirstPost?: boolean; posts?: any[]; holdFirstResponse?: Promise<void> }
async function fixture(page: Page, options: FixtureOptions = {}) {
  const state: Record<string, any> = {}
  let recoveryFailure = false, postCount = 0, chatCount = 0
  await page.context().route(/\/api\/v1\/conversations(?:\/|$)/, async r => {
    const path = new URL(r.request().url()).pathname, method = r.request().method()
    if (path.endsWith('/conversations')) {
      if (method === 'GET') return r.fulfill({ json: Object.values(state).map(d => d.conversation) })
      const body = r.request().postDataJSON(), id = `chat-${++chatCount}`
      const conversation = { id, title: body.title, settings: body.settings, snapshotId: 'snap', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), revision: 0 }
      state[id] = { conversation, memory: { facts: [] }, turns: [] }
      return r.fulfill({ status: 201, json: state[id] })
    }
    const id = path.split('/')[4]!, d = state[id]
    if (!d) return r.fulfill({ status: 404, json: { message: 'Не найден' } })
    if (method === 'DELETE') { delete state[id]; return r.fulfill({ status: 204 }) }
    if (path.endsWith('/turns')) {
      const body = r.request().postDataJSON(), turnId = `turn-${d.turns.length + 1}`
      options.posts?.push({ conversationId: id, ...body }); postCount++
      if (options.absentFirstPost && postCount === 1) { recoveryFailure = !!options.lostRecoveryResponse; return r.abort('failed') }
      const existing = d.turns.find((t: any) => t.requestId === body.requestId)
      if (existing) return r.fulfill({ json: d })
      if (body.expectedRevision !== d.conversation.revision) return r.fulfill({ status: 409, json: { message: 'Чат изменился' } })
      if (!d.memory.facts.length) d.memory.facts.push({ layer: 'GOAL', key: 'goal', value: 'Сохранить изменения', sourceTurnId: turnId, quote: body.question })
      d.turns.push({ id: turnId, requestId: body.requestId, question: body.question, createdAt: new Date().toISOString(), status: 'COMPLETED', preparation: { query: 'Как сохранить изменения Git?', changes: [], rawJson: 'UNVERIFIED', usage: null }, result: result(body.question, d.conversation.settings.indexId, options.invalid), issue: null, memoryAfter: d.memory, includedHistoryTurnIds: [], omittedHistoryTurnCount: 0, totalUsage: null, estimatedCost: null, llmStagesAttempted: 2 })
      if (options.repair) {
        const generation = { model: 'draft-fixture', finishReason: 'stop', milliseconds: 1, usage: null, estimatedCost: null, messages: [], rawJson: JSON.stringify({ claims: [{ text: 'ИСХОДНЫЙ ЛОЖНЫЙ ВЫВОД ЧАТА' }] }) }
        const turn = d.turns.at(-1)
        turn.llmStagesAttempted = 5; turn.result.llmStagesAttempted = 4
        turn.result.repair = { originalGeneration: generation, originalSupportCheck: { status: 'REJECTED', claims: [{ claimIndex: 0, verdict: 'UNSUPPORTED', reason: 'Пропущено условие источника.' }], issues: [], generation: { ...generation, model: 'judge-fixture', rawJson: '{}' } } }
        turn.result.supportCheck = { status: 'PASSED', claims: [{ claimIndex: 0, verdict: 'SUPPORTED', reason: 'Подтверждено.' }], issues: [], generation: { ...generation, rawJson: '{}' } }
      }
      d.conversation.revision += 2
      if (options.lostResponse && postCount === 1) { recoveryFailure = !!options.lostRecoveryResponse; return r.abort('failed') }
      if (postCount === 1 && options.holdFirstResponse) await options.holdFirstResponse
      return r.fulfill({ json: d })
    }
    if (method === 'GET' && recoveryFailure) { recoveryFailure = false; return r.abort('failed') }
    return r.fulfill({ json: d })
  })
  await page.route('**/api/v1/indexes/*/documents/doc', r => r.fulfill({ json: { id: 'doc', title: 'Git', text: quote, sections: [] } }))
  return state
}
async function start(page: Page, name = 'Моя задача', threshold?: number) {
  await page.goto('/'); await page.getByRole('button', { name: 'Чат с RAG и памятью' }).click()
  await expect(page.getByRole('button', { name: 'Новый чат' })).toBeEnabled()
  if (!await page.getByRole('textbox', { name: 'Название чата' }).isVisible()) await page.getByRole('button', { name: 'Новый чат' }).click()
  await page.getByRole('textbox', { name: 'Название чата' }).fill(name)
  if (threshold !== undefined) {
    await page.getByText('Настройки поиска и памяти', { exact: true }).click()
    await page.getByRole('spinbutton', { name: 'Порог', exact: true }).fill(String(threshold))
  }
  await page.getByRole('button', { name: 'Создать чат', exact: true }).click()
  await expect(page.locator('.rag-chat-heading h2')).toHaveText(name)
}
async function send(page: Page, q: string, timeout = 5_000) { await page.getByRole('textbox', { name: 'Сообщение по задаче' }).fill(q); await page.getByRole('button', { name: 'Отправить', exact: true }).click(); await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeVisible({ timeout }) }
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
test('chat repair preserves final-only answer and collapses rejected draft across reload', async ({ page }) => {
  await fixture(page, { repair: true }); await start(page); await send(page, 'Что делает add?')
  await expect(page.locator('.rag-chat-composer')).toContainText('до 5 LLM-вызовов')
  await expect(page.locator('.rag-chat-claim')).toContainText('Сохраняется подготовленная версия.')
  await expect(page.locator('.rag-chat-claim')).not.toContainText('ИСХОДНЫЙ ЛОЖНЫЙ ВЫВОД ЧАТА')
  const diagnostic = page.locator('.grounding-repair')
  await expect(diagnostic.locator('> summary')).toHaveText('Исправление черновика · 1 попытка')
  for (const text of await page.getByText('ИСХОДНЫЙ ЛОЖНЫЙ ВЫВОД ЧАТА', { exact: false }).all()) await expect(text).not.toBeVisible()
  await diagnostic.locator('> summary').click()
  await expect(diagnostic.locator('.rejected-draft-claim')).toContainText('ИСХОДНЫЙ ЛОЖНЫЙ ВЫВОД ЧАТА')
  await expect(diagnostic).toContainText('Пропущено условие источника.')
  await page.reload(); await page.getByRole('button', { name: 'Чат с RAG и памятью' }).click()
  await expect(diagnostic).not.toHaveAttribute('open', '')
  for (const text of await page.getByText('ИСХОДНЫЙ ЛОЖНЫЙ ВЫВОД ЧАТА', { exact: false }).all()) await expect(text).not.toBeVisible()
  await expect(page.locator('.rag-chat-claim')).toContainText('Сохраняется подготовленная версия.')
})
test('lost HTTP response recovers durable message without second POST', async ({ page }) => {
  const state = await fixture(page, { invalid: false, lostResponse: true }); await start(page); await send(page, 'Мой вопрос')
  await expect(page.locator('.rag-chat-turn')).toHaveCount(1)
  expect(state['chat-1'].turns).toHaveLength(1)
  await expect(page.getByRole('textbox', { name: 'Сообщение по задаче' })).toHaveValue('')
})
test('drafts stay with their chat when selecting creating and reloading', async ({ page }) => {
  const state = await fixture(page); await start(page, 'Задача A')
  const input = page.getByRole('textbox', { name: 'Сообщение по задаче' })
  await input.fill('Черновик только для A')
  await page.getByRole('button', { name: 'Новый чат' }).click()
  await page.getByRole('textbox', { name: 'Название чата' }).fill('Задача B')
  await page.getByRole('button', { name: 'Создать чат', exact: true }).click()
  await expect(page.locator('.rag-chat-heading h2')).toHaveText('Задача B')
  await expect(input).toHaveValue('')
  await input.fill('Черновик только для B')
  await page.locator('.rag-chat-items').getByRole('button', { name: /Задача A/ }).click()
  await expect(input).toHaveValue('Черновик только для A')
  await page.locator('.rag-chat-items').getByRole('button', { name: /Задача B/ }).click()
  await expect(input).toHaveValue('Черновик только для B')
  await page.reload(); await page.getByRole('button', { name: 'Чат с RAG и памятью' }).click()
  await expect(page.locator('.rag-chat-heading h2')).toHaveText('Задача B')
  await expect(input).toHaveValue('Черновик только для B')
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect(page.locator('.rag-chat-turn')).toHaveCount(1)
  expect(state['chat-1'].turns).toHaveLength(0)
  expect(state['chat-2'].turns[0].question).toBe('Черновик только для B')
})
test('lost POST and recovery GET keep receipt until reselect without duplicate and permit intentional repeat', async ({ page }) => {
  const posts: any[] = []
  const state = await fixture(page, { lostResponse: true, lostRecoveryResponse: true, posts }); await start(page)
  await send(page, 'Сохранённый вопрос')
  await expect(page.locator('.rag-delivery-recovery')).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Сообщение по задаче' })).toBeDisabled()
  expect(posts).toHaveLength(1); expect(state['chat-1'].turns).toHaveLength(1)
  await page.locator('.rag-chat-items').getByRole('button', { name: /Моя задача/ }).click()
  await expect(page.locator('.rag-chat-turn')).toHaveCount(1)
  await expect(page.locator('.rag-delivery-recovery')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: 'Сообщение по задаче' })).toHaveValue('')
  expect(posts).toHaveLength(1)
  // A freshly typed identical question after acknowledgment is an intentional repeat.
  await send(page, 'Сохранённый вопрос')
  await expect(page.locator('.rag-chat-turn')).toHaveCount(2)
  expect(posts).toHaveLength(2); expect(posts[0].requestId).not.toBe(posts[1].requestId)
})
test('explicit recovery checks durable receipt before resending', async ({ page }) => {
  const posts: any[] = []
  await fixture(page, { lostResponse: true, lostRecoveryResponse: true, posts }); await start(page)
  await send(page, 'Уже сохранено')
  await expect(page.locator('.rag-delivery-recovery')).toBeVisible()
  await page.getByRole('button', { name: 'Проверить и повторить', exact: true }).click()
  await expect(page.locator('.rag-chat-turn')).toHaveCount(1)
  await expect(page.locator('.rag-delivery-recovery')).toHaveCount(0)
  expect(posts).toHaveLength(1)
})
test('reload restores unresolved receipt and acknowledges saved POST without duplicate', async ({ page }) => {
  const posts: any[] = []
  await fixture(page, { lostResponse: true, lostRecoveryResponse: true, posts }); await start(page)
  await send(page, 'Сохранено перед перезагрузкой')
  await expect(page.locator('.rag-delivery-recovery')).toBeVisible()
  await page.reload(); await page.getByRole('button', { name: 'Чат с RAG и памятью' }).click()
  await expect(page.locator('.rag-chat-turn')).toHaveCount(1)
  await expect(page.locator('.rag-delivery-recovery')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: 'Сообщение по задаче' })).toHaveValue('')
  expect(posts).toHaveLength(1)
})
test('reload and manual retry reuse request ID if original POST never arrived', async ({ page }) => {
  const posts: any[] = []
  const state = await fixture(page, { absentFirstPost: true, lostRecoveryResponse: true, posts }); await start(page)
  await send(page, 'Не дошло до сервера')
  await expect(page.locator('.rag-delivery-recovery')).toBeVisible()
  expect(state['chat-1'].turns).toHaveLength(0)
  await page.reload(); await page.getByRole('button', { name: 'Чат с RAG и памятью' }).click()
  await expect(page.locator('.rag-delivery-recovery')).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Сообщение по задаче' })).toBeDisabled()
  expect(posts).toHaveLength(1)
  await page.getByRole('button', { name: 'Проверить и повторить', exact: true }).click()
  await expect(page.locator('.rag-chat-turn')).toHaveCount(1)
  await expect(page.locator('.rag-delivery-recovery')).toHaveCount(0)
  expect(posts).toHaveLength(2); expect(posts[0].requestId).toBe(posts[1].requestId)
  expect(state['chat-1'].turns).toHaveLength(1)
})
for (const method of ['getItem', 'setItem'] as const) test(`unavailable storage ${method} retains drafts and prevents unrecorded POST`, async ({ page }) => {
  const posts: any[] = []
  await page.addInitScript(operation => {
    const original = Storage.prototype[operation]
    ;(window as any).auditStorageBlocked = true
    ;(Storage.prototype as any)[operation] = function (...args: any[]) {
      if (args[0] === 'rag-conversation-client-state-v1' && (window as any).auditStorageBlocked) throw new DOMException('Audit storage failure', 'QuotaExceededError')
      return (original as any).apply(this, args)
    }
  }, method)
  const state = await fixture(page, { posts }); await start(page, 'Черновик A')
  const input = page.getByRole('textbox', { name: 'Сообщение по задаче' })
  await input.fill('Не потерять набранный текст')
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect(input).toHaveValue('Не потерять набранный текст')
  await expect(page.getByRole('alert')).toContainText('Черновик остаётся')
  expect(posts).toHaveLength(0)
  await page.getByRole('button', { name: 'Новый чат' }).click()
  await page.getByRole('textbox', { name: 'Название чата' }).fill('Черновик B')
  await page.getByRole('button', { name: 'Создать чат', exact: true }).click()
  await input.fill('Отдельный текст B')
  await page.locator('.rag-chat-items').getByRole('button', { name: /Черновик A/ }).click()
  await expect(input).toHaveValue('Не потерять набранный текст')
  await page.evaluate(() => { (window as any).auditStorageBlocked = false })
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect(page.locator('.rag-chat-turn')).toHaveCount(1)
  expect(posts).toHaveLength(1); expect(state['chat-1'].turns[0].question).toBe('Не потерять набранный текст')
  await page.locator('.rag-chat-items').getByRole('button', { name: /Черновик B/ }).click()
  await expect(input).toHaveValue('Отдельный текст B')
})
test('corrupt recovery data blocks POST without deleting stored receipt or typed draft', async ({ page }) => {
  const posts: any[] = []
  await page.addInitScript(() => localStorage.setItem('rag-conversation-client-state-v1', '{invalid saved receipt'))
  await fixture(page, { posts }); await start(page)
  const input = page.getByRole('textbox', { name: 'Сообщение по задаче' })
  await input.fill('Черновик при повреждённом хранилище')
  await page.getByRole('button', { name: 'Отправить', exact: true }).click()
  await expect(input).toHaveValue('Черновик при повреждённом хранилище')
  expect(posts).toHaveLength(0)
  expect(await page.evaluate(() => localStorage.getItem('rag-conversation-client-state-v1'))).toBe('{invalid saved receipt')
})
test('404 while reconciling does not lock new or other chats', async ({ page }) => {
  const posts: any[] = []
  const state = await fixture(page, { lostResponse: true, lostRecoveryResponse: true, posts }); await start(page)
  await send(page, 'Вопрос в удалённом чате')
  await expect(page.locator('.rag-delivery-recovery')).toBeVisible()
  delete state['chat-1']
  await page.getByRole('button', { name: 'Проверить и повторить', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Не найден')
  expect(posts).toHaveLength(1)
  await expect(page.getByRole('button', { name: 'Новый чат' })).toBeEnabled()
  await page.getByRole('button', { name: 'Новый чат' }).click()
  await page.getByRole('textbox', { name: 'Название чата' }).fill('Продолжение в новом чате')
  await page.getByRole('button', { name: 'Создать чат', exact: true }).click()
  await expect(page.locator('.rag-chat-heading h2')).toHaveText('Продолжение в новом чате')
  await expect(page.locator('.rag-delivery-recovery')).toHaveCount(0)
  await expect(page.getByRole('textbox', { name: 'Сообщение по задаче' })).toBeEnabled()
  await expect(page.getByRole('textbox', { name: 'Сообщение по задаче' })).toHaveValue('')
})
test('stale revision recovery keeps request ID and refreshes revision only after GET', async ({ page }) => {
  const posts: any[] = []
  const state = await fixture(page, { posts }); await start(page)
  state['chat-1'].conversation.revision = 2
  await send(page, 'Запрос из устаревшей вкладки')
  await expect(page.locator('.rag-delivery-recovery')).toBeVisible()
  expect(state['chat-1'].turns).toHaveLength(0)
  await page.getByRole('button', { name: 'Проверить и повторить', exact: true }).click()
  await expect(page.locator('.rag-chat-turn')).toHaveCount(1)
  expect(posts.map(p => p.expectedRevision)).toEqual([0, 2])
  expect(posts[0].requestId).toBe(posts[1].requestId)
})
test('another tab editing its draft preserves in-flight receipt and both chat states', async ({ page }) => {
  const posts: any[] = []
  let release!: () => void
  const held = new Promise<void>(resolve => { release = resolve })
  await fixture(page, { posts, holdFirstResponse: held }); await start(page, 'Публикация A')
  const other = await page.context().newPage()
  try {
    await start(other, 'Черновик B')
    await page.getByRole('textbox', { name: 'Сообщение по задаче' }).fill('Первый запрос ещё выполняется')
    await page.getByRole('button', { name: 'Отправить', exact: true }).click()
    await expect.poll(() => posts.length).toBe(1)
    await other.getByRole('textbox', { name: 'Сообщение по задаче' }).fill('Черновик другой вкладки')
    const pendingState = await other.evaluate(() => JSON.parse(localStorage.getItem('rag-conversation-client-state-v1')!))
    expect(pendingState.outbox['chat-1'].requestId).toBe(posts[0].requestId)
    expect(pendingState.drafts['chat-2']).toBe('Черновик другой вкладки')
    release()
    await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeVisible()
    const finalState = await page.evaluate(() => JSON.parse(localStorage.getItem('rag-conversation-client-state-v1')!))
    expect(finalState.outbox['chat-1']).toBeUndefined()
    expect(finalState.drafts['chat-2']).toBe('Черновик другой вкладки')
    expect(posts).toHaveLength(1)
  } finally { release(); await other.close() }
})
test('live chat resolves followup keeps goal and opens snapshot source @live', async ({ page }, testInfo) => {
  test.skip(process.env.RAG_LIVE !== 'true', 'Opt-in: up to 10 paid DeepSeek calls including bounded repairs')
  test.setTimeout(1_800_000)
  await start(page, `UI день25 ${Date.now()}`)
  const questions = ['Моя цель — отменить локальный коммит и сохранить изменения. Что делает git reset --soft?', 'А что станет с индексом?']
  for (const [position, question] of questions.entries()) {
    await page.getByRole('textbox', { name: 'Сообщение по задаче' }).fill(question)
    const responsePromise = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/turns') && r.request().method() === 'POST', { timeout: 840_000 })
    await page.getByRole('button', { name: 'Отправить', exact: true }).click()
    const response = await responsePromise, trace = await response.json(), turn = trace.turns?.at(-1)
    const tracePath = testInfo.outputPath(`day25-live-turn-${position + 1}-${Date.now()}.json`)
    await writeFile(tracePath, JSON.stringify(trace, null, 2), 'utf8')
    await testInfo.attach(`day25-live-turn-${position + 1}`, { path: tracePath, contentType: 'application/json' })
    console.log(`Day25 live turn ${position + 1}: HTTP ${response.status()}, status=${turn?.result?.status}, llmStagesAttempted=${turn?.llmStagesAttempted ?? 'unknown'}, repair=${!!turn?.result?.repair}`)
    await expect(page.getByRole('button', { name: 'Отправить', exact: true })).toBeVisible({ timeout: 840_000 })
    await expect(page.locator('.rag-chat-turn').last()).toHaveAttribute('data-status', 'ANSWERED')
  }
  await expect(page.locator('.rag-memory-fact').first()).toContainText(/сохранить/)
  await page.locator('.rag-chat-turn').last().locator('.rag-turn-trace > summary').click()
  await expect(page.locator('.rag-chat-turn').last().locator('.rag-turn-trace')).toContainText(/reset/)
  await page.screenshot({ path: 'test-results/day25-live-chat.png', fullPage: false })
  await page.locator('.rag-chat-turn').last().locator('.rag-chat-claim summary').first().click()
  await page.locator('.rag-chat-turn').last().getByRole('button', { name: 'Открыть цитату в книге' }).first().click(); await expect(page.getByRole('dialog')).toContainText('Цитата совпала')
})

test('live chat high threshold refuses without grounded generation @live', async ({ page }, testInfo) => {
  test.skip(process.env.RAG_LIVE !== 'true', 'Opt-in: one paid preparation call; no answer generation expected')
  test.setTimeout(300_000)
  await start(page, `UI отказ день25 ${Date.now()}`, 1)
  const responsePromise = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/turns') && r.request().method() === 'POST', { timeout: 240_000 })
  await send(page, 'Какая погода будет в Самаре завтра?', 240_000)
  const response = await responsePromise, trace = await response.json(), turn = trace.turns?.at(-1)
  const tracePath = testInfo.outputPath(`day25-live-unknown-${Date.now()}.json`)
  await writeFile(tracePath, JSON.stringify(trace, null, 2), 'utf8')
  await testInfo.attach('day25-live-unknown', { path: tracePath, contentType: 'application/json' })
  expect(response.status()).toBe(200)
  expect(turn.preparation.issues).toEqual([])
  expect(turn.result.status).toBe('UNKNOWN')
  expect(turn.result.retrieval.included).toEqual([])
  expect(turn.result.claims).toEqual([])
  expect(turn.result.sources).toEqual([])
  expect(turn.result.generation).toBeNull()
  expect(turn.result.llmStagesAttempted).toBe(0)
  expect(turn.llmStagesAttempted).toBe(1)
  await expect(page.locator('.rag-chat-turn').last()).toHaveAttribute('data-status', 'UNKNOWN')
  await expect(page.locator('.rag-chat-turn').last()).toContainText('Не знаю по найденным материалам')
  await page.screenshot({ path: 'test-results/day25-live-unknown.png', fullPage: false })
  console.log(`Day25 live UNKNOWN: ${turn.llmStagesAttempted} preparation call; grounded calls=${turn.result.llmStagesAttempted}`)
})
