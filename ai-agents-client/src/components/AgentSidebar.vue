<script setup>
defineProps({
  agents: Array,
  selectedAgent: String,
  conversations: Array,
  selectedConversation: String,
  theme: String,
  open: Boolean,
  busy: Boolean,
})
defineEmits(['select-agent', 'select-conversation', 'delete-conversation', 'new', 'theme', 'close'])

const themes = [
  { id: 'light', label: 'Светлая', icon: '☀' },
  { id: 'dark', label: 'Тёмная', icon: '◐' },
  { id: 'new-year', label: 'Новый год', icon: '✦' },
]
</script>

<template>
  <aside class="sidebar" :class="{ open }">
    <div class="brand">
      <span class="brand-mark">A</span>
      <div><strong>AI Agents</strong><small>Python · День 8</small></div>
      <button class="mobile-close" aria-label="Закрыть меню" @click="$emit('close')">×</button>
    </div>

    <button class="new-chat" :disabled="busy || !selectedAgent" @click="$emit('new')">
      <span>＋</span> Новый чат
    </button>

    <section class="sidebar-section">
      <p class="section-title">Агенты</p>
      <button
        v-for="agent in agents"
        :key="agent.id"
        class="agent-card"
        :class="{ active: agent.id === selectedAgent }"
        :disabled="busy"
        @click="$emit('select-agent', agent.id)"
      >
        <span class="agent-avatar">✦</span>
        <span><strong>{{ agent.name }}</strong><small>{{ agent.description }}</small></span>
      </button>
      <p v-if="!agents?.length" class="muted">Агенты не загружены</p>
    </section>

    <slot />
    <section class="sidebar-section conversations-section">
      <p class="section-title">Сохранённые чаты</p>
      <nav class="conversation-list" aria-label="Сохранённые чаты">
        <p v-if="!conversations?.length" class="empty-conversations">После первого сообщения чат появится здесь</p>
        <div
          v-for="item in conversations"
          :key="item.id"
          class="conversation-item"
          :class="{ active: item.id === selectedConversation }"
        >
          <button class="conversation-title" :disabled="busy" @click="$emit('select-conversation', item.id)">
            <span>◌</span><strong>{{ item.title }}</strong>
          </button>
          <button
            class="delete-conversation"
            :disabled="busy"
            :aria-label="`Удалить чат «${item.title}»`"
            @click="$emit('delete-conversation', item)"
          >×</button>
        </div>
      </nav>
    </section>

    <section class="sidebar-section theme-section">
      <p class="section-title">Оформление</p>
      <div class="theme-grid">
        <button
          v-for="item in themes"
          :key="item.id"
          :class="{ active: theme === item.id }"
          :aria-label="`Тема: ${item.label}`"
          @click="$emit('theme', item.id)"
        ><span>{{ item.icon }}</span>{{ item.label }}</button>
      </div>
    </section>

    <div class="day-note"><strong>Итерация Day 8</strong><span>Полная история · наблюдение за токенами · без обрезки контекста.</span></div>
  </aside>
</template>

