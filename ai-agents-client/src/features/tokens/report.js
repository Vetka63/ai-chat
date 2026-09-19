import { callType, compressionDescription, requestNumber } from '../context/compressionDisplay'

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
  const factUpdates = totals(runs.filter((r) => r.purpose === 'facts'))
  const savings = conversation?.token_savings
  const contextMode = conversation?.context_settings?.mode || 'full'
  const facts = conversation?.facts?.facts || {}
  const lineageLines = conversation ? [
    '## Положение в дереве диалогов', '',
    `Корневой диалог: ${conversation.root_conversation_id || conversation.id}.`,
    `Родитель: ${conversation.parent_conversation_id || 'нет'}. Checkpoint: ${conversation.checkpoint_id || 'нет'}. Название ветки: ${conversation.branch_name || 'исходный диалог'}.`, '',
    `Checkpoint в этом диалоге: ${(conversation.checkpoints || []).length}.`, '',
  ] : []
  const factsLines = contextMode === 'sticky_facts' || Object.keys(facts).length ? [
    '## Sticky Facts', '',
    ...(Object.keys(facts).length
      ? Object.entries(facts).map(([key, value]) => `- **${key}:** ${value}`)
      : ['Facts ещё не созданы.']),
    '', `Ревизия: ${conversation?.facts?.revision || 0}. Отдельных вызовов обновления: ${runs.filter((r) => r.purpose === 'facts').length}.`, '',
  ] : []
  const savingsLines = savings ? [
    '## Эффект сжатия', '',
    `Сопоставлено основных вызовов: ${savings.compared_dialogue_runs}; без usage: ${savings.unknown_dialogue_runs}.`, '',
    `Полный вход без сжатия ≈ ${savings.full_prompt_tokens} токенов; отправленный вход со сжатием ≈ ${savings.compressed_prompt_tokens}; сокращение входа ≈ ${savings.gross_input_savings_tokens}.`, '',
    `Создание сводок по API: ${savings.summary_usage_tokens} токенов; вызовов summary без usage: ${savings.unknown_summary_runs}.`, '',
    `Чистая экономия ≈ ${savings.net_savings_tokens ?? 'неизвестна'} токенов${savings.net_savings_percent == null ? '' : ` (${savings.net_savings_percent}% от исходного входа)`}.`, '',
    ...(savings.mixed_models ? ['### По моделям', '', ...savings.by_model.map((item) =>
      `- ${item.requested_model} (${item.method}): сокращение входа ≈ ${item.gross_input_savings_tokens}; summary API ${item.summary_usage_tokens}; чистый итог ≈ ${item.net_savings_tokens ?? 'неизвестен'}.`), ''] : []),
    'Формула: локальная оценка уменьшения входных prompt минус фактические API total_tokens всех вызовов summary. Ответы контрольного диалога без сжатия не выполнялись, поэтому результат является оценкой.', '',
  ] : []
  return [
    '# День 10 · Стратегии управления контекстом', '', title || 'Диалог', '',
    `Выбранная стратегия: ${contextMode}. Оригиналы хранятся в SQLite; состав запроса зависит от стратегии.`, '',
    `Известный расход API: ${summary.tokens} токенов. Оценка стоимости: ${money(summary.cost)}. Вызовов без usage: ${summary.unknown}.`, '',
    `Из них создание summary: ${compression.tokens} известных токенов, ${money(compression.cost)}; вызовов сжатия без usage: ${compression.unknown}. Это уже включено в итог выше.`, '',
    `Из них обновление facts: ${factUpdates.tokens} известных токенов, ${money(factUpdates.cost)}; вызовов facts без usage: ${factUpdates.unknown}. Это уже включено в итог выше.`, '',
    ...lineageLines,
    ...factsLines,
    ...savingsLines,
    '| Вызов | Тип / ваш запрос | Модель (запрошена → возвращена) | История ≈ | Сообщение ≈ | Prompt ≈ | API input | API output | API total | USD ≈ | Статус |',
    '|---|---|---|---:|---:|---:|---:|---:|---:|---:|---|',
    ...runs.map((r, i) => `| ${i + 1} | ${callType(r)} / №${requestNumber(conversation?.messages, r.user_index) ?? '—'} | ${r.requested_model} → ${r.returned_model || '—'} | ${r.estimate.history_tokens} | ${r.estimate.current_message_tokens} | ${r.estimate.prompt_tokens} | ${r.usage?.prompt_tokens ?? '—'} | ${r.usage?.completion_tokens ?? '—'} | ${r.usage?.total_tokens ?? '—'} | ${money(r.estimated_cost_usd)} | ${r.error_code || r.status} |`),
    '', '## Детали запусков', '',
    ...runs.flatMap((r, i) => [
      `### ${i + 1}. ${r.created_at}`, '',
      r.purpose === 'summary' ? compressionDescription(r, runs)
        : r.purpose === 'facts' ? `Обновление key-value памяти. Результат: ${r.status === 'success' ? 'facts сохранены' : 'прежние facts оставлены без изменений'}.`
          : `Назначение: ${r.purpose || 'dialogue'}. Режим: ${r.estimate.context_mode || 'full'}. Prompt полной истории ≈ ${r.estimate.full_prompt_tokens ?? '—'}; реально отправляемый prompt ≈ ${r.estimate.prompt_tokens}; передано сообщений хвоста: ${r.estimate.retained_message_count ?? '—'}; исключено: ${r.estimate.discarded_message_count ?? 0}; facts: ${r.estimate.fact_count ?? 0}; summary ≈ ${r.estimate.summary_tokens || 0} токенов.`, '',
      `Метод оценки: ${r.estimate.method}. Системный текст: ${r.estimate.system_tokens}. Окно: ${r.estimate.context_window}. Резерв ответа: ${r.estimate.reserved_output_tokens ?? 'не задан, по умолчанию API'}. Заполнение (${r.estimate.reserved_output_tokens == null ? 'только вход, резерв неизвестен' : 'с резервом'}): ${r.estimate.occupancy_percent}%.`, '',
      `Время: ${r.duration_ms ?? '—'} мс. Finish reason: ${r.finish_reason || '—'}. Кэш: ${r.usage?.cached_tokens ?? 'неизвестен'}. Reasoning: ${r.usage?.reasoning_tokens ?? 'неизвестен'} (часть output, не прибавляется повторно).`, '',
      `Ошибка: ${r.error_message || 'нет'}. HTTP провайдера: ${r.provider_status ?? '—'}.`, '',
      `Тариф за 1M токенов: input ${r.pricing.input_usd}, cached ${r.pricing.cached_input_usd}, output ${r.pricing.output_usd} USD. ${r.pricing.label}. [Источник](${r.pricing.source}), проверен ${r.pricing.checked_at}.`, '',
    ]),
    '## Как читать результаты', '',
    '- API input включает системный промпт, историю, текущий запрос и форматирование. Раздельные оценки текста не обязаны складываться в точный prompt.',
    '- Суммарный расход повторно учитывает историю при каждом вызове. Это не размер текущего контекста.',
    '- Неизвестный usage не означает бесплатный вызов. Оценка по публичному тарифу не является списанием со счёта (возможен бесплатный план).',
    '- Sliding Window отбрасывает старый контекст только из запроса; Sticky Facts добавляет отдельный платный LLM-вызов; Branching изолирует продолжения после общего checkpoint.',
    '- Чистая экономия summary оценочная: уменьшение prompt считается локально, а расход summary берётся из API. Для строгого эксперимента сравните диалоги с одной моделью.',
    '- context_limit_exceeded — ошибка окна модели. rate_limit — ограничение частоты/TPM, request_too_large — транспорт; это не доказательство переполнения контекста.',
    '', '## Вывод по эксперименту', '',
    'Пройдите один сценарий сбора ТЗ в Sliding Window и Sticky Facts, затем создайте две ветки от checkpoint. Сравните сохранение ранних требований, независимость решений и дополнительные LLM-вызовы. Запишите наблюдения здесь.', '',
    ...(conversation ? ['## Настройки и актуальная сводка', '',
      `Режим: ${contextMode}; N: ${conversation.context_settings?.keep_last ?? 10}; период summary: ${conversation.context_settings?.summarize_every ?? 10}.`, '',
      conversation.summary?.text || 'Сводка ещё не создана.', '', '## Сообщения для сравнения качества', '',
      ...(conversation.messages || []).flatMap((m) => [`### ${m.role}`, '', m.content, '']),
    ] : []),
  ].join('\n')
}

export function memoryReport(title, runs, conversation, workspace) {
  const summary = totals(runs)
  const proposals = runs.filter(r => r.purpose === 'memory_proposals')
  const json = value => JSON.stringify(value ?? null, null, 2).split('\n').map(line => '    ' + line).join('\n')
  return [
    '# День 11 · Три слоя памяти', '', title || 'Алгоритмическая задача', '',
    'История сохраняется полностью. В LLM передаются последние N сообщений, карточка задачи и активные подтверждённые записи. Предложения требуют решения пользователя.', '',
    'Анализ кода моделью не является исполнением кода. Локальные оценки токенов приблизительны; usage ниже получен от API.', '',
    `Известный расход: ${summary.tokens} токенов, стоимость ≈ ${money(summary.cost)}. Вызовов без usage: ${summary.unknown}. Вызовов предложений памяти: ${proposals.length} (включены в итог).`, '',
    '## Текущее состояние памяти', '', json(workspace), '',
    '## Снимки и метрики вызовов', '',
    ...runs.flatMap((r, i) => [
      `### ${i + 1}. ${callType(r)} · ${r.status}`, '',
      `Дата: ${r.created_at}. Модель: ${r.requested_model} → ${r.returned_model || 'неизвестна'}.`, '',
      `API input / output / total: ${r.usage?.prompt_tokens ?? '—'} / ${r.usage?.completion_tokens ?? '—'} / ${r.usage?.total_tokens ?? '—'}. USD ≈ ${money(r.estimated_cost_usd)}. Время: ${r.duration_ms ?? '—'} мс.`, '',
      `Prompt ≈ ${r.estimate.prompt_tokens}; рабочая ≈ ${r.estimate.working_memory_tokens ?? 0}; долговременная ≈ ${r.estimate.long_term_memory_tokens ?? 0}. Метод: ${r.estimate.method}.`, '',
      `Ошибка: ${r.error_code || 'нет'} ${r.error_message || ''}. Завершение: ${r.finish_reason || '—'}.`, '',
      `Источник тарифов: ${r.pricing?.source || 'не задан'}. Проверен: ${r.pricing?.checked_at || '—'}.`, '',
      'Память на момент запроса:', '', json(r.memory_context), '',
    ]),
    '## Переписка', '',
    ...(conversation?.messages || []).flatMap(m => [`### ${m.role}`, '', m.content, '']),
    '## Что сравнить', '',
    '1. Удалите раннее требование из окна N: агент больше не видит его в краткосрочной памяти.',
    '2. Сохраните требование в рабочую память: оно доступно после выхода сообщения из окна.',
    '3. Сохраните общее знание в долговременную память: оно доступно новой задаче; рабочая память туда не переносится.',
    '4. Отключите долговременную запись: сравните состав запроса и ответ. Неподтверждённые предложения не влияют на ответ.', '',
  ].join('\n')
}

export function downloadReport(title, runs, conversation, workspace) {
  const memory = conversation?.agent_id === 'algorithm_coach'
  const content = memory ? memoryReport(title, runs, conversation, workspace) : markdownReport(title, runs, conversation)
  const url = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = memory ? 'day-11-memory-layers.md' : 'day-10-context-strategies.md'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
