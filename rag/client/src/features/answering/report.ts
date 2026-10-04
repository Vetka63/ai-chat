import type { Schema } from '../../api/client'

/** Экспорт фиксирует реальные ответы, usage и контекст; победитель определяется вручную. */
export function answersMarkdown(results: Schema<'AnswerResult'>[], expected?: Schema<'ControlQuestion'>): string {
  const lines = ['# День 22 — сравнение с RAG / без RAG', '']
  if (expected) lines.push(`Контрольный вопрос: ${expected.id}`, `Ожидание: ${expected.expected}`, `Ожидаемый источник: ${expected.expectedSourceSuffix}`, '')
  for (const r of results) {
    lines.push(`## ${r.mode === 'RAG' ? 'С RAG' : 'Без RAG'}`, '', `Вопрос: ${r.question}`, `Модель: ${r.model}; temperature=${r.temperature}; thinking=${r.thinking}`, `Max output tokens: ${r.maxOutputTokens ?? 'не задан приложением'}`, `Время: ${r.totalMilliseconds} мс; LLM: ${r.generationMilliseconds} мс; retrieval: ${r.context.retrievalMilliseconds} мс`, `Завершение: ${r.finishReason}; неполный ответ: ${r.truncated}`, '', r.answer, '', '### Расход API', '', r.usage ? `Input=${r.usage.promptTokens}; output=${r.usage.completionTokens}; total=${r.usage.totalTokens}; cache hit=${r.usage.cacheHitTokens ?? 'неизвестно'}; cache miss=${r.usage.cacheMissTokens ?? 'неизвестно'}` : 'Usage отсутствует; расход неизвестен.')
    if (r.estimatedCost) lines.push(`Оценка USD: ${r.estimatedCost.minimumUsd.toFixed(6)}–${r.estimatedCost.maximumUsd.toFixed(6)}. ${r.estimatedCost.note}`, `Тариф: ${r.estimatedCost.source}, проверен ${r.estimatedCost.verifiedOn}`)
    lines.push('', '### Переданные материалы', '', `Индекс: ${r.context.indexId ?? 'не использовался'}; snapshot: ${r.context.snapshotId ?? 'нет'}`, `Текст чанков: ${r.context.textCharacters}/${r.context.maxCharacters} символов; включено: ${r.context.included.length}; исключено: ${r.context.omittedChunkIds.length}`)
    r.context.included.forEach(h => lines.push(`- ${h.chunk.source} / ${h.chunk.section}; chunk_id=${h.chunk.chunkId}; cosine=${h.similarity.toFixed(4)}`))
    lines.push('', '### Ограничения', '', ...r.warnings.map(w => `- ${w}`), '')
  }
  lines.push('## Ручное сравнение', '', '- Что совпало с ожиданием:', '- Что было упущено или неверно:', '- Какие фрагменты помогли:', '- Итог: RAG лучше / без RAG лучше / равно / недостаточно данных.', '', 'Переданные источники не являются автоматически проверенными цитатами ответа.')
  return lines.join('\n')
}
