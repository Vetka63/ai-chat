<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { defaultPreferences, preferenceFields } from './options'
const props = defineProps({ profiles: Array, profile: Object, busy: Boolean, loading: Boolean, error: String, notice: String, preferredId: String })
const emit = defineEmits(['save', 'refresh'])
const selectedId = ref(''), creating = ref(false), name = ref(''), constraints = ref('')
const preferences = reactive(defaultPreferences())
const selected = computed(() => props.profiles?.find(p => p.id === selectedId.value))
const lines = computed(() => constraints.value.split('\n').map(v => v.trim()).filter(Boolean))
const valid = computed(() => name.value.trim() && lines.value.length <= 8 && lines.value.every(line => line.length <= 400))
function load(profile) {
  name.value = profile?.name || ''
  Object.assign(preferences, defaultPreferences(), profile?.preferences || {})
  preferences.preferred_code_language ??= ''
  constraints.value = (profile?.preferences?.soft_constraints || []).join('\n')
}
watch(() => props.profile?.id, id => { if (id) { creating.value = false; selectedId.value = id } }, { immediate: true })
watch(() => props.preferredId, id => { if (id) { creating.value = false; selectedId.value = id } })
watch(() => props.profiles, list => { if (!selectedId.value && list?.length) selectedId.value = props.profile?.id || list[0].id }, { immediate: true })
watch(() => [selected.value?.id, selected.value?.revision], () => { if (!creating.value) load(selected.value) }, { immediate: true })
function startCreate() { creating.value = true; load(null) }
function cancelCreate() { creating.value = false; load(selected.value) }
function submit() {
  if (!valid.value || props.busy || (!creating.value && !selected.value)) return
  emit('save', { ...(creating.value ? {} : { id: selected.value.id, revision: selected.value.revision }),
    name: name.value.trim(), preferences: { ...preferences, preferred_code_language: preferences.preferred_code_language || null, soft_constraints: lines.value } })
}
</script>

<template>
  <section class="profile-panel sidebar-section">
    <p class="section-title">Персонализация · День 12</p>
    <p v-if="profile" class="profile-binding">Профиль этой задачи: <strong>{{ profile.name }}</strong> · версия {{ profile.revision }}. Привязка фиксирована.</p>
    <p v-else class="muted">Профиль выбирается при создании задачи. Другой профиль — новая задача.</p>
    <p v-if="error" role="alert" class="token-warning">{{ error }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <details>
      <summary>Профили и предпочтения</summary>
      <p class="muted">Это локальные персоны, не аккаунты. Правка профиля влияет на все его задачи, но не меняет прошлые ответы.</p>
      <label v-if="!creating">Редактируемый профиль
        <select v-model="selectedId" :disabled="busy || loading">
          <option v-for="p in profiles" :key="p.id" :value="p.id">{{ p.name }} · v{{ p.revision }}</option>
        </select>
      </label>
      <p v-if="selected && profile && !creating && selected.id !== profile.id" class="muted">Вы редактируете другой профиль. Текущая задача останется у «{{ profile.name }}».</p>
      <form v-if="selected || creating" @submit.prevent="submit">
        <fieldset :disabled="busy || loading">
          <legend>{{ creating ? 'Новый профиль' : 'Мягкие предпочтения' }}</legend>
          <label>Имя профиля<input v-model="name" required maxlength="80"></label>
          <label v-for="field in preferenceFields" :key="field.key">{{ field.label }}
            <select v-model="preferences[field.key]">
              <option v-for="(label, value) in field.options" :key="value" :value="value">{{ label }}</option>
            </select>
          </label>
          <label>Пожелания и мягкие ограничения<textarea v-model="constraints" rows="4" maxlength="3208" placeholder="Например: объясняй без жаргона&#10;Одна запись на строку"></textarea></label>
          <small>До 8 строк, до 400 символов в каждой. Это не обязательные инварианты: текущей просьбой можно изменить стиль ответа. Условия задачи важнее предпочтений.</small>
          <p v-if="!valid && name" class="token-warning">Проверьте имя и длину пожеланий.</p>
          <button type="submit" :disabled="!valid">{{ creating ? 'Создать профиль' : 'Сохранить профиль' }}</button>
        </fieldset>
      </form>
      <div class="profile-actions">
        <button v-if="!creating" :disabled="busy || loading" @click="startCreate">Новый профиль</button>
        <button v-else :disabled="busy" @click="cancelCreate">Отмена создания</button>
        <button :disabled="busy || loading" @click="emit('refresh')">{{ loading ? 'Загрузка…' : 'Обновить профили' }}</button>
      </div>
      <small>Обновление списка перезагрузит изменённые версии. При конфликте сохраните свой черновик перед обновлением.</small>
    </details>
  </section>
</template>

<style scoped>
.profile-panel { font-size: 12px; line-height: 1.6; }
p { margin: 3px 0 8px; }
details { padding: 10px; border: 1px solid var(--line); border-radius: 10px; }
summary { cursor: pointer; font-weight: 600; }
fieldset { padding: 0; border: 0; min-width: 0; margin: 10px 0; }
legend { font-weight: 600; }
label { display: flex; flex-direction: column; gap: 5px; margin: 10px 0; }
input, select, textarea { width: 100%; min-width: 0; font: inherit; padding: 9px; border-radius: 8px; background: var(--panel); color: var(--text); border: 1px solid var(--line); }
textarea { resize: vertical; }
small { display: block; color: var(--muted); margin-bottom: 10px; }
button { cursor: pointer; font: inherit; border: 1px solid var(--line); border-radius: 8px; padding: 8px; background: var(--panel); color: var(--text); }
button:hover { border-color: var(--accent); }
button:disabled { opacity: .5; cursor: default; }
.profile-actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0; }
</style>
