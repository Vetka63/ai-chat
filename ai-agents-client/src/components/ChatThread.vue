<script setup>
import { nextTick, ref, watch } from 'vue'

const props = defineProps({ messages: Array, agentName: String, sending: Boolean })
const thread = ref(null)

async function scrollToBottom(smooth = true) {
  await nextTick()
  if (!thread.value) return

  const options = { top: thread.value.scrollHeight, behavior: smooth ? 'smooth' : 'auto' }
  if (typeof thread.value.scrollTo === 'function') thread.value.scrollTo(options)
  else thread.value.scrollTop = options.top
}

watch(
  () => [props.messages, props.messages?.length, props.sending],
  (_, previous) => scrollToBottom(Boolean(previous)),
  { immediate: true },
)
</script>

<template>
  <section ref="thread" class="thread" aria-live="polite">
    <div v-if="!messages.length" class="welcome">
      <span class="welcome-orbit"><i>✦</i></span>
      <p class="eyebrow">АГЕНТ С ПОСТОЯННЫМ КОНТЕКСТОМ</p>
      <h1>О чём поговорим?</h1>
      <p>{{ agentName || 'Агент' }} сохранит сообщения в SQLite и вспомнит их даже после перезапуска приложения.</p>
      <div class="suggestions">
        <span>Объясни простую тему</span><span>Предложи три идеи</span><span>Помоги составить план</span>
      </div>
    </div>

    <div v-else class="message-list">
      <article v-for="message in messages" :key="message.id" class="message" :class="message.role">
        <div class="message-avatar">{{ message.role === 'user' ? 'В' : message.role === 'error' ? '!' : '✦' }}</div>
        <div class="message-body">
          <strong>{{ message.role === 'user' ? 'Вы' : message.role === 'error' ? 'Ошибка' : agentName }}</strong>
          <p>{{ message.content }}</p>
          <small v-if="message.model">{{ message.model }}<template v-if="message.source === 'demo'"> · demo</template></small>
        </div>
      </article>
      <article v-if="sending" class="message assistant pending">
        <div class="message-avatar">✦</div><div class="message-body"><strong>{{ agentName }}</strong><span class="typing"><i></i><i></i><i></i></span></div>
      </article>
    </div>
  </section>
</template>
