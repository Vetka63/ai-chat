function rating(value) {
  return value ? `${value} / 5` : 'не оценено'
}

function valueOrDash(value) {
  return value === null || value === undefined || value === '' ? '—' : value
}

function successful(results) {
  return (results || []).filter((result) => result.status === 'success')
}

function minimumBy(items, selector) {
  return items.reduce((best, item) => (
    best === null || selector(item) < selector(best) ? item : best
  ), null)
}

export function summarizeModelComparison(results) {
  const available = successful(results)
  const priced = available.filter((result) => (
    result.metrics?.estimatedCostUsd !== null
    && result.metrics?.estimatedCostUsd !== undefined
  ))
  return {
    fastest: minimumBy(available, (result) => Number(result.metrics?.elapsedMs) || 0),
    fewestTokens: minimumBy(available, (result) => Number(result.metrics?.totalTokens) || 0),
    cheapest: minimumBy(priced, (result) => Number(result.metrics?.estimatedCostUsd) || 0),
  }
}

export function createModelComparisonReport({
  experimentId,
  task,
  results,
  ratings = {},
  winner,
  conclusion,
  judgeResult,
}) {
  const leaders = summarizeModelComparison(results)
  const winnerResult = (results || []).find((result) => result.id === winner)
  const lines = [
    '# День 5. Сравнение версий моделей',
    '',
    `Запуск: ${experimentId || 'не указан'}`,
    '',
    '## Исходный запрос',
    '',
    task || 'не указан',
  ]

  for (const result of results || []) {
    lines.push(
      '',
      `## ${result.title} · ${result.model}`,
      '',
      `Провайдер: ${result.provider}`,
      `Модель: [${result.model}](${result.modelUrl})`,
      `Тариф: [${result.pricingLabel}](${result.pricingUrl})`,
      `Качество: ${rating(ratings[result.id])}`,
      `Время ответа: ${valueOrDash(result.metrics?.elapsedMs)} мс`,
      `Входные токены: ${valueOrDash(result.metrics?.promptTokens)}`,
      `Выходные токены: ${valueOrDash(result.metrics?.completionTokens)}`,
      `Всего токенов: ${valueOrDash(result.metrics?.totalTokens)}`,
      `Расчётная стоимость: ${result.metrics?.estimatedCostUsd ?? 'не рассчитана'} USD`,
      '',
      result.answer || `Ошибка: ${result.error || 'ответ не получен'}`,
    )
  }

  lines.push(
    '',
    '## Сравнение',
    '',
    `Лучшее качество: ${winnerResult ? `${winnerResult.title} (${winnerResult.model})` : 'не выбрано'}`,
    `Самая быстрая: ${leaders.fastest ? `${leaders.fastest.title} — ${leaders.fastest.metrics.elapsedMs} мс` : 'нет данных'}`,
    `Наименьший расход токенов: ${leaders.fewestTokens ? `${leaders.fewestTokens.title} — ${leaders.fewestTokens.metrics.totalTokens}` : 'нет данных'}`,
    `Самая дешёвая: ${leaders.cheapest ? `${leaders.cheapest.title} — ${leaders.cheapest.metrics.estimatedCostUsd} USD` : 'нет данных'}`,
  )

  if (judgeResult) {
    lines.push(
      '',
      '## Оценка DeepSeek Pro',
      '',
      `Победитель: **${valueOrDash(judgeResult.winnerTitle)}**`,
      `Модель судьи: ${valueOrDash(judgeResult.model)}`,
      `API-вызовы судьи: ${valueOrDash(judgeResult.metrics?.apiCalls)}`,
      `Токены судьи: ${valueOrDash(judgeResult.metrics?.totalTokens)}`,
      `Расчётная стоимость судьи: ${judgeResult.metrics?.estimatedCostUsd ?? 'не рассчитана'} USD`,
      '',
      judgeResult.summary || 'Краткий вывод отсутствует.',
      '',
      '| Вариант | Средний балл | Точность | Следование задаче | Полнота | Ясность |',
      '|---|---:|---:|---:|---:|---:|',
    )
    for (const evaluation of judgeResult.evaluations || []) {
      lines.push(`| ${evaluation.title} | ${evaluation.average} | ${evaluation.scores.accuracy} | ${evaluation.scores.instructionFollowing} | ${evaluation.scores.completeness} | ${evaluation.scores.clarity} |`)
    }
  }

  lines.push(
    '',
    '## Короткий вывод',
    '',
    conclusion?.trim() || 'не указан',
    '',
    '> Время зависит от сети и нагрузки провайдера. Число токенов между провайдерами сравнивается ориентировочно, поскольку модели используют разные токенизаторы. Стоимость рассчитана по тарифам, зафиксированным в конфигурации эксперимента.',
  )

  return `${lines.join('\n')}\n`
}

export function downloadModelComparisonReport(reportData) {
  const shortId = reportData.experimentId?.slice(0, 8) || 'current'
  const blob = new Blob([createModelComparisonReport(reportData)], {
    type: 'text/markdown;charset=utf-8',
  })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `day5-model-comparison-${shortId}.md`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}
