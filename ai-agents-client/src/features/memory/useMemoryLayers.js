import { computed, onScopeDispose, ref, watch } from 'vue'
import { getConversation } from '../../api/agents'
import * as api from './api'

export function useMemoryLayers(chat) {
  const workspace = ref(null)
  const busy = ref(false)
  const loading = ref(false)
  const error = ref('')
  const enabled = computed(() => chat.agent.value?.capabilities?.includes('memory_layers'))
  let revision = 0
  let disposed = false
  const scope = () => chat.agentId.value + '/' + chat.conversation.value?.id
  const versions = () => ({ task_revision: workspace.value.task.revision, profile_revision: workspace.value.profile.memory_revision,
    preferences_revision: workspace.value.profile.revision || 1 })

  async function refresh() {
    const current = ++revision
    if (!enabled.value || !chat.conversation.value) { workspace.value = null; loading.value = false; return }
    loading.value = true
    try {
      const value = await api.getMemory(chat.agentId.value, chat.conversation.value.id)
      if (current === revision) { workspace.value = value; error.value = '' }
    } catch (cause) {
      if (current === revision) error.value = cause.message
    } finally {
      if (current === revision) loading.value = false
    }
  }

  watch([() => chat.agentId.value, () => chat.conversation.value?.id], () => {
    workspace.value = null
    error.value = ''
    refresh()
  })
  watch([() => chat.messages.value.length, () => chat.sending.value], () => {
    if (!chat.sending.value && !busy.value && enabled.value) refresh()
  })
  onScopeDispose(() => { disposed = true; ++revision })

  async function change(operation) {
    if (!workspace.value || busy.value || chat.sending.value) return
    busy.value = true
    const target = scope()
    ++revision
    loading.value = false
    error.value = ''
    try {
      const result = await operation(chat.agentId.value, chat.conversation.value.id, versions())
      if (disposed || target !== scope()) return
      workspace.value = result.workspace || result
      // Изменение памяти пересчитывает preview без дополнительного LLM-вызова.
      chat.conversation.value = { ...chat.conversation.value,
        runs: result.run ? [...(chat.conversation.value.runs || []), result.run] : chat.conversation.value.runs }
    } catch (cause) {
      if (!disposed && target === scope()) {
        await refresh()
        // Метрики ошибочного служебного вызова тоже должны появиться в UI.
        try {
          const fresh = await getConversation(chat.agentId.value, chat.conversation.value.id)
          if (!disposed && target === scope()) chat.conversation.value = fresh
        } catch { /* Последний доступный снимок остаётся в UI. */ }
        if (!disposed && target === scope()) error.value = cause.message
      }
    } finally { busy.value = false }
  }

  return { workspace, busy, loading, error, enabled, refresh,
    saveProblem: problem => change((a, c, v) => api.saveProblem(a, c, { ...v, problem })),
    saveEntry: entry => change((a, c, v) => api.saveEntry(a, c, { ...v, ...entry })),
    deleteEntry: id => change((a, c, v) => api.deleteEntry(a, c, id, v)),
    propose: sourceId => change((a, c, v) => api.proposeMemory(a, c, { ...v, source_message_id: sourceId })),
    resolve: (id, action) => change((a, c, v) => api.resolveProposal(a, c, id, { ...v, action })),
  }
}
