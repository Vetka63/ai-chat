import type { Schema } from '../../api/client'

/** Отчёт использует только фактические измерения; ответы будущей LLM здесь не оцениваются. */
export function comparisonMarkdown(comparison: Schema<'IndexComparison'>, searches: Schema<'SearchResult'>[]): string {
  const indexes = comparison.indexes
  const lines = ['# День 21 — сравнение chunking', '', `Контролируемое сравнение корпуса/модели: ${comparison.comparable ? 'да' : 'нет'}`, '', '| Показатель | ' + indexes.map(i => i.config.strategy).join(' | ') + ' |', '| --- | --- | --- |']
  const rows: [string, (i: Schema<'IndexInfo'>) => string | number][] = [
    ['Чанков', i => i.metrics.chunkCount], ['Размер / overlap, символы', i => `${i.config.maxCharacters} / ${i.config.overlapCharacters}`],
    ['Минимум / медиана / p95', i => `${i.metrics.minCharacters} / ${i.metrics.medianCharacters} / ${i.metrics.p95Characters}`],
    ['Покрытие, %', i => i.metrics.coveragePercent], ['Чанков через границу раздела', i => i.metrics.crossSectionChunks],
    ['Разрезанных блоков команд', i => i.metrics.splitCodeBlocks], ['Время построения, мс', i => i.buildMilliseconds], ['Векторы, байт', i => i.vectorBytes],
  ]
  rows.forEach(([label, value]) => lines.push(`| ${label} | ${indexes.map(value).join(' | ')} |`))
  indexes.forEach(i => lines.push('', `## ${i.config.strategy}`, '', `Индекс: ${i.id}`, `Корпус: ${i.snapshotId}`, `Модель: ${i.embedding.model}`, `Digest: ${i.embedding.digest}`, `Размерность: ${i.embedding.dimension}`, `Embedding tokens (runtime): ${i.embeddingInputTokens ?? 'не предоставлены'}`))
  lines.push('', '## Ограничения сравнения', '', ...comparison.notes.map(n => `- ${n}`))
  searches.forEach(s => {
    lines.push('', '## Диагностический поиск', '', `Вопрос: ${s.query}`, `Индекс: ${s.indexId}`, `Время: ${s.milliseconds} мс`, '')
    s.hits.forEach(h => lines.push(`- ${h.rank}. cosine=${h.similarity.toFixed(4)}; ${h.chunk.title}; ${h.chunk.section}; chunk_id=${h.chunk.chunkId}`))
  })
  lines.push('', 'Это сравнение индексации и retrieval, не оценка ответов LLM. Размеры — символы, не токены.')
  return lines.join('\n')
}

/** Создаёт локальную загрузку файла без отправки отчёта во внешний сервис. */
export function download(name: string, content: string, type = 'text/markdown;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const link = document.createElement('a'); link.href = url; link.download = name; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
