import { computed, onScopeDispose, ref, watch } from 'vue'
import { saveInvariants } from './api'

/** Правила изменяются только явной командой с проверкой версии задачи. */
export function useInvariants(chat, memory) {
  const busy = ref(false), error = ref('')
  const enabled = computed(() => chat.agent.value?.capabilities?.includes('invariants'))
  let disposed = false
  onScopeDispose(() => { disposed = true })
  watch(() => chat.conversation.value?.id, () => { error.value = '' })
  async function save(rules) {
    if (busy.value || !memory.workspace.value?.workflow) return
    const a = chat.agentId.value, c = chat.conversation.value?.id
    const current = () => !disposed && a === chat.agentId.value && c === chat.conversation.value?.id
    busy.value = true; error.value = ''
    try {
      await saveInvariants(a, c, { command_id: crypto.randomUUID(), expected_revision: memory.workspace.value.workflow.state.revision, rules })
      if (current()) {
        await memory.refresh()
        if (current()) chat.conversation.value = { ...chat.conversation.value }
      }
    } catch (cause) {
      if (current()) { error.value = cause.message; await memory.refresh() }
    } finally { busy.value = false }
  }
  return { enabled, busy, error, save }
}
