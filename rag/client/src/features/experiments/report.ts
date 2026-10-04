import type { Schema } from '../../api/client'

export const modeLabels: Record<Schema<'RetrievalMode'>, string> = { RAW: 'Обычный RAG', FILTERED: 'RAG + фильтр', REWRITE: 'RAG + rewrite', REWRITE_FILTERED: 'RAG + rewrite + фильтр' }
export const reasonLabels = { SELECTED: 'Отобран', BELOW_THRESHOLD: 'Ниже порога', TOP_K_LIMIT: 'Лимит final top-K' }
export const usageText = (u: Schema<'TokenUsage'> | null) => u ? `${u.promptTokens} / ${u.completionTokens} / ${u.totalTokens}` : 'Неизвестно'

/** Общий rewrite учитывается один раз; экспорт различает отбор и реальный вход в LLM. */
export function experimentMarkdown(result: Schema<'ExperimentComparison'>, expected?: Schema<'ControlQuestion'>): string {
  const r = result.request
  const lines = ['# День 23 — сравнение retrieval', '', `Вопрос: ${r.question}`, `Индекс: ${r.indexId}; snapshot=${result.snapshotId}`, `Candidate top-K=${r.candidateTopK}; final top-K=${r.finalTopK}; threshold=${r.similarityThreshold}; budget=${r.contextMaxCharacters}`, `Ответы: ${r.generateAnswers}; max output=${r.maxOutputTokens ?? 'не задан'}`, `LLM-стадий: ${result.llmStagesAttempted}; total latency=${result.totalMilliseconds} мс`, `API input/output/total (rewrite один раз): ${usageText(result.totalUsage)}`, '']
  if (expected) lines.push(`Ожидание: ${expected.expected}; source=${expected.expectedSourceSuffix}`, '')
  if (result.estimatedCost) lines.push(`Оценка USD: ${result.estimatedCost.minimumUsd}–${result.estimatedCost.maximumUsd}; ${result.estimatedCost.source}`, '')
  if (result.rewrite) lines.push('## Общий rewrite', '', `Поисковый вопрос: ${result.rewrite.query}`, `Модель: ${result.rewrite.model}; время=${result.rewrite.milliseconds} мс; usage=${usageText(result.rewrite.usage)}`, '')
  if (result.rewriteError) lines.push(`Ошибка rewrite: ${result.rewriteError.code} — ${result.rewriteError.message}`, '')
  for (const item of result.results) {
    lines.push(`## ${modeLabels[item.mode]}`, '', `Статус: ${item.status}`, item.message ?? item.error?.message ?? '')
    if (item.pipeline) {
      lines.push(`Поисковый запрос: ${item.pipeline.searchQuery}`, `Кандидатов=${item.pipeline.rawCandidates.length}; отобрано=${item.pipeline.selectedCandidates.length}; передано LLM=${item.answer?.context.included.length ?? 0}`, '', '### Решения по кандидатам', '')
      item.pipeline.decisions.forEach(d => lines.push(`- ${d.hit.chunk.source} / ${d.hit.chunk.section}; chunk_id=${d.hit.chunk.chunkId}; cosine=${d.hit.similarity.toFixed(4)}; ${reasonLabels[d.reason]}`))
    }
    if (item.answer) lines.push('', '### Ответ', '', item.answer.answer, '', `Model=${item.answer.model}; finish=${item.answer.finishReason}; truncated=${item.answer.truncated}; generation latency=${item.answer.generationMilliseconds} мс`, `API input/output/total: ${usageText(item.answer.usage)}`, '', ...item.answer.context.included.map(h => `Передан: ${h.chunk.source} / ${h.chunk.section}; chunk_id=${h.chunk.chunkId}`))
    lines.push('')
  }
  lines.push('## Ограничения', '', ...result.warnings.map(w => `- ${w}`), '', '## Ручной вывод', '', '- Правильность и полнота:', '- Какие источники помогли или потерялись:', '- Изменил ли rewrite смысл вопроса:', '- Расход, время и итог:')
  return lines.join('\n')
}
