<script setup>
defineProps({ agents: Array, selected: String, theme: String, open: Boolean })
defineEmits(['select', 'new', 'theme', 'close'])

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
      <div><strong>AI Agents</strong><small>Python · День 6</small></div>
      <button class="mobile-close" aria-label="Закрыть меню" @click="$emit('close')">×</button>
    </div>

    <button class="new-chat" @click="$emit('new')"><span>＋</span> Новый чат</button>

    <section class="sidebar-section">
      <p class="section-title">Агенты</p>
      <button
        v-for="agent in agents"
        :key="agent.id"
        class="agent-card"
        :class="{ active: agent.id === selected }"
        @click="$emit('select', agent.id)"
      >
        <span class="agent-avatar">✦</span>
        <span><strong>{{ agent.name }}</strong><small>{{ agent.description }}</small></span>
      </button>
      <p v-if="!agents?.length" class="muted">Агенты не загружены</p>
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

    <div class="day-note"><strong>Итерация Day 6</strong><span>История пока не сохраняется и не передаётся модели.</span></div>
  </aside>
</template>

