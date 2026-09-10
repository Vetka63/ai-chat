<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import RunMetrics from '../features/tokens/RunMetrics.vue'

const props = defineProps({ messages: Array, runs: Array, agentName: String, sending: Boolean })
const thread = ref(null)
const expanded = ref(new Set())
const metrics = computed(() => new Map((props.runs || []).map((r) => [r.assistant_index ?? r.user_index, r])))
watch(() => props.messages, () => { expanded.value = new Set() })

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
      <article v-for="(message, index) in messages" :key="index" class="message" :class="message.role">
        <div class="message-avatar">{{ message.role === 'user' ? 'В' : message.role === 'error' ? '!' : '✦' }}</div>
        <div class="message-body">
          <strong>{{ message.role === 'user' ? 'Вы' : message.role === 'error' ? 'Ошибка' : agentName }}</strong>
          <p>{{ expanded.has(index) || message.content.length <= 5000 ? message.content : message.content.slice(0, 1200) + '…' }}</p>
          <button v-if="message.content.length > 5000" class="report-button" @click="expanded.has(index) ? expanded.delete(index) : expanded.add(index)">{{ expanded.has(index) ? 'Свернуть' : `Показать весь текст (${message.content.length.toLocaleString('ru-RU')} символов)` }}</button>
          <small v-if="message.model">{{ message.model }}<template v-if="message.source === 'demo'"> · demo</template></small>
          <RunMetrics :run="metrics.get(index)" />
        </div>
      </article>
      <article v-if="sending" class="message assistant pending">
        <div class="message-avatar">✦</div><div class="message-body"><strong>{{ agentName }}</strong><span class="typing"><i></i><i></i><i></i></span></div>
      </article>
    </div>
  </section>
</template>
