import { computed, onScopeDispose, ref } from 'vue'
import { taskCommand } from './api'
import { getConversation } from '../../api/agents'

/** Команды автомата отделены от отправки чата: пауза доступна во время LLM. */
export function useTaskWorkflow(chat, memory) {
  const busy = ref(false), controlBusy = ref(false), error = ref('')
  const enabled = computed(() => chat.agent.value?.capabilities?.includes('task_workflow'))
  const workspace = computed(() => memory.workspace.value?.workflow)
  const blocked = computed(() => enabled.value && (!workspace.value || workspace.value.state.status === 'paused' || workspace.value.state.phase === 'done' || (['execution', 'validation'].includes(workspace.value.state.phase) && !workspace.value.state.approved_plan_id)))
  let disposed = false
  onScopeDispose(() => { disposed = true })
  async function change(resource, body) {
    const control = resource === 'events' && ['pause', 'resume'].includes(body.event)
    const pending = control ? controlBusy : busy
    if (!workspace.value || pending.value || (!control && controlBusy.value)) return
    const a = chat.agentId.value, c = chat.conversation.value?.id
    const current = () => !disposed && a === chat.agentId.value && c === chat.conversation.value?.id
    pending.value = true; error.value = ''
    try {
      await taskCommand(a, c, resource, { command_id: crypto.randomUUID(), expected_revision: workspace.value.state.revision, ...body })
      if (!disposed && a === chat.agentId.value && c === chat.conversation.value?.id) {
        await memory.refresh()
        if (current()) chat.conversation.value = { ...chat.conversation.value }
      }
    } catch (cause) {
      if (!disposed && a === chat.agentId.value && c === chat.conversation.value?.id) {
        error.value = cause.message
        await memory.refresh()
      }
    } finally {
      // Judge артефакта тоже расходует токены, даже при отказе. Обновляем runs.
      if (resource === 'artifacts' && current()) {
        try {
          const value = await getConversation(a, c)
          if (current()) chat.conversation.value = value
        } catch { /* Основная ошибка команды важнее фонового обновления статистики. */ }
      }
      pending.value = false
    }
  }
  return { busy, controlBusy, error, enabled, workspace, blocked,
    transition: event => change('events', typeof event === 'string' ? { event } : event),
    save: artifact => change('artifacts', artifact),
    step: step_id => change('step', { step_id }),
    send: () => { if (!blocked.value && !busy.value && !controlBusy.value) return chat.send({ command_id: crypto.randomUUID(), expected_revision: workspace.value?.state.revision }) },
  }
}
