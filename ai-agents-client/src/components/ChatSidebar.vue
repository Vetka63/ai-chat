<script setup>
defineProps({ conversations: Array, selectedConversation: String, agentName: String, busy: Boolean })
defineEmits(['new', 'select', 'delete', 'close'])
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
        <div v-for="item in conversations" :key="item.id" class="conversation-item" :class="{ active: item.id === selectedConversation }">
          <button class="conversation-title" :disabled="busy" :aria-current="item.id === selectedConversation ? 'page' : undefined" :title="item.title" @click="$emit('select', item.id)">
            <span>◌</span><strong>{{ item.title }}</strong>
            <small class="context-mode-badge" :title="item.context_settings?.mode === 'summary' ? 'Сжатая память' : 'Полная история'">{{ item.context_settings?.mode === 'summary' ? 'Σ' : '∞' }}</small>
          </button>
          <button class="delete-conversation" :disabled="busy" :aria-label="`Удалить чат «${item.title}»`" @click="$emit('delete', item)">×</button>
        </div>
      </nav>
    </section>
    <div class="sidebar-footer"><span class="agent-avatar">✦</span><div><strong>{{ agentName || 'Выберите агента' }}</strong><small>Чаты выбранного агента</small></div></div>
  </aside>
</template>
