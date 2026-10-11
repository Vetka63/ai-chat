<script setup lang="ts">
import { ref } from 'vue'
import ChatView from './features/chat/ChatView.vue'
import LabView from './features/lab/LabView.vue'
import AuthBoundary from './features/private/AuthBoundary.vue'
import type { PrivateSettings } from './api'

const page = ref(window.location.hash === '#lab' ? 'lab' : 'chat')
const busy = ref(false)
const settings = ref<PrivateSettings | null>(null)
function accessReady(value: PrivateSettings) { settings.value = value; busy.value = false; if (value.enabled) page.value = 'chat' }
function navigate(next: string) { if (!busy.value) { page.value = next; window.location.hash = next } }
</script>

<template>
  <AuthBoundary :busy="busy" @ready="accessReady">
  <div class="app-shell" :class="{ 'private-mode': settings?.enabled }">
    <nav class="workspace-nav" aria-label="Разделы приложения">
      <span class="workspace-brand">{{ settings?.enabled ? 'Private AI' : 'Local Lab' }} <small>без облачных моделей</small></span>
      <button :class="{ active: page === 'chat' }" :aria-current="page === 'chat' ? 'page' : undefined" :disabled="busy" @click="navigate('chat')">{{ settings?.enabled ? 'День 30 · Приватный чат' : 'День 27 · Чат' }}</button>
      <button v-if="!settings?.enabled" :class="{ active: page === 'lab' }" :aria-current="page === 'lab' ? 'page' : undefined" :disabled="busy" @click="navigate('lab')">День 26 · Проверки</button>
    </nav>
    <ChatView v-if="page === 'chat'" :service-settings="settings" @busy="busy = $event" />
    <LabView v-else @busy="busy = $event" />
  </div>
  </AuthBoundary>
</template>
