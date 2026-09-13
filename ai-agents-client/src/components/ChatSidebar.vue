<script setup>
import { computed } from 'vue'
const props = defineProps({ conversations: Array, selectedConversation: String, agentName: String, busy: Boolean })
defineEmits(['new', 'select', 'delete', 'close'])
const badge = mode => ({ full: '∞', summary: 'Σ', sliding_window: '⇥', sticky_facts: '◆', branching: '⑂' }[mode] || '∞')
const ordered = computed(() => {
  const source = props.conversations || []
  const children = new Map()
  source.forEach(item => {
    const parent = item.parent_conversation_id
    if (parent) children.set(parent, [...(children.get(parent) || []), item])
  })
  const result = []
  const visit = (item, depth = 0) => {
    result.push({ item, depth })
    ;(children.get(item.id) || []).forEach(child => visit(child, depth + 1))
  }
  source.filter(item => !item.parent_conversation_id || !source.some(other => other.id === item.parent_conversation_id)).forEach(item => visit(item))
  return result
})
</script>

<template>
  <aside class="chat-sidebar" aria-label="Чаты">
    <div class="brand">
      <span class="brand-mark">A</span>
      <div><strong>AI Agents</strong><small>Ваше пространство для диалогов</small></div>
      <button class="mobile-close" aria-label="Закрыть список чатов" @click="$emit('close')">×</button>
    </div>
    <button class="new-chat" :disabled="busy || !agentName" @click="$emit('new')"><span>＋</span> Новый чат</button>
    <section class="sidebar-section conversations-section">
      <p class="section-title">Сохранённые чаты</p>
      <nav class="conversation-list" tabindex="0" aria-label="Сохранённые чаты">
        <p v-if="!conversations?.length" class="empty-conversations">Начните диалог — он появится здесь.</p>
        <div v-for="entry in ordered" :key="entry.item.id" class="conversation-item" :class="{ active: entry.item.id === selectedConversation, 'branch-child': entry.depth > 0 }" :style="{ '--branch-depth': entry.depth }">
          <button class="conversation-title" :disabled="busy" :aria-current="entry.item.id === selectedConversation ? 'page' : undefined" :title="entry.item.title" @click="$emit('select', entry.item.id)">
            <span>{{ entry.depth ? '└' : '◌' }}</span><strong>{{ entry.item.branch_name || entry.item.title }}</strong>
            <small class="context-mode-badge" :title="entry.item.context_settings?.mode">{{ badge(entry.item.context_settings?.mode) }}</small>
          </button>
          <button class="delete-conversation" :disabled="busy" :aria-label="`Удалить чат «${entry.item.title}»`" @click="$emit('delete', entry.item)">×</button>
        </div>
      </nav>
    </section>
    <div class="sidebar-footer"><span class="agent-avatar">✦</span><div><strong>{{ agentName || 'Выберите агента' }}</strong><small>Чаты выбранного агента</small></div></div>
  </aside>
</template>
