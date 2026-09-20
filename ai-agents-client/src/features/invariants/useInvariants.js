import { computed, ref, watch } from 'vue'
import { getInvariants, saveInvariants } from './api'

export function useInvariants(chat, workflow) {
  const workspace = ref(null)
  const busy = ref(false)
  const error = ref('')
  const enabled = computed(() => chat.agent.value?.capabilities?.includes('invariants'))
  let requestId = 0

  async function refresh() {
    const current = ++requestId
    if (!enabled.value || !chat.conversation.value) { workspace.value = null; return }
    try {
      const value = await getInvariants(chat.agentId.value, chat.conversation.value.id)
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
  watch(() => workflow.busy.value, (working, wasWorking) => {
    if (wasWorking && !working && enabled.value) refresh()
  })

  async function save(rules) {
    if (!workspace.value || busy.value || !chat.conversation.value) return
    const agentId = chat.agentId.value
    const conversationId = chat.conversation.value.id
    busy.value = true
    error.value = ''
    try {
      const value = await saveInvariants(agentId, conversationId, {
        expected_revision: workspace.value.revision, rules,
      })
      if (agentId === chat.agentId.value && conversationId === chat.conversation.value?.id) {
        workspace.value = value
        await workflow.refresh()
      }
    } catch (cause) {
      if (agentId === chat.agentId.value && conversationId === chat.conversation.value?.id) {
        await refresh()
        error.value = cause.message
      }
    } finally { busy.value = false }
  }

  return { workspace, busy, error, enabled, refresh, save }
}
