import type { Schema } from '../../api/client'

/** Ответ и проверенные цитаты отделены от исходного непроверенного JSON диагностики. */
export function groundedMarkdown(r: Schema<'GroundedResult'>): string {
  const lines = ['# День 24 — ответ с источниками', '', `Вопрос: ${r.request.question}`, `Статус: ${r.status}; index=${r.request.indexId}; snapshot=${r.snapshotId}`, `Threshold=${r.request.similarityThreshold}; K=${r.request.candidateTopK}/${r.request.finalTopK}; budget=${r.request.contextMaxCharacters}; max output=${r.request.maxOutputTokens ?? 'не задан'}`, '', r.answer, r.clarification ?? '', '']
  r.claims.forEach((c, i) => { lines.push(`## Пункт ${i + 1}`, '', c.text, ''); c.citations.forEach(q => lines.push(`Источник: ${q.source.source} / ${q.source.section}; chunk_id=${q.source.chunkId}`, `Canonical UTF-16: [${q.canonicalStart},${q.canonicalEndExclusive})`, '', ...q.quote.split('\n').map(l => `> ${l}`), '')) })
  lines.push('## Список источников', '', ...r.sources.map(s => `- ${s.source} / ${s.section}; chunk_id=${s.chunkId}`), '', '## Проверка', '', ...r.issues.map(i => `- ${i.code}: ${i.message}`), `LLM-стадий=${r.llmStagesAttempted}; latency=${r.totalMilliseconds} мс; total API tokens=${r.totalUsage?.totalTokens ?? 'неизвестно'}`, '', ...r.warnings.map(w => `- ${w}`), '', '## Ручная оценка', '', '- Поддерживает ли каждая цитата смысл пункта:', '- Не потерян ли важный нюанс:')
  if (r.supportCheck) lines.push('', '## Дополнительная проверка смысла', '', `Статус=${r.supportCheck.status}; API tokens=${r.supportCheck.generation.usage?.totalTokens ?? 'неизвестно'}`, 'Вердикт модели не гарантирует истинность; смысловую приёмку следует проверять отдельно.')
  return lines.join('\n')
}
