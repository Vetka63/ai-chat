import { computed, ref, watch } from 'vue'
import { applyWorkflow, getWorkflow } from './api'

export function useWorkflow(chat) {
  const workspace = ref(null)
  const busy = ref(false)
  const error = ref('')
  const enabled = computed(() => chat.agent.value?.capabilities?.includes('task_workflow'))
  let requestId = 0
  const scope = () => `${chat.agentId.value}/${chat.conversation.value?.id}`

  async function refresh() {
    const current = ++requestId
    if (!enabled.value || !chat.conversation.value) { workspace.value = null; return }
    try {
      const value = await getWorkflow(chat.agentId.value, chat.conversation.value.id)
      if (current === requestId) { workspace.value = value; error.value = '' }
    } catch (cause) {
      if (current === requestId) error.value = cause.message
    }
  }

  watch([() => chat.agentId.value, () => chat.conversation.value?.id], () => {
    workspace.value = null
    error.value = ''
    refresh()
  })
  watch(() => chat.sending.value, (sending, wasSending) => {
    if (wasSending && !sending && enabled.value) refresh()
  })

  async function apply(action, details = {}) {
    if (!workspace.value || busy.value || (chat.sending.value && action !== 'pause')) return
    const target = scope()
    busy.value = true
    error.value = ''
    try {
      const value = await applyWorkflow(chat.agentId.value, chat.conversation.value.id,
        { action, expected_revision: workspace.value.state.revision, ...details })
      if (target === scope()) {
        workspace.value = value
        // Смена этапа влияет на оценку следующего запроса.
        chat.conversation.value = { ...chat.conversation.value }
      }
    } catch (cause) {
      if (target === scope()) { await refresh(); error.value = cause.message }
    } finally { busy.value = false }
  }

  return { workspace, busy, error, enabled, refresh, apply }
}
