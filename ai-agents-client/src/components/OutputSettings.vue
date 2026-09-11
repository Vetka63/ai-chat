<script setup>
import { computed } from 'vue'
const props = defineProps({ limit: Number, model: Object, busy: Boolean })
const emit = defineEmits(['change'])
const maximum = computed(() => props.model?.max_output_tokens || 1200)
function update(event, commit = false) {
  const value = Number(event.target.value)
  if (Number.isInteger(value) && value >= 1 && value <= maximum.value) emit('change', value)
  else if (commit) event.target.value = props.limit
}
</script>

<template>
  <section class="token-panel sidebar-section output-settings">
    <p class="section-title">Длина ответа</p>
    <div class="token-details">
      <label class="context-choice"><input type="checkbox" :checked="limit != null" :disabled="busy || !model" @change="$emit('change', $event.target.checked ? Math.min(1200, maximum) : null)"> Ограничить ответ вручную</label>
      <template v-if="limit != null">
        <label class="output-number">Максимум токенов
          <input type="number" aria-label="Лимит ответа в токенах" :value="limit" min="1" :max="maximum" step="1" :disabled="busy" @input="update($event)" @change="update($event, true)">
        </label>
        <input class="output-slider" type="range" aria-label="Длина ответа в токенах" min="1" :max="maximum" step="1" :value="Math.min(limit, maximum)" :disabled="busy" @input="update">
        <small class="muted">От 1 до {{ maximum.toLocaleString('ru-RU') }} · потолок выбранной модели по каталогу.</small>
        <p v-if="limit > maximum" class="token-warning">Лимит выше потолка выбранной модели. Уменьшите его или отключите ограничение перед отправкой.</p>
      </template>
      <small v-else class="muted">По умолчанию провайдера: max_tokens не отправляется. Собственные ограничения API продолжают действовать.</small>
      <small class="muted">Применяется к следующему ответу и сохраняется при отправке. На служебную сводку памяти ползунок не влияет; её длина ограничивается инструкцией сжатия, а не фиксированным числом токенов приложения.</small>
    </div>
  </section>
</template>
