<script setup lang="ts">
import { ref } from 'vue'
import ChatView from './features/chat/ChatView.vue'
import LabView from './features/lab/LabView.vue'
import OptimizationView from './features/optimization/OptimizationView.vue'

const page = ref(window.location.hash.startsWith('#optimization') ? 'optimization' : window.location.hash === '#lab' ? 'lab' : 'chat')
const busy = ref(false)
function navigate(next: string) { if (!busy.value) { page.value = next; window.location.hash = next } }
</script>

<template>
  <div class="app-shell">
    <nav class="workspace-nav" aria-label="Разделы приложения">
      <span class="workspace-brand">Local Lab <small>локальные модели · эксперименты</small></span>
      <button :class="{ active: page === 'chat' }" :aria-current="page === 'chat' ? 'page' : undefined" :disabled="busy" @click="navigate('chat')">День 27 · Чат</button>
      <button :class="{ active: page === 'lab' }" :aria-current="page === 'lab' ? 'page' : undefined" :disabled="busy" @click="navigate('lab')">День 26 · Проверки</button>
      <button :class="{ active: page === 'optimization' }" :aria-current="page === 'optimization' ? 'page' : undefined" :disabled="busy" @click="navigate('optimization')">День 29 · Оптимизация</button>
    </nav>
    <ChatView v-if="page === 'chat'" @busy="busy = $event" />
    <LabView v-else-if="page === 'lab'" @busy="busy = $event" />
    <OptimizationView v-else @busy="busy = $event" />
  </div>
</template>
