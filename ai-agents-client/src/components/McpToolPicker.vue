<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({ servers: Array, selectedIds: Array, busy: Boolean, loading: Boolean, error: String })
const emit = defineEmits(['change', 'refresh'])
const root = ref(null)
const trigger = ref(null)
const open = ref(false)
const count = computed(() => props.selectedIds?.length || 0)

async function toggle() {
  if (props.busy) return
  open.value = !open.value
  if (open.value) {
    await nextTick()
    root.value?.querySelector('input:not(:disabled), .mcp-refresh')?.focus()
  }
}
function close(restore = false) {
  open.value = false
  if (restore) trigger.value?.focus()
}
function change(id, checked) {
  const selected = new Set(props.selectedIds || [])
  if (checked) selected.add(id)
  else selected.delete(id)
  emit('change', [...selected])
}
function outside(event) { if (!root.value?.contains(event.target)) close() }
function keyboard(event) {
  if (event.key === 'Escape') { event.stopPropagation(); close(true) }
}
watch(() => props.busy, busy => { if (busy) close() })
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>

<template>
  <div ref="root" class="mcp-picker" @keydown="keyboard">
    <button ref="trigger" type="button" class="mcp-trigger" :class="{ active: count }"
      :disabled="busy" aria-label="MCP для следующего сообщения" aria-haspopup="dialog" :aria-expanded="open"
      @click="toggle">
      <span aria-hidden="true">◇</span><strong>{{ count ? `MCP · ${count}` : 'MCP выкл.' }}</strong><span aria-hidden="true">⌃</span>
    </button>
    <div v-if="open" class="mcp-menu" role="dialog" aria-label="MCP для следующего сообщения">
      <div class="mcp-menu-heading"><strong>Инструменты этого сообщения</strong><button type="button" aria-label="Закрыть выбор MCP" @click="close(true)">×</button></div>
      <p>Отмеченные серверы будут доступны агенту для следующего сообщения. Выбор останется в этом чате, пока вы его не измените. Инструмент вызывается только когда подходит к запросу.</p>
      <p v-if="error" role="alert" class="mcp-menu-error">{{ error }}</p>
      <p v-if="loading">Загружаем список…</p>
      <fieldset v-else :disabled="busy">
        <legend class="sr-only">Доступные MCP-серверы</legend>
        <label v-for="server in servers" :key="server.id" class="mcp-menu-option">
          <input type="checkbox" :checked="selectedIds?.includes(server.id)" @change="change(server.id, $event.target.checked)">
          <span><strong>{{ server.name }}</strong><small>{{ server.description }}</small></span>
        </label>
        <p v-if="!servers?.length && !error">Нет доступных MCP-серверов.</p>
      </fieldset>
      <div class="mcp-menu-actions">
        <button type="button" :disabled="busy || loading || !count" @click="emit('change', [])">Отключить все</button>
        <button type="button" class="mcp-refresh" :disabled="busy || loading" @click="emit('refresh')">Обновить список</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mcp-picker { position: relative; min-width: 0; }
.mcp-trigger { display: flex; align-items: center; gap: 7px; min-height: 36px; padding: 7px 10px; border: 1px solid var(--line); border-radius: 10px; background: var(--panel); color: var(--text); cursor: pointer; font-size: 12px; white-space: nowrap; }
.mcp-trigger:hover, .mcp-trigger.active { background: var(--accent-soft); color: var(--text); }
.mcp-trigger:disabled { opacity: .5; cursor: default; }
.mcp-menu { position: absolute; bottom: calc(100% + 12px); right: 0; width: min(350px, calc(100vw - 40px)); max-height: min(460px, 60dvh); overflow-y: auto; padding: 13px; border: 1px solid var(--line); border-radius: 16px; background: var(--page); color: var(--text); box-shadow: 0 12px 40px #0003; font-size: 12px; }
.mcp-menu-heading, .mcp-menu-actions { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.mcp-menu-heading > button { border: 0; background: transparent; color: var(--muted); cursor: pointer; font-size: 20px; }
.mcp-menu p { margin: 8px 0; color: var(--muted); line-height: 1.5; }
.mcp-menu .mcp-menu-error { color: var(--danger); }
.mcp-menu fieldset { margin: 10px 0; padding: 0; border: 0; min-width: 0; }
.mcp-menu-option { display: flex; align-items: flex-start; gap: 10px; padding: 10px; margin: 5px 0; border: 1px solid var(--line); border-radius: 10px; cursor: pointer; }
.mcp-menu-option:has(input:checked) { background: var(--accent-soft); border-color: var(--accent); }
.mcp-menu-option input { flex: 0 0 auto; width: 17px; height: 17px; margin: 2px 0 0; accent-color: var(--accent); }
.mcp-menu-option span { display: grid; gap: 3px; min-width: 0; }
.mcp-menu-option small { color: var(--muted); line-height: 1.3; }
.mcp-menu-actions { padding-top: 8px; border-top: 1px solid var(--line); }
.mcp-menu-actions button { border: 0; border-radius: 8px; background: var(--accent-soft); color: var(--text); padding: 7px 9px; cursor: pointer; font-size: 11px; }
.mcp-menu-actions button:disabled { opacity: .45; cursor: default; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
</style>
