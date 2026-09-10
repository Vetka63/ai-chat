<script setup>
defineProps({ agents: Array, selectedAgent: String, theme: String, busy: Boolean })
defineEmits(['select-agent', 'theme', 'close'])
const themes = [
  { id: 'light', label: 'Светлая', icon: '☀' },
  { id: 'dark', label: 'Тёмная', icon: '◐' },
  { id: 'new-year', label: 'Новый год', icon: '✦' },
]
</script>

<template>
  <aside class="settings-sidebar" aria-label="Настройки агента">
    <header class="settings-header"><div><strong>Настройки</strong><small>Агент и контекст диалога</small></div><button class="icon-button" aria-label="Скрыть настройки" @click="$emit('close')">×</button></header>
    <div class="settings-scroll">
      <section class="sidebar-section">
        <p class="section-title">Агент</p>
        <button v-for="agent in agents" :key="agent.id" class="agent-card" :class="{ active: agent.id === selectedAgent }" :aria-pressed="agent.id === selectedAgent" :disabled="busy" @click="$emit('select-agent', agent.id)">
          <span class="agent-avatar">✦</span><span><strong>{{ agent.name }}</strong><small>{{ agent.description }}</small></span>
        </button>
        <p v-if="!agents?.length" class="muted">Агенты не загружены</p>
      </section>
      <details class="theme-section token-details">
        <summary>Оформление <span class="muted">{{ themes.find(item => item.id === theme)?.label }}</span></summary>
        <div class="theme-grid">
          <button v-for="item in themes" :key="item.id" :class="{ active: theme === item.id }" :aria-pressed="theme === item.id" :aria-label="`Тема: ${item.label}`" @click="$emit('theme', item.id)"><span>{{ item.icon }}</span>{{ item.label }}</button>
        </div>
      </details>
      <slot />
    </div>
  </aside>
</template>

