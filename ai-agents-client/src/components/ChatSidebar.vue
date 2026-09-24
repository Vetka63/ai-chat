<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  agents: Array,
  selectedAgent: String,
  conversations: Array,
  selectedConversation: String,
  busy: Boolean,
  mcpActive: Boolean,
})
const emit = defineEmits(['new', 'select', 'select-agent', 'delete', 'close', 'open-mcp'])
const search = ref('')
const expanded = ref(true)
const activeAgent = computed(() => props.agents?.find(agent => agent.id === props.selectedAgent))

watch(() => props.selectedAgent, () => {
  search.value = ''
  expanded.value = true
})

function chooseAgent(id) {
  if (props.busy) return
  if (id === props.selectedAgent) expanded.value = !expanded.value
  else emit('select-agent', id)
}

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
const visibleEntries = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return query ? ordered.value.filter(({ item }) => (item.branch_name || item.title).toLocaleLowerCase().includes(query)) : ordered.value
})
</script>

<template>
  <aside class="chat-sidebar" aria-label="Агенты и чаты">
    <div class="brand">
      <span class="brand-mark">A</span>
      <div><strong>AI Agents</strong><small>Рабочее пространство</small></div>
      <button class="mobile-close" aria-label="Закрыть список чатов" @click="$emit('close')">×</button>
    </div>
    <button class="new-chat" :disabled="busy || !activeAgent" :title="activeAgent ? `Новый чат: ${activeAgent.name}` : undefined" @click="$emit('new')"><span aria-hidden="true">＋</span> Новый чат</button>
    <div class="sidebar-navigation">
      <p class="section-title">Агенты</p>
      <nav class="agent-tree" aria-label="Агенты и их чаты">
        <p v-if="!agents?.length" class="empty-conversations">Агенты не загружены.</p>
        <section v-for="agent in agents" :key="agent.id" class="agent-group" :class="{ active: agent.id === selectedAgent }">
          <button class="agent-folder" type="button" :disabled="busy" :aria-expanded="agent.id === selectedAgent && expanded"
            :aria-controls="agent.id === selectedAgent ? `agent-chats-${agent.id}` : undefined" @click="chooseAgent(agent.id)">
            <span class="folder-chevron" aria-hidden="true">{{ agent.id === selectedAgent && expanded ? '⌄' : '›' }}</span>
            <span class="folder-icon" aria-hidden="true">✦</span>
            <span class="folder-name">{{ agent.name }}</span>
            <small v-if="agent.id === selectedAgent" class="folder-count">{{ conversations?.length || 0 }}</small>
          </button>
          <div v-if="agent.id === selectedAgent" v-show="expanded" :id="`agent-chats-${agent.id}`" class="agent-contents">
            <label class="chat-search"><span aria-hidden="true">⌕</span><input v-model="search" type="search" :aria-label="`Поиск чатов агента ${agent.name}`" placeholder="Поиск в чатах…"></label>
            <div class="conversation-section-heading"><p class="section-title">Сохранённые чаты</p></div>
            <div class="conversation-list" aria-label="Сохранённые чаты">
              <p v-if="!conversations?.length" class="empty-conversations">Пока нет чатов. Создайте первый.</p>
              <p v-else-if="!visibleEntries.length" class="empty-conversations">По вашему запросу чатов не найдено.</p>
              <div v-for="entry in visibleEntries" :key="entry.item.id" class="conversation-item" :class="{ active: entry.item.id === selectedConversation, 'branch-child': entry.depth > 0 }" :style="{ '--branch-depth': entry.depth }">
                <button class="conversation-title" :disabled="busy" :aria-current="entry.item.id === selectedConversation ? 'page' : undefined" :title="entry.item.title" @click="$emit('select', entry.item.id)">
                  <span aria-hidden="true">{{ entry.depth ? '└' : '◌' }}</span><strong>{{ entry.item.branch_name || entry.item.title }}</strong>
                  <small class="context-mode-badge" :title="entry.item.context_settings?.mode">{{ badge(entry.item.context_settings?.mode) }}</small>
                </button>
                <button class="delete-conversation" :disabled="busy" :aria-label="`Удалить чат «${entry.item.title}»`" @click="$emit('delete', entry.item)">×</button>
              </div>
            </div>
          </div>
        </section>
      </nav>
    </div>
    <button class="mcp-nav" :class="{ active: mcpActive }" :aria-current="mcpActive ? 'page' : undefined" @click="$emit('open-mcp')">
      <span aria-hidden="true">⌘</span><span><strong>Каталог MCP</strong><small>Серверы и инструменты</small></span>
    </button>
    <div class="sidebar-footer"><span class="agent-avatar">✦</span><div><strong>{{ activeAgent?.name || 'Выберите агента' }}</strong><small>Текущий агент</small></div></div>
  </aside>
</template>
