<script setup>
import { ref } from 'vue'

defineProps({ agent: Object, theme: String, busy: Boolean, mcpTools: Boolean })
defineEmits(['theme', 'close'])
const activeTab = ref('agent')
const themes = [
  { id: 'light', label: 'Светлая', icon: '☀' },
  { id: 'dark', label: 'Тёмная', icon: '◐' },
  { id: 'new-year', label: 'Новый год', icon: '✦' },
]
</script>

<template>
  <aside class="settings-sidebar" aria-label="Настройки агента">
    <header class="settings-header"><div><strong>Настройки</strong><small>{{ agent?.name || 'Текущий агент' }}</small></div><button class="icon-button" aria-label="Скрыть настройки" @click="$emit('close')">×</button></header>
    <div class="inspector-tabs" role="tablist" aria-label="Разделы инспектора">
      <button type="button" role="tab" id="inspector-tab-agent" aria-controls="inspector-pane-agent" :aria-selected="activeTab === 'agent'" @click="activeTab = 'agent'">Агент</button>
      <button type="button" role="tab" id="inspector-tab-context" aria-controls="inspector-pane-context" :aria-selected="activeTab === 'context'" @click="activeTab = 'context'">{{ mcpTools ? 'MCP' : 'Контекст' }}</button>
      <button type="button" role="tab" id="inspector-tab-metrics" aria-controls="inspector-pane-metrics" :aria-selected="activeTab === 'metrics'" @click="activeTab = 'metrics'">Метрики</button>
    </div>
    <div class="settings-scroll">
      <div v-show="activeTab === 'agent'" id="inspector-pane-agent" class="inspector-pane" role="tabpanel" aria-labelledby="inspector-tab-agent">
        <p class="inspector-intro">Настройки действуют для выбранного агента или текущего диалога. Переключить агента можно в левой панели.</p>
        <section class="sidebar-section">
          <p class="section-title">Текущий агент</p>
          <div v-if="agent" class="agent-card active">
            <span class="agent-avatar">✦</span><span><strong>{{ agent.name }}</strong><small>{{ agent.description }}</small></span>
          </div>
          <p v-else class="muted">Агент не выбран</p>
        </section>
        <details class="theme-section token-details">
          <summary>Оформление <span class="muted">{{ themes.find(item => item.id === theme)?.label }}</span></summary>
          <div class="theme-grid">
            <button v-for="item in themes" :key="item.id" :class="{ active: theme === item.id }" :aria-pressed="theme === item.id" :aria-label="`Тема: ${item.label}`" @click="$emit('theme', item.id)"><span>{{ item.icon }}</span>{{ item.label }}</button>
          </div>
        </details>
        <slot name="agent" />
      </div>
      <div v-show="activeTab === 'context'" id="inspector-pane-context" class="inspector-pane" role="tabpanel" aria-labelledby="inspector-tab-context">
        <slot name="context" />
      </div>
      <div v-show="activeTab === 'metrics'" id="inspector-pane-metrics" class="inspector-pane" role="tabpanel" aria-labelledby="inspector-tab-metrics">
        <slot name="metrics" />
      </div>
    </div>
  </aside>
</template>

