/** Browser recovery data contains no provider secrets. Keep a request ID until
 * the server confirms its durable turn, including across a page reload. */
export interface PendingDelivery {
  requestId: string
  question: string
  expectedRevision: number
}

export interface ConversationClientState {
  drafts: Record<string, string>
  outbox: Record<string, PendingDelivery>
}

export const conversationClientStateKey = 'rag-conversation-client-state-v1'

export function emptyClientState(): ConversationClientState {
  return { drafts: {}, outbox: {} }
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function readClientState(storage: Pick<Storage, 'getItem'>): ConversationClientState {
  const raw = storage.getItem(conversationClientStateKey)
  if (!raw) return emptyClientState()
  const value: unknown = JSON.parse(raw)
  if (!record(value) || !record(value.drafts) || !record(value.outbox)) throw new Error('invalid_client_state')
  const drafts: Record<string, string> = {}, outbox: Record<string, PendingDelivery> = {}
  for (const [id, draft] of Object.entries(value.drafts)) {
    if (typeof draft !== 'string' || draft.length > 2000) throw new Error('invalid_draft')
    Object.defineProperty(drafts, id, { value: draft, enumerable: true, writable: true, configurable: true })
  }
  for (const [id, request] of Object.entries(value.outbox)) {
    if (!record(request) || typeof request.requestId !== 'string' || !/^[A-Za-z0-9_-]{1,80}$/.test(request.requestId)
      || typeof request.question !== 'string' || !request.question.trim() || request.question.length > 2000
      || typeof request.expectedRevision !== 'number' || !Number.isSafeInteger(request.expectedRevision) || request.expectedRevision < 0) {
      throw new Error('invalid_pending_delivery')
    }
    Object.defineProperty(outbox, id, {
      value: { requestId: request.requestId, question: request.question, expectedRevision: request.expectedRevision },
      enumerable: true, writable: true, configurable: true,
    })
  }
  return { drafts, outbox }
}

export function writeClientState(storage: Pick<Storage, 'setItem'>, state: ConversationClientState): void {
  // setItem is synchronous: failure must prevent the corresponding network POST.
  storage.setItem(conversationClientStateKey, JSON.stringify(state))
}
