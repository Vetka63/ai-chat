<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  options: { type: Array, default: () => [] },
  ariaLabel: { type: String, required: true },
  icon: { type: String, default: 'space' },
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue'])
const root = ref(null)
const trigger = ref(null)
const open = ref(false)
const highlightedIndex = ref(0)
const listboxId = 'dropdown-' + Math.random().toString(36).slice(2, 9)

const selectedOption = computed(() => (
  props.options.find((option) => option.id === props.modelValue)
  || props.options[0]
  || { id: '', name: 'Нет вариантов', description: '' }
))

function closeDropdown() {
  open.value = false
}

function selectOption(option) {
  emit('update:modelValue', option.id)
  closeDropdown()
  trigger.value?.focus()
}

function toggleDropdown() {
  if (props.disabled || !props.options.length) return
  open.value = !open.value
  if (open.value) {
    highlightedIndex.value = Math.max(
      0,
      props.options.findIndex((option) => option.id === selectedOption.value.id),
    )
  }
}

function handleKeydown(event) {
  if (props.disabled) return
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value) {
      toggleDropdown()
      return
    }
    const delta = event.key === 'ArrowDown' ? 1 : -1
    highlightedIndex.value = (
      highlightedIndex.value + delta + props.options.length
    ) % props.options.length
    return
  }
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    if (!open.value) {
      toggleDropdown()
    } else if (props.options[highlightedIndex.value]) {
      selectOption(props.options[highlightedIndex.value])
    }
    return
  }
  if (event.key === 'Escape') {
    event.preventDefault()
    closeDropdown()
  }
}

function handlePointerdown(event) {
  if (!root.value?.contains(event.target)) closeDropdown()
}

onMounted(() => document.addEventListener('pointerdown', handlePointerdown))
onBeforeUnmount(() => document.removeEventListener('pointerdown', handlePointerdown))
</script>

<template>
  <div ref="root" class="app-dropdown" :class="{ 'app-dropdown--open': open }">
    <button
      ref="trigger"
      type="button"
      class="dropdown-trigger"
      :disabled="disabled"
      :aria-label="ariaLabel"
      :aria-expanded="open"
      :aria-controls="listboxId"
      aria-haspopup="listbox"
      @click="toggleDropdown"
      @keydown="handleKeydown"
    >
      <span class="dropdown-icon" :class="'dropdown-icon--' + icon" aria-hidden="true">
        <svg v-if="icon === 'space'" viewBox="0 0 24 24">
          <path d="M12 3 4.5 7.2 12 11.4l7.5-4.2L12 3Z" />
          <path d="m4.5 12 7.5 4.2 7.5-4.2M4.5 16.8 12 21l7.5-4.2" />
        </svg>
        <svg v-else-if="icon === 'format'" viewBox="0 0 24 24">
          <path d="M5 6h14M5 12h9M5 18h6" />
        </svg>
        <svg v-else viewBox="0 0 24 24">
          <path d="M12 3a9 9 0 1 0 9 9" />
          <path d="M12 3v5M12 12l3.5 2.2M18.5 5.5l1.7-1.7" />
        </svg>
      </span>
      <span class="dropdown-value">
        <strong>{{ selectedOption.name }}</strong>
        <small v-if="selectedOption.description">{{ selectedOption.description }}</small>
      </span>
      <svg class="dropdown-chevron" viewBox="0 0 24 24" aria-hidden="true">
        <path d="m7 10 5 5 5-5" />
      </svg>
    </button>

    <div v-if="open" :id="listboxId" class="dropdown-menu" role="listbox" :aria-label="ariaLabel">
      <button
        v-for="(option, index) in options"
        :key="option.id"
        type="button"
        class="dropdown-option"
        :class="{ 'dropdown-option--highlighted': index === highlightedIndex }"
        role="option"
        :aria-selected="option.id === selectedOption.id"
        @mouseenter="highlightedIndex = index"
        @click="selectOption(option)"
      >
        <span class="dropdown-option-check" aria-hidden="true">
          <svg v-if="option.id === selectedOption.id" viewBox="0 0 24 24">
            <path d="m5 12 4.2 4.2L19 6.5" />
          </svg>
        </span>
        <span class="dropdown-option-copy">
          <strong>{{ option.name }}</strong>
          <small v-if="option.description">{{ option.description }}</small>
        </span>
      </button>
    </div>
  </div>
</template>
