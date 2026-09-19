import { computed, onScopeDispose, ref, watch } from 'vue'
import * as api from './api'

export function useProfiles(chat, memory) {
  const profiles = ref([]), loading = ref(false), busy = ref(false), error = ref(''), notice = ref(''), preferredId = ref('')
  const enabled = computed(() => chat.agent.value?.capabilities?.includes('personalization'))
  let revision = 0, disposed = false
  async function refresh() {
    const current = ++revision
    if (!enabled.value) { profiles.value = []; loading.value = false; return }
    loading.value = true
    try {
      const value = await api.listProfiles()
      if (current === revision) { profiles.value = value; error.value = '' }
    } catch (cause) {
      if (current === revision) error.value = cause.message
    } finally { if (current === revision) loading.value = false }
  }
  watch(enabled, () => { error.value = ''; notice.value = ''; refresh() }, { immediate: true })
  onScopeDispose(() => { disposed = true; ++revision })
  async function save({ id, ...body }) {
    if (busy.value || chat.sending.value || memory.busy.value || !enabled.value) return
    busy.value = true
    error.value = ''; notice.value = ''
    const agentId = chat.agentId.value
    ++revision
    loading.value = false
    try {
      const saved = id ? await api.updateProfile(id, body) : await api.createProfile(body)
      if (disposed || agentId !== chat.agentId.value) return
      profiles.value = [...profiles.value.filter(p => p.id !== saved.id), saved]
      if (!id) preferredId.value = saved.id
      if (memory.workspace.value?.profile.id === saved.id) {
        await memory.refresh()
        if (!disposed && chat.conversation.value && agentId === chat.agentId.value)
          chat.conversation.value = { ...chat.conversation.value } // Пересчёт prompt без LLM.
      }
      notice.value = id ? 'Профиль сохранён. Новые настройки применятся к следующим запросам его задач.' : 'Профиль создан. Выберите его при создании новой задачи.'
    } catch (cause) {
      if (!disposed && agentId === chat.agentId.value) error.value = cause.message
    } finally { busy.value = false }
  }
  return { profiles, loading, busy, enabled, error, notice, preferredId, refresh, save }
}
