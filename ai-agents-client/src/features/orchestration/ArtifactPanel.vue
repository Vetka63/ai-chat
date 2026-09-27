<script setup>
import { computed } from 'vue'

const props = defineProps({ artifacts: { type: Array, default: () => [] } })
const groups = computed(() => [
  { kind: 'collection', label: 'Подборки' },
  { kind: 'summary', label: 'Сводки' },
  { kind: 'draft', label: 'Черновики' },
  { kind: 'report', label: 'Отчёты' },
].map(group => ({ ...group, items: props.artifacts.filter(item => item.kind === group.kind) })))

function downloadReport(item) {
  const markdown = item.payload?.report_markdown
  if (!markdown) return
  const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = item.payload.file_name || `report-${item.id}.md`
  link.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <details class="artifact-panel">
    <summary>Рабочие артефакты чата <span>{{ artifacts.length }}</span></summary>
    <p v-if="!artifacts.length" class="muted">После поиска здесь появятся подборки, сводки и отчёты. Они сохраняются отдельно от сообщений.</p>
    <div v-for="group in groups" :key="group.kind" class="artifact-group">
      <strong>{{ group.label }} · {{ group.items.length }}</strong>
      <ul>
        <li v-for="item in group.items" :key="item.id">
          <span>{{ item.title }}</span>
          <small v-if="item.kind === 'collection'">{{ item.payload?.games?.length || 0 }} игр</small>
          <span v-else-if="item.kind === 'report'" class="report-actions">
            <small>Сохранён · {{ item.payload?.file_name }}</small>
            <button type="button" :aria-label="`Скачать отчёт ${item.title}`" @click="downloadReport(item)">Скачать MD</button>
          </span>
          <small v-else>ID {{ item.id.slice(0, 8) }}</small>
        </li>
        <li v-if="!group.items.length" class="muted">Пока нет</li>
      </ul>
    </div>
  </details>
</template>

<style scoped>
.artifact-panel { margin: 0 16px 10px; padding: 10px 14px; border: 1px solid var(--line); border-radius: 14px; background: var(--panel); color: var(--text); }
.artifact-panel > summary { cursor: pointer; font-weight: 650; font-size: 13px; }
.artifact-panel > summary span { margin-left: 6px; color: var(--muted); }
.artifact-panel p { margin: 12px 0 0; font-size: 12px; }
.artifact-group { padding-top: 12px; font-size: 12px; }
.artifact-group ul { list-style: none; margin: 5px 0 0; padding: 0; display: grid; gap: 4px; }
.artifact-group li { display: flex; justify-content: space-between; gap: 8px; padding: 6px 8px; border-radius: 8px; background: var(--accent-soft); }
.artifact-group li span { min-width: 0; overflow-wrap: anywhere; }
.artifact-group small, .muted { color: var(--muted); }
.report-actions { display: inline-flex; align-items: center; gap: 8px; }
.report-actions button { border: 1px solid var(--line); border-radius: 7px; background: var(--panel); color: var(--text); cursor: pointer; padding: 3px 7px; white-space: nowrap; }
.report-actions button:hover { border-color: var(--accent); }
</style>
