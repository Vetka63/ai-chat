<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { api, type Schema } from '../../api/client'
import { download } from '../lab/report'
import { usageText } from '../experiments/report'
import { conversationMarkdown } from './report'
import { conversationClientStateKey, emptyClientState, readClientState, writeClientState, type ConversationClientState, type PendingDelivery } from './deliveryState'
import '../grounding/grounding.css'
import './conversations.css'

const props = defineProps<{ indexes: Schema<'IndexInfo'>[] }>()
const chats = ref<Schema<'Conversation'>[]>([]), active = ref<Schema<'ConversationDetail'> | null>(null)
const busy = ref(true), error = ref(''), creating = ref(false), title = ref('Новая задача по Git'), memoryVisible = ref(true)
const clientState = ref(emptyClientState()), storageError = ref(''), deliveryNote = ref('')
const unsavedDrafts = new Map<string, string>()
try { clientState.value = readClientState(localStorage) } catch { storageError.value = 'Не удалось прочитать черновики и незавершённые отправки. Отправка заблокирована, чтобы не создать повторный запрос. Проверьте доступ к хранилищу браузера.' }
function persist(update: (current: ConversationClientState) => ConversationClientState): boolean {
  try { const saved = readClientState(localStorage); const state = update({ ...saved, drafts: { ...saved.drafts, ...Object.fromEntries(unsavedDrafts) } }); writeClientState(localStorage, state); clientState.value = state; unsavedDrafts.clear(); storageError.value = ''; return true }
  catch { storageError.value = 'Хранилище браузера недоступно. Черновик остаётся в открытой вкладке; отправка возможна только после сохранения состояния. Восстановите доступ и повторите действие.'; return false }
}
function syncClientState(event: StorageEvent) {
  if (event.key !== conversationClientStateKey) return
  try { const saved = readClientState(localStorage); clientState.value = { ...saved, drafts: { ...saved.drafts, ...Object.fromEntries(unsavedDrafts) } } } catch { storageError.value = 'Не удалось прочитать обновлённое состояние отправки. Черновик остаётся в открытой вкладке; восстановите доступ к хранилищу браузера.' }
}
const text = computed({
  get: () => active.value ? clientState.value.drafts[active.value.conversation.id] ?? '' : '',
  set: (value: string) => { const id = active.value?.conversation.id; if (id) { unsavedDrafts.set(id, value); clientState.value = { ...clientState.value, drafts: { ...clientState.value.drafts, [id]: value } }; persist(current => current) } },
})
const unresolved = computed(() => active.value ? clientState.value.outbox[active.value.conversation.id] : undefined)
const settings = ref<Schema<'ConversationSettings'>>({ indexId: props.indexes.find(i => i.config.strategy === 'STRUCTURAL')?.id ?? props.indexes[0]?.id ?? '', candidateTopK: 10, finalTopK: 5, similarityThreshold: .65, contextMaxCharacters: 16000, historyTurns: 6, historyMaxCharacters: 10000, maxOutputTokens: null })
const scroll = ref<HTMLElement | null>(null)
const opened = ref<{ doc: Schema<'Document'>; citation: Schema<'VerifiedCitation'> } | null>(null)
const pendingText = ref('')
const pending = computed(() => active.value?.turns.some(t => t.status === 'PENDING') ?? false)
const layers: Record<Schema<'MemoryLayer'>, string> = { GOAL: 'Цель', CLARIFICATIONS: 'Уточнения', CONSTRAINTS: 'Ограничения', TERMS: 'Термины' }
const statuses = { ANSWERED: 'Цитаты проверены · смысл сверяйте с источником', UNKNOWN: 'Не знаю по найденным материалам', INVALID_EVIDENCE: 'Ответ не прошёл проверку источников', ERROR: 'Ошибка ответа' }
let poll: ReturnType<typeof setTimeout> | undefined, disposed = false
function fail(e: unknown) { error.value = e instanceof Error ? e.message : String(e) }
async function bottom() { await nextTick(); if (scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight }
async function refreshList() { chats.value = await api.conversations() }
function remember() { if (active.value) localStorage.setItem('rag-active-conversation', active.value.conversation.id) }
function acceptDetail(d: Schema<'ConversationDetail'>) {
  if (active.value?.conversation.id === d.conversation.id && active.value.conversation.revision > d.conversation.revision) return
  active.value = d
  const request = clientState.value.outbox[d.conversation.id]
  if (!request) return
  const turn = d.turns.find(t => t.requestId === request.requestId)
  if (!turn) return
  if (turn.question !== request.question) throw new Error('Сохранённый requestId относится к другому тексту. Повторная отправка заблокирована.')
  if (persist(current => { const outbox = { ...current.outbox }; if (outbox[d.conversation.id]?.requestId === request.requestId) delete outbox[d.conversation.id]; return { ...current, outbox } })) deliveryNote.value = turn.status === 'PENDING'
    ? 'Вопрос уже сохранён и обрабатывается. Повторная отправка не требуется.'
    : 'Сохранённый результат получен. Повторный запрос не отправлялся.'
}
function schedulePoll() {
  if (poll) clearTimeout(poll)
  if (!pending.value || disposed || !active.value) return
  const id = active.value.conversation.id
  poll = setTimeout(async () => {
    try { const d = await api.conversation(id); if (active.value?.conversation.id === id) { acceptDetail(d); await bottom(); schedulePoll() } } catch (e) { fail(e); schedulePoll() }
  }, 2500)
}
async function select(id: string) {
  if (busy.value) return
  busy.value = true; error.value = ''; deliveryNote.value = ''; creating.value = false; opened.value = null
  try { acceptDetail(await api.conversation(id)); remember(); await bottom(); schedulePoll() } catch (e) { fail(e) } finally { busy.value = false }
}
onMounted(async () => {
  try { await refreshList(); busy.value = false; const saved = localStorage.getItem('rag-active-conversation'); const id = chats.value.find(c => c.id === saved)?.id ?? chats.value[0]?.id; if (id) await select(id); else creating.value = true } catch (e) { fail(e) } finally { busy.value = false }
  window.addEventListener('keydown', escape)
  window.addEventListener('storage', syncClientState)
})
onUnmounted(() => { disposed = true; if (poll) clearTimeout(poll); window.removeEventListener('keydown', escape); window.removeEventListener('storage', syncClientState) })
function escape(e: KeyboardEvent) { if (e.key === 'Escape') { opened.value = null; if (active.value) creating.value = false } }
async function create() {
  if (busy.value || !title.value.trim() || !settings.value.indexId) return
  busy.value = true; error.value = ''; deliveryNote.value = ''
  try { active.value = await api.createConversation({ title: title.value.trim(), settings: { ...settings.value, maxOutputTokens: typeof settings.value.maxOutputTokens === 'number' ? settings.value.maxOutputTokens : null } }); creating.value = false; remember(); await refreshList(); await bottom() } catch (e) { fail(e) } finally { busy.value = false }
}
async function deliver(id: string, request: PendingDelivery) {
  pendingText.value = request.question; await bottom()
  try { acceptDetail(await api.sendTurn(id, request)); deliveryNote.value = ''; await refreshList() }
  catch (e) {
    fail(e)
    try { acceptDetail(await api.conversation(id)) } catch { /* Keep the persisted request identity until a successful reconciliation. */ }
  } finally { pendingText.value = '' }
}
async function send() {
  if (busy.value || pending.value || unresolved.value || !active.value || !text.value.trim()) return
  const id = active.value.conversation.id
  const request = { requestId: crypto.randomUUID(), question: text.value.trim(), expectedRevision: active.value.conversation.revision }
  if (!persist(current => current.outbox[id] ? current : { drafts: { ...current.drafts, [id]: '' }, outbox: { ...current.outbox, [id]: request } })) return
  if (clientState.value.outbox[id]?.requestId !== request.requestId) return // Another tab already has an unresolved send.
  busy.value = true; error.value = ''; deliveryNote.value = ''
  try { await deliver(id, request) } finally { busy.value = false; await bottom(); schedulePoll() }
}
async function reconcile(retry: boolean) {
  if (busy.value || !active.value || !unresolved.value) return
  const id = active.value.conversation.id
  busy.value = true; error.value = ''; deliveryNote.value = ''
  try {
    const d = await api.conversation(id)
    acceptDetail(d)
    const request = clientState.value.outbox[id]
    if (!request) return // The original POST was saved: never issue another one.
    if (d.turns.some(t => t.requestId === request.requestId)) return // Storage acknowledgment failed.
    if (d.turns.some(t => t.status === 'PENDING')) { deliveryNote.value = 'В чате обрабатывается другое сообщение. Дождитесь его завершения.'; return }
    if (!retry) { deliveryNote.value = 'Вопрос пока не найден в сохранённой истории. Его можно отправить повторно с тем же идентификатором.'; return }
    const refreshed = { ...request, expectedRevision: d.conversation.revision }
    if (!persist(current => current.outbox[id]?.requestId === request.requestId ? { ...current, outbox: { ...current.outbox, [id]: refreshed } } : current)) return
    if (clientState.value.outbox[id]?.requestId !== request.requestId) return
    await deliver(id, refreshed)
  } catch (e) { fail(e) }
  finally { busy.value = false; await bottom(); schedulePoll() }
}
async function remove() {
  if (!active.value || busy.value || pending.value || unresolved.value || !window.confirm('Удалить этот чат, его сообщения и память? Индексы останутся.')) return
  busy.value = true; error.value = ''
  try { const id = active.value.conversation.id; await api.deleteConversation(id); persist(current => { const drafts = { ...current.drafts }; delete drafts[id]; return { ...current, drafts } }); active.value = null; localStorage.removeItem('rag-active-conversation'); await refreshList(); creating.value = true } catch (e) { fail(e) } finally { busy.value = false }
}
async function citation(c: Schema<'VerifiedCitation'>) {
  try { if (!active.value) return; const doc = await api.indexDocument(active.value.conversation.settings.indexId, c.source.documentId); if (doc.text.slice(c.canonicalStart, c.canonicalEndExclusive) !== c.quote) throw new Error('Цитата не совпала с snapshot.'); opened.value = { doc, citation: c } } catch (e) { fail(e) }
}
function jump(id: string) { document.getElementById(`turn-${id}`)?.scrollIntoView({ block: 'center', behavior: 'smooth' }) }
</script>

<template>
  <section class="conversation-lab">
    <p class="notice error" v-if="error" role="alert">{{ error }}</p>
    <p class="notice error" v-if="storageError" role="alert">{{ storageError }}</p>
    <div class="rag-chat-layout" :class="{ 'memory-hidden': !memoryVisible }">
      <aside class="rag-chat-list" aria-label="Сохранённые чаты">
        <button class="primary" :disabled="busy" @click="creating = true">＋ Новый чат</button>
        <div class="rag-chat-items"><button v-for="c in chats" :key="c.id" :class="{ selected: active?.conversation.id === c.id }" :disabled="busy" @click="select(c.id)"><span>{{ c.title }}</span><small>{{ new Date(c.updatedAt).toLocaleString('ru-RU') }}</small></button><p v-if="!chats.length" class="hint">Каждый чат хранит отдельную историю и память задачи.</p></div>
      </aside>
      <main class="rag-chat-main">
        <header class="rag-chat-heading"><div><h2>{{ active?.conversation.title ?? 'Чат по книге Pro Git' }}</h2><small>Свежий поиск для каждого вопроса · источники обязательны</small></div><button :aria-expanded="memoryVisible" @click="memoryVisible = !memoryVisible">Память задачи</button></header>
        <div class="rag-chat-messages" ref="scroll" aria-live="polite" aria-label="Сообщения чата">
          <p v-if="active && !active.turns.length" class="rag-chat-empty">Опишите цель и ограничения, затем задавайте вопросы. Например: «Хочу отменить локальный коммит и сохранить изменения. Что делает reset --soft?»</p>
          <article v-for="t in active?.turns" :key="t.id" :id="`turn-${t.id}`" class="rag-chat-turn" :data-status="t.result?.status ?? t.status">
            <p class="rag-user-message">{{ t.question }}</p>
            <div class="rag-assistant-message">
              <p v-if="t.status === 'PENDING'" class="hint">Вопрос сохранён. Подготавливаем память, ищем источники…</p>
              <p v-if="t.issue" class="notice error">{{ t.issue }}</p>
              <template v-if="t.result">
                <small class="rag-answer-status">{{ statuses[t.result.status] }}</small>
                <template v-if="t.result.status === 'ANSWERED'">
                  <section v-for="(claim, n) in t.result.claims" :key="n" class="rag-chat-claim"><p>{{ claim.text }}</p><details><summary>Источники и цитаты · {{ claim.citations.length }}</summary><blockquote v-for="(c, j) in claim.citations" :key="j" class="evidence-quote"><p>{{ c.quote }}</p><footer>{{ c.source.source }} · {{ c.source.section }}<br><code>{{ c.source.chunkId }}</code><br><button @click="citation(c)">Открыть цитату в книге</button></footer></blockquote></details></section>
                  <p class="hint">Источники: {{ t.result.sources.map(s => s.section).join(' · ') }}</p>
                </template>
                <template v-else><p>{{ t.result.answer }}</p><p class="clarification" v-if="t.result.clarification">{{ t.result.clarification }}</p><p v-if="!t.result.sources.length" class="hint">Источников ответа нет: технические утверждения не публикуются.</p><p v-for="i in t.result.issues" :key="i.code" class="notice error">{{ i.code }} · {{ i.message }}</p></template>
              </template>
              <details v-if="t.result?.supportCheck" class="rag-support-check unverified-diagnostics">
                <summary>Проверка смысловой поддержки · {{ t.result.supportCheck.status }}</summary>
                <p>Дополнительная проверка моделью: {{ usageText(t.result.supportCheck.generation.usage) }}. Её объяснения — диагностика, не новые факты и не гарантия безошибочности.</p>
                <p v-for="assessment in t.result.supportCheck.claims" :key="assessment.claimIndex">Пункт {{ assessment.claimIndex + 1 }} · {{ assessment.verdict }}: {{ assessment.reason }}</p>
                <p v-for="issue in t.result.supportCheck.issues" :key="issue.code">{{ issue.code }} · {{ issue.message }}</p>
              </details>
              <p v-else-if="t.result?.status === 'ANSWERED'" class="hint">У этого сохранённого ответа нет записи проверки смысла; точное совпадение цитаты само по себе не подтверждает вывод.</p>
              <details v-if="t.status !== 'PENDING'" class="rag-turn-trace"><summary>Как получен ответ · {{ t.llmStagesAttempted }} LLM-стадий</summary><p>Самостоятельный вопрос поиска: {{ t.preparation?.query ?? 'Подготовка не завершена' }}</p><p>Хвост в prompt: {{ t.includedHistoryTurnIds.length }} обменов; вне prompt: {{ t.omittedHistoryTurnCount }}. Полная история хранится.</p><p>Изменений памяти: {{ t.preparation?.changes.length ?? 0 }}</p><p>Подготовка: {{ usageText(t.preparation?.usage ?? null) }} · ответ: {{ usageText(t.result?.totalUsage ?? null) }} · общий API usage: {{ usageText(t.totalUsage) }}</p><p v-if="t.estimatedCost">Оценка USD: {{ t.estimatedCost.minimumUsd.toFixed(6) }}–{{ t.estimatedCost.maximumUsd.toFixed(6) }}, не списание.</p><p v-if="t.result?.retrieval">Найдено {{ t.result.retrieval.rawCandidates.length }} → в prompt {{ t.result.retrieval.included.length }} · не поместилось {{ t.result.retrieval.omittedChunkIds.length }}</p><details class="unverified-diagnostics"><summary>Непроверенная диагностика модели</summary><pre>{{ t.preparation }}</pre><pre>{{ t.result?.generation }}</pre></details></details>
            </div>
          </article>
          <p v-if="pendingText" class="rag-user-message optimistic-message">{{ pendingText }}</p>
          <div v-if="unresolved && !busy" class="notice rag-delivery-recovery" role="status"><p>Доставка сообщения не подтверждена. Оно сохранено в этом браузере, в том числе после перезагрузки страницы.</p><blockquote>{{ unresolved.question }}</blockquote><p>Сначала проверим историю чата. Повторная отправка использует прежний идентификатор и не создаст второй ответ на уже сохранённый запрос.</p><button :disabled="busy" @click="reconcile(false)">Проверить статус</button><button :disabled="busy || pending" @click="reconcile(true)">Проверить и повторить</button></div>
          <p v-if="deliveryNote" class="notice" role="status">{{ deliveryNote }}</p>
        </div>
        <form class="rag-chat-composer" @submit.prevent="send"><label for="rag-chat-input">Сообщение по задаче</label><textarea id="rag-chat-input" v-model="text" rows="3" maxlength="2000" :disabled="!active || busy || pending || !!unresolved" placeholder="Спросите о Git или уточните ситуацию…" @keydown.ctrl.enter.prevent="send"/><div><small>Ctrl+Enter · до 3 LLM-вызовов, без автоматических повторов</small><button class="primary" :disabled="!active || busy || pending || !!unresolved || !text.trim()">{{ busy || pending ? 'Обрабатываем…' : 'Отправить' }}</button></div></form>
      </main>
      <aside v-if="memoryVisible" class="rag-memory-panel">
        <h3>Память этого чата</h3><p class="hint">Это данные пользователя, не источник знаний о Git. Обновляются автоматически. Общая цель сохраняется; для её смены начните сообщение с «Новая цель:».</p>
        <template v-for="(label, layer) in layers" :key="layer"><h4>{{ label }}</h4><article v-for="f in active?.memory.facts.filter(f => f.layer === layer)" :key="f.key" class="rag-memory-fact"><p>{{ f.value }}</p><details><summary>Откуда запомнили</summary><blockquote>{{ f.quote }}</blockquote><button @click="jump(f.sourceTurnId)">К сообщению пользователя</button></details></article><p v-if="!active?.memory.facts.some(f => f.layer === layer)" class="hint">Пока не зафиксировано</p></template>
        <details v-if="active"><summary>Настройки чата</summary><p>Индекс: {{ active.conversation.settings.indexId }}</p><p>Хвост: до {{ active.conversation.settings.historyTurns }} обменов и {{ active.conversation.settings.historyMaxCharacters }} символов.</p><p>Порог: {{ active.conversation.settings.similarityThreshold }}; K: {{ active.conversation.settings.candidateTopK }} → {{ active.conversation.settings.finalTopK }}; источники: {{ active.conversation.settings.contextMaxCharacters }} символов.</p><p>Лимит ответа: {{ active.conversation.settings.maxOutputTokens ?? 'Не задан приложением' }}.</p><p class="hint">Для других настроек создайте новый чат. Snapshot не меняется.</p></details>
        <template v-if="active"><button @click="download('day25-chat.md', conversationMarkdown(active))">Скачать чат MD</button><button @click="download('day25-chat.json', JSON.stringify(active, null, 2), 'application/json')">Скачать диагностику JSON</button><button :disabled="busy || pending || !!unresolved" @click="remove">Удалить чат</button></template>
      </aside>
    </div>
    <div v-if="creating" class="modal-backdrop" @click.self="active && (creating = false)"><form class="document-modal rag-chat-create" role="dialog" aria-modal="true" aria-labelledby="new-chat-title" @submit.prevent="create"><header><h2 id="new-chat-title">Новый чат с RAG</h2><button v-if="active" type="button" aria-label="Закрыть создание чата" @click="creating = false">×</button></header><p>История и память изолированы. Настройки закрепляются при создании.</p><fieldset :disabled="busy"><label>Название чата<input v-model="title" maxlength="100" required></label><label>Индекс для чата<select v-model="settings.indexId" required><option value="">Выберите индекс</option><option v-for="i in indexes" :key="i.id" :value="i.id">{{ i.config.strategy }} · {{ i.metrics.chunkCount }} чанков</option></select></label><details><summary>Настройки поиска и памяти</summary><div class="answer-config"><label>Кандидатов<input v-model.number="settings.candidateTopK" type="number" min="1" max="20" required></label><label>После фильтра<input v-model.number="settings.finalTopK" type="number" min="1" max="10" required></label><label>Порог<input v-model.number="settings.similarityThreshold" type="number" min="-1" max="1" step="0.01" required></label><label>Бюджет источников<input v-model.number="settings.contextMaxCharacters" type="number" min="300" max="60000" required></label><label>Обменов в хвосте<input v-model.number="settings.historyTurns" type="number" min="1" max="12" required></label><label>Символов в хвосте<input v-model.number="settings.historyMaxCharacters" type="number" min="1000" max="20000" required></label><label>Лимит ответа<input v-model.number="settings.maxOutputTokens" type="number" min="1" max="32768" placeholder="Не задан"></label></div></details></fieldset><p class="notice error" v-if="error">{{ error }}</p><p v-if="!indexes.length" class="notice">Сначала постройте индекс во вкладке «Разбиение и индексы».</p><button class="primary" :disabled="busy || !settings.indexId || !title.trim()">Создать чат</button></form></div>
    <div v-if="opened" class="modal-backdrop" @click.self="opened = null"><section class="document-modal" role="dialog" aria-modal="true" aria-labelledby="chat-source-title"><header><h2 id="chat-source-title">{{ opened.doc.title }}</h2><button aria-label="Закрыть источник" @click="opened = null">×</button></header><p>Цитата совпала с сохранённым snapshot чата.</p><blockquote>{{ opened.citation.quote }}</blockquote><details><summary>Документ с подсветкой</summary><pre>{{ opened.doc.text.slice(0, opened.citation.canonicalStart) }}<mark>{{ opened.citation.quote }}</mark>{{ opened.doc.text.slice(opened.citation.canonicalEndExclusive) }}</pre></details></section></div>
  </section>
</template>
