<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { privateApi, type PrivateSettings } from '../../api'

const emit = defineEmits<{ ready: [settings: PrivateSettings] }>()
defineProps<{ busy?: boolean }>()
const settings = ref<PrivateSettings | null>(null), password = ref(''), error = ref(''), loading = ref(true)
async function refresh() {
  loading.value = true; error.value = ''
  try { settings.value = await privateApi.settings(); emit('ready', settings.value) }
  catch (e) { error.value = String(e) } finally { loading.value = false }
}
async function login() {
  if (loading.value || !password.value) return
  loading.value = true; error.value = ''
  try { await privateApi.login(password.value); await refresh() }
  catch (e) { error.value = String(e) } finally { password.value = ''; loading.value = false }
}
async function logout() {
  loading.value = true
  try { await privateApi.logout(); await refresh() } catch (e) { error.value = String(e) } finally { loading.value = false }
}
function expired() { if (settings.value) settings.value = { ...settings.value, authenticated: false } }
onMounted(() => { window.addEventListener('private-session-expired', expired); void refresh() })
onUnmounted(() => window.removeEventListener('private-session-expired', expired))
</script>

<template>
  <template v-if="settings?.authenticated">
    <slot :settings="settings" />
    <div v-if="settings.enabled" class="private-status"><span>Приватный сервис · {{ settings.generationsPerMinute }} запросов/мин · окно {{ settings.contextTokens }} токенов</span><button :disabled="loading || busy" @click="logout">Выйти</button></div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
  </template>
  <main v-else class="private-login">
    <span class="eyebrow">День 30 · домашний AI-сервис</span>
    <h1>Ваш приватный чат</h1>
    <p>Ответы формирует Ollama на домашнем ПК. Для доступа с iPhone подключите приватную сеть ZeroTier.</p>
    <form v-if="settings" @submit.prevent="login">
      <label for="private-password">Пароль чата</label>
      <input id="private-password" v-model="password" type="password" autocomplete="current-password" :disabled="loading" required>
      <button class="primary" type="submit" :disabled="loading || !password">{{ loading ? 'Подключение…' : 'Войти' }}</button>
    </form>
    <p v-if="loading" role="status">Проверяем доступ к сервису…</p>
    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <button v-if="!settings && !loading" @click="refresh">Проверить подключение</button>
    <small>Пароль хранится на ПК в .env.day30 и не попадает в Git. В браузере используется временная HttpOnly-сессия.</small>
  </main>
</template>

<style>
.private-login{width:min(100%,500px);padding:36px 24px;margin:8vh auto;display:grid;gap:22px}.private-login p{color:#64736c}.private-login form{display:grid;gap:14px}.private-login input{width:100%;border:1px solid #cddcd1;padding:12px;border-radius:10px;background:white}.private-login small{font-size:12px;overflow-wrap:anywhere;color:#748071}.private-status{display:flex;align-items:center;justify-content:center;gap:12px;padding:8px 14px;background:#edf3e9;font-size:12px}.private-status button{padding:4px 12px}.app-shell.private-mode .chat-page{height:calc(100dvh - 98px)}@media(max-width:700px){.private-status{font-size:10px}.app-shell.private-mode .chat-page{height:calc(100dvh - 98px)}}
</style>
