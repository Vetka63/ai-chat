<script setup>
const model = defineModel({ type: String, default: '' })
defineProps({ disabled: Boolean, sending: Boolean })
defineEmits(['send'])

function submit(event) {
  if (event.shiftKey) return
  event.preventDefault()
  if (!event.isComposing) event.currentTarget.form.requestSubmit()
}
</script>

<template>
  <div class="composer-wrap">
    <form class="composer" @submit.prevent="$emit('send')">
      <textarea v-model="model" rows="1" maxlength="10000" placeholder="Напишите сообщение агенту…" :disabled="disabled" @keydown.enter="submit"></textarea>
      <button type="submit" aria-label="Отправить" :disabled="disabled || !model.trim()">{{ sending ? '•••' : '↑' }}</button>
    </form>
    <p>Enter — отправить · Shift + Enter — новая строка · история не сохраняется</p>
  </div>
</template>

