<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import ChatView from './features/chat/ChatView.vue'
import LabView from './features/lab/LabView.vue'
import RagView from './features/rag/RagView.vue'

const page = ref(window.location.hash.startsWith('#rag') ? 'rag' : window.location.hash === '#lab' ? 'lab' : 'chat')
const busy = ref(false)
function syncPage() {
  if (busy.value) return
  const hash = window.location.hash
  page.value = hash === '#rag' || hash.startsWith('#rag/') ? 'rag' : hash === '#lab' ? 'lab' : 'chat'
}
onMounted(() => window.addEventListener('hashchange', syncPage))
onUnmounted(() => window.removeEventListener('hashchange', syncPage))
watch(busy, value => { if (!value) syncPage() })
function navigate(next: string) { if (!busy.value) { page.value = next; window.location.hash = next } }
</script>

<template>
  <div class="app-shell">
    <nav class="workspace-nav" aria-label="Разделы приложения">
      <span class="workspace-brand">Local Lab <small>локальные модели и RAG</small></span>
      <button :class="{ active: page === 'chat' }" :aria-current="page === 'chat' ? 'page' : undefined" :disabled="busy" @click="navigate('chat')">День 27 · Чат</button>
      <button :class="{ active: page === 'lab' }" :aria-current="page === 'lab' ? 'page' : undefined" :disabled="busy" @click="navigate('lab')">День 26 · Проверки</button>
      <button :class="{ active: page === 'rag' }" :aria-current="page === 'rag' ? 'page' : undefined" :disabled="busy" @click="navigate('rag')">День 28 · RAG</button>
    </nav>
    <ChatView v-if="page === 'chat'" @busy="busy = $event" />
    <LabView v-else-if="page === 'lab'" @busy="busy = $event" />
    <RagView v-else @busy="busy = $event" />
  </div>
</template>
