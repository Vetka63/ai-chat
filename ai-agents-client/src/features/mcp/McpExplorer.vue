<script setup>
import { computed, onMounted, ref } from 'vue'
import { discoverMcpTools, listMcpServers } from './api'

defineEmits(['open-chats'])
const menuButton = ref(null)
defineExpose({ focusMenu: () => menuButton.value?.focus() })

const servers = ref([])
const selectedId = ref('')
const loading = ref(false)
const connecting = ref(false)
const error = ref('')
const result = ref(null)
const selected = computed(() => servers.value.find(server => server.id === selectedId.value))

async function loadServers() {
  loading.value = true
  error.value = ''
  try {
    servers.value = await listMcpServers()
    if (!servers.value.some(server => server.id === selectedId.value)) selectedId.value = servers.value[0]?.id || ''
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

async function connect() {
  if (!selectedId.value || connecting.value) return
  connecting.value = true
  result.value = null
  error.value = ''
  try {
    result.value = await discoverMcpTools(selectedId.value)
  } catch (cause) {
    error.value = cause.message
  } finally {
    connecting.value = false
  }
}

function chooseServer(id) {
  selectedId.value = id
  result.value = null
  error.value = ''
}

onMounted(loadServers)
</script>

<template>
  <header class="chat-header mcp-header">
    <button ref="menuButton" class="menu-button icon-button" aria-label="Открыть навигацию" @click="$emit('open-chats')">☰</button>
    <div><strong>Каталог MCP</strong><span><i></i> Подключение и доступные инструменты</span></div>
  </header>
  <div class="mcp-scroll">
    <div class="mcp-content">
      <div class="mcp-hero">
        <span class="mcp-eyebrow">MODEL CONTEXT PROTOCOL</span>
        <h1>Подключение к инструментам</h1>
        <p>Выберите разрешённый сервер. Python-клиент установит MCP-соединение, запросит <code>tools/list</code> и покажет инструменты, которые объявил сервер.</p>
      </div>

      <div class="mcp-flow" aria-label="Этапы проверки">
        <span>01 · Выбор сервера</span><b aria-hidden="true">→</b>
        <span>02 · MCP-соединение</span><b aria-hidden="true">→</b>
        <span>03 · Список инструментов</span>
      </div>

      <section class="mcp-card" aria-labelledby="mcp-server-title">
        <div class="mcp-card-heading">
          <div><small>ИСТОЧНИК ИНСТРУМЕНТОВ</small><h2 id="mcp-server-title">MCP-сервер</h2></div>
          <span class="mcp-transport">{{ selected?.transport === 'stdio' ? 'stdio · локально' : selected?.transport === 'streamable_http' ? 'HTTP · отдельный сервис' : selected?.transport || 'MCP' }}</span>
        </div>
        <p v-if="loading" role="status">Загружаем каталог серверов…</p>
        <p v-else-if="!servers.length" class="muted">Доступных MCP-серверов нет.</p>
        <div v-else class="mcp-server-list" role="group" aria-label="Выберите MCP-сервер">
          <button v-for="server in servers" :key="server.id" class="mcp-server-option" :class="{ selected: selectedId === server.id }"
            type="button" :aria-pressed="selectedId === server.id" @click="chooseServer(server.id)">
            <span class="mcp-server-symbol" aria-hidden="true">⌘</span>
            <span><strong>{{ server.name }}</strong><small>{{ server.description }}</small></span>
            <span class="mcp-check" aria-hidden="true">{{ selectedId === server.id ? '✓' : '' }}</span>
          </button>
        </div>
        <div class="mcp-card-actions">
          <span v-if="selected" class="muted">Параметры подключения задаются на бэкенде.</span>
          <button class="mcp-connect" type="button" :disabled="loading || connecting || !selectedId" @click="connect">
            {{ connecting ? 'Подключаемся…' : result ? 'Проверить ещё раз' : 'Подключиться и получить инструменты' }}
          </button>
        </div>
      </section>

      <p v-if="error" class="mcp-error" role="alert">{{ error }} <button type="button" @click="loadServers">Обновить каталог</button></p>

      <section v-if="result" class="mcp-card mcp-results" aria-labelledby="mcp-results-title">
        <div class="mcp-card-heading">
          <div><small>СОЕДИНЕНИЕ ПРОВЕРЕНО</small><h2 id="mcp-results-title">Инструменты сервера</h2></div>
          <span class="mcp-count">{{ result.tools.length }}</span>
        </div>
        <p class="mcp-connection-info">{{ result.server_name }} · протокол {{ result.protocol_version }} · соединение закрыто после получения списка</p>
        <p v-if="!result.tools.length" class="muted">Сервер ответил, но не объявил инструментов.</p>
        <div v-for="tool in result.tools" :key="tool.name" class="mcp-tool">
          <span class="mcp-tool-icon" aria-hidden="true">{ }</span>
          <div>
            <strong>{{ tool.title }}</strong>
            <code>{{ tool.name }}</code>
            <p>{{ tool.description || 'Описание не указано сервером.' }}</p>
            <details><summary>Схема входных параметров</summary><pre>{{ JSON.stringify(tool.input_schema, null, 2) }}</pre></details>
          </div>
        </div>
      </section>
      <p class="mcp-footnote">Это справочник серверов. Чтобы дать модели доступ к инструменту, откройте чат игрового MCP-агента и выберите сервер рядом с полем сообщения.</p>
    </div>
  </div>
</template>
