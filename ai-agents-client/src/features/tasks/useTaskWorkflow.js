import { computed, onScopeDispose, ref } from 'vue'
import { taskCommand } from './api'

/** Команды автомата отделены от отправки чата: пауза доступна во время LLM. */
export function useTaskWorkflow(chat, memory) {
  const busy = ref(false), error = ref('')
  const enabled = computed(() => chat.agent.value?.capabilities?.includes('task_workflow'))
  const workspace = computed(() => memory.workspace.value?.workflow)
  const blocked = computed(() => enabled.value && (!workspace.value || workspace.value.state.status === 'paused' || workspace.value.state.phase === 'done'))
  let disposed = false
  onScopeDispose(() => { disposed = true })
  async function change(resource, body) {
    if (!workspace.value || busy.value) return
    const a = chat.agentId.value, c = chat.conversation.value?.id
    busy.value = true; error.value = ''
    try {
      await taskCommand(a, c, resource, { command_id: crypto.randomUUID(), expected_revision: workspace.value.state.revision, ...body })
      if (!disposed && a === chat.agentId.value && c === chat.conversation.value?.id) {
        await memory.refresh()
        chat.conversation.value = { ...chat.conversation.value }
      }
    } catch (cause) {
      if (!disposed && a === chat.agentId.value && c === chat.conversation.value?.id) {
        error.value = cause.message
        await memory.refresh()
      }
    } finally { busy.value = false }
  }
  return { busy, error, enabled, workspace, blocked,
    transition: event => change('events', { event }),
    save: artifact => change('artifacts', artifact),
    step: step_id => change('step', { step_id }),
    send: () => { if (!blocked.value && !busy.value) return chat.send({ command_id: crypto.randomUUID(), expected_revision: workspace.value?.state.revision }) },
  }
}
