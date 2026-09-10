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

export function markdownReport(title, runs, conversation) {
  const summary = totals(runs)
  const compression = totals(runs.filter((r) => r.purpose === 'summary'))
  return [
    '# День 9 · Сжатие контекста и токены', '', title || 'Диалог', '',
    'Оригиналы хранятся полностью. Каждый запуск использует свой зафиксированный режим контекста. Сжатие — отдельный вызов LLM.', '',
    `Известный расход API: ${summary.tokens} токенов. Оценка стоимости: ${money(summary.cost)}. Вызовов без usage: ${summary.unknown}.`, '',
    `Из них создание summary: ${compression.tokens} известных токенов, ${money(compression.cost)}; вызовов сжатия без usage: ${compression.unknown}. Это уже включено в итог выше.`, '',
    '| № | Модель (запрошена → возвращена) | История ≈ | Сообщение ≈ | Prompt ≈ | API input | API output | API total | USD ≈ | Статус |',
    '|---|---|---:|---:|---:|---:|---:|---:|---:|---|',
    ...runs.map((r, i) => `| ${i + 1} | ${r.requested_model} → ${r.returned_model || '—'} | ${r.estimate.history_tokens} | ${r.estimate.current_message_tokens} | ${r.estimate.prompt_tokens} | ${r.usage?.prompt_tokens ?? '—'} | ${r.usage?.completion_tokens ?? '—'} | ${r.usage?.total_tokens ?? '—'} | ${money(r.estimated_cost_usd)} | ${r.error_code || r.status} |`),
    '', '## Детали запусков', '',
    ...runs.flatMap((r, i) => [
      `### ${i + 1}. ${r.created_at}`, '',
      `Назначение: ${r.purpose || 'dialogue'}. Режим: ${r.estimate.context_mode || 'full'}. Prompt без сжатия ≈ ${r.estimate.full_prompt_tokens ?? '—'}; отправляемый prompt ≈ ${r.estimate.prompt_tokens}; сводка ≈ ${r.estimate.summary_tokens || 0} токенов, охватывает ${r.estimate.summarized_messages || 0} сообщений.`, '',
      `Метод оценки: ${r.estimate.method}. Системный текст: ${r.estimate.system_tokens}. Окно: ${r.estimate.context_window}. Резерв ответа: ${r.estimate.reserved_output_tokens}. Заполнение с резервом: ${r.estimate.occupancy_percent}%.`, '',
      `Время: ${r.duration_ms ?? '—'} мс. Finish reason: ${r.finish_reason || '—'}. Кэш: ${r.usage?.cached_tokens ?? 'неизвестен'}. Reasoning: ${r.usage?.reasoning_tokens ?? 'неизвестен'} (часть output, не прибавляется повторно).`, '',
      `Ошибка: ${r.error_message || 'нет'}. HTTP провайдера: ${r.provider_status ?? '—'}.`, '',
      `Тариф за 1M токенов: input ${r.pricing.input_usd}, cached ${r.pricing.cached_input_usd}, output ${r.pricing.output_usd} USD. ${r.pricing.label}. [Источник](${r.pricing.source}), проверен ${r.pricing.checked_at}.`, '',
    ]),
    '## Как читать результаты', '',
    '- API input включает системный промпт, историю, текущий запрос и форматирование. Раздельные оценки текста не обязаны складываться в точный prompt.',
    '- Суммарный расход повторно учитывает историю при каждом вызове. Это не размер текущего контекста.',
    '- Неизвестный usage не означает бесплатный вызов. Оценка по публичному тарифу не является списанием со счёта (возможен бесплатный план).',
    '- Уменьшение prompt — локальная оценка, а не чистая экономия. Для чистого сравнения учитывайте фактический API total и стоимость ВСЕХ вызовов, включая summary, в обеих копиях чата.',
    '- context_limit_exceeded — ошибка окна модели. rate_limit — ограничение частоты/TPM, request_too_large — транспорт; это не доказательство переполнения контекста.',
    '', '## Вывод по эксперименту', '',
    'Сравните ответы на одинаковый контрольный вопрос в двух копиях одной исходной истории с одной моделью. Проверьте сохранение фактов/чисел/ограничений, качество ответа, расходы с учётом summary. Запишите наблюдения здесь.', '',
    ...(conversation ? ['## Настройки и актуальная сводка', '',
      `Режим: ${conversation.context_settings?.mode || 'full'}; N: ${conversation.context_settings?.keep_last ?? 10}; период: ${conversation.context_settings?.summarize_every ?? 10}.`, '',
      conversation.summary?.text || 'Сводка ещё не создана.', '', '## Сообщения для сравнения качества', '',
      ...(conversation.messages || []).flatMap((m) => [`### ${m.role}`, '', m.content, '']),
    ] : []),
  ].join('\n')
}

export function downloadReport(title, runs, conversation) {
  const url = URL.createObjectURL(new Blob([markdownReport(title, runs, conversation)], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = 'day-9-context.md'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
