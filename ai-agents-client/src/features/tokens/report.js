// Метрики с неизвестным usage не подменяются нулями даже для ошибочных запусков.
export const count = (value) => value == null ? '—' : Number(value).toLocaleString('ru-RU')
export const money = (value) => value == null ? 'неизвестно' : `$${Number(value).toFixed(6)}`

export function totals(runs) {
  return runs.reduce((sum, run) => ({
    tokens: sum.tokens + (run.usage?.total_tokens || 0),
    cost: sum.cost + Number(run.estimated_cost_usd || 0),
    known: sum.known + (run.usage ? 1 : 0),
    unknown: sum.unknown + (run.usage ? 0 : 1),
  }), { tokens: 0, cost: 0, known: 0, unknown: 0 })
}

export function markdownReport(title, runs) {
  const summary = totals(runs)
  return [
    '# День 8 · Работа с токенами', '', title || 'Диалог', '',
    'История передаётся полностью. Собственного лимита контекста и автоматического сжатия нет.', '',
    `Известный расход API: ${summary.tokens} токенов. Оценка стоимости: ${money(summary.cost)}. Вызовов без usage: ${summary.unknown}.`, '',
    '| № | Модель (запрошена → возвращена) | История ≈ | Сообщение ≈ | Prompt ≈ | API input | API output | API total | USD ≈ | Статус |',
    '|---|---|---:|---:|---:|---:|---:|---:|---:|---|',
    ...runs.map((r, i) => `| ${i + 1} | ${r.requested_model} → ${r.returned_model || '—'} | ${r.estimate.history_tokens} | ${r.estimate.current_message_tokens} | ${r.estimate.prompt_tokens} | ${r.usage?.prompt_tokens ?? '—'} | ${r.usage?.completion_tokens ?? '—'} | ${r.usage?.total_tokens ?? '—'} | ${money(r.estimated_cost_usd)} | ${r.error_code || r.status} |`),
    '', '## Детали запусков', '',
    ...runs.flatMap((r, i) => [
      `### ${i + 1}. ${r.created_at}`, '',
      `Метод оценки: ${r.estimate.method}. Системный текст: ${r.estimate.system_tokens}. Окно: ${r.estimate.context_window}. Резерв ответа: ${r.estimate.reserved_output_tokens}. Заполнение с резервом: ${r.estimate.occupancy_percent}%.`, '',
      `Время: ${r.duration_ms ?? '—'} мс. Finish reason: ${r.finish_reason || '—'}. Кэш: ${r.usage?.cached_tokens ?? 'неизвестен'}. Reasoning: ${r.usage?.reasoning_tokens ?? 'неизвестен'} (часть output, не прибавляется повторно).`, '',
      `Ошибка: ${r.error_message || 'нет'}. HTTP провайдера: ${r.provider_status ?? '—'}.`, '',
      `Тариф за 1M токенов: input ${r.pricing.input_usd}, cached ${r.pricing.cached_input_usd}, output ${r.pricing.output_usd} USD. ${r.pricing.label}. [Источник](${r.pricing.source}), проверен ${r.pricing.checked_at}.`, '',
    ]),
    '## Как читать результаты', '',
    '- API input включает системный промпт, историю, текущий запрос и форматирование. Раздельные оценки текста не обязаны складываться в точный prompt.',
    '- Суммарный расход повторно учитывает историю при каждом вызове. Это не размер текущего контекста.',
    '- Неизвестный usage не означает бесплатный вызов. Оценка по публичному тарифу не является списанием со счёта (возможен бесплатный план).',
    '- context_limit_exceeded — ошибка окна модели. rate_limit — ограничение частоты/TPM, request_too_large — транспорт; это не доказательство переполнения контекста.',
    '', '## Вывод по эксперименту', '',
    'Сопоставьте короткий и длинный диалоги по API input, а переполнение — по коду ошибки. Для сравнения используйте одну модель. Запишите наблюдения здесь.', '',
  ].join('\n')
}

export function downloadReport(title, runs) {
  const url = URL.createObjectURL(new Blob([markdownReport(title, runs)], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = 'day-8-tokens.md'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
