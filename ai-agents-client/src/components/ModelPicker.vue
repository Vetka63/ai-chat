<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({ models: Array, modelId: String, busy: Boolean })
const emit = defineEmits(['model'])
const root = ref(null)
const trigger = ref(null)
const open = ref(false)
const selected = computed(() => props.models?.find(item => item.id === props.modelId))

async function toggle() {
  if (props.busy) return
  open.value = !open.value
  if (open.value) {
    await nextTick()
    const options = root.value.querySelectorAll('[role="menuitemradio"]:not(:disabled)')
    ;([...options].find(item => item.getAttribute('aria-checked') === 'true') || options[0])?.focus()
  }
}
function close(restore = false) {
  open.value = false
  if (restore) trigger.value?.focus()
}
function choose(item) {
  if (props.busy || !item.available) return
  emit('model', item.id)
  close(true)
}
function navigate(event) {
  if (event.key === 'Escape') { event.stopPropagation(); close(true); return }
  if (event.key === 'Tab') { close(); return }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const options = [...root.value.querySelectorAll('[role="menuitemradio"]:not(:disabled)')]
  if (!options.length) return
  const index = options.indexOf(document.activeElement)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? options.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length
  options[next].focus()
}
function outside(event) { if (!root.value?.contains(event.target)) close() }
watch(() => props.busy, busy => { if (busy) close() })
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>

<template>
  <div ref="root" class="model-picker">
    <button ref="trigger" type="button" class="model-trigger" :disabled="busy || !models?.length" aria-label="Модель для следующего сообщения" aria-haspopup="menu" :aria-expanded="open" @click="toggle" @keydown.down.prevent="!open && toggle()" @keydown.up.prevent="!open && toggle()">
      <span class="model-dot" aria-hidden="true"></span><strong>{{ selected?.title || 'Выберите модель' }}</strong><span aria-hidden="true">⌃</span>
    </button>
    <div v-if="open" class="model-menu" role="menu" aria-label="Выбор модели" @keydown="navigate">
      <p>Модель следующего ответа</p>
      <button v-for="item in models" :key="item.id" type="button" role="menuitemradio" :aria-checked="item.id === modelId" :disabled="busy || !item.available" @click="choose(item)">
        <span><strong>{{ item.title }}</strong><small>{{ item.available ? `Контекст: ${Number(item.context_window || 0).toLocaleString('ru-RU')} токенов` : 'Нужен ключ API' }}</small></span><span v-if="item.id === modelId" aria-hidden="true">✓</span>
      </button>
      <small class="model-menu-note">Модель можно менять. История этого чата сохраняется.</small>
    </div>
  </div>
</template>
