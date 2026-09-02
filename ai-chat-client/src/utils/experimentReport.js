const metricKeys = [
  'apiCalls',
  'elapsedMs',
  'apiDurationMs',
  'promptTokens',
  'completionTokens',
  'totalTokens',
  'promptCacheHitTokens',
  'promptCacheMissTokens',
  'reasoningTokens',
]

export function combineMetrics(values = []) {
  const combined = Object.fromEntries(metricKeys.map((key) => [key, 0]))
  let estimatedCostUsd = 0
  let hasCost = false

  for (const value of values) {
    if (!value) continue
    for (const key of metricKeys) {
      combined[key] += Number(value[key]) || 0
    }
    if (value.estimatedCostUsd !== null && value.estimatedCostUsd !== undefined) {
      estimatedCostUsd += Number(value.estimatedCostUsd) || 0
      hasCost = true
    }
  }

  return {
    ...combined,
    estimatedCostUsd: hasCost ? Number(estimatedCostUsd.toFixed(8)) : null,
  }
}

function clone(value) {
  return value == null ? value : JSON.parse(JSON.stringify(value))
}

function averageRating(scores = {}) {
  const values = Object.values(scores).filter((value) => Number(value) > 0)
  if (!values.length) return null
  return Number((values.reduce((sum, value) => sum + Number(value), 0) / values.length).toFixed(1))
}

export function createExperimentSnapshot({
  experimentId,
  profile,
  task,
  results,
  ratings,
  winner,
  evaluationComment,
  judgeResult,
  generatedAt = new Date().toISOString(),
}) {
  const copiedResults = clone(results || [])
  const copiedJudge = clone(judgeResult)
  const solutionMetrics = combineMetrics(copiedResults.map((result) => result.metrics))
  const judgeMetrics = copiedJudge?.metrics || null
  const winnerResult = copiedResults.find((result) => result.strategy === winner)

  return {
    schemaVersion: 1,
    generatedAt,
    persistence: 'none',
    experimentId,
    profile: {
      id: profile?.id,
      name: profile?.name,
    },
    task,
    results: copiedResults,
    metrics: {
      solutions: solutionMetrics,
      judge: judgeMetrics,
      overall: combineMetrics([solutionMetrics, judgeMetrics]),
    },
    manualEvaluation: {
      winnerStrategy: winner || null,
      winnerTitle: winnerResult?.title || null,
      comment: evaluationComment?.trim() || null,
      ratings: copiedResults.map((result) => {
        const scores = clone(ratings?.[result.strategy] || {})
        return {
          strategy: result.strategy,
          title: result.title,
          scores,
          average: averageRating(scores),
        }
      }),
    },
    automaticJudge: copiedJudge || null,
  }
}

function valueOrDash(value) {
  return value === null || value === undefined || value === '' ? '—' : String(value)
}

function metricLine(metrics) {
  if (!metrics) return 'Метрики недоступны.'
  const cost = metrics.estimatedCostUsd === null || metrics.estimatedCostUsd === undefined
    ? 'не рассчитана (тарифы не настроены)'
    : `$${Number(metrics.estimatedCostUsd).toFixed(8)}`
  return [
    `API-вызовы: ${metrics.apiCalls || 0}`,
    `время: ${metrics.elapsedMs || 0} мс`,
    `токены: ${metrics.totalTokens || 0}`,
    `вход: ${metrics.promptTokens || 0}`,
    `выход: ${metrics.completionTokens || 0}`,
    `стоимость: ${cost}`,
  ].join(' · ')
}

export function renderExperimentMarkdown(snapshot) {
  const lines = [
    '# День 3 — сравнение способов рассуждения',
    '',
    `- Эксперимент: ${valueOrDash(snapshot.experimentId)}`,
    `- Профиль: ${valueOrDash(snapshot.profile?.name)} (${valueOrDash(snapshot.profile?.id)})`,
    `- Создано: ${valueOrDash(snapshot.generatedAt)}`,
    '- Хранение истории: отключено; файл является снимком текущего экрана.',
    '',
    '## Задача',
    '',
    valueOrDash(snapshot.task),
    '',
    '## Общие метрики решений',
    '',
    metricLine(snapshot.metrics?.solutions),
    '',
  ]

  for (const [index, result] of (snapshot.results || []).entries()) {
    lines.push(`## ${index + 1}. ${result.title || result.strategy}`, '')
    lines.push(`_${result.description || ''}_`, '')
    lines.push(metricLine(result.metrics), '')

    if (result.generatedPrompt) {
      lines.push('### Сгенерированный промпт', '', result.generatedPrompt, '')
    }
    if (result.experts?.length) {
      lines.push('### Решения экспертов', '')
      for (const expert of result.experts) {
        lines.push(`#### ${expert.role}`, '', expert.solution, '')
      }
      lines.push('### Общий итог', '', valueOrDash(result.consensus || result.answer), '')
      if (result.comparison) {
        lines.push('### Сравнение экспертов', '', result.comparison, '')
      }
    } else {
      lines.push('### Ответ', '', valueOrDash(result.answer), '')
    }
  }

  lines.push('## Ручная оценка', '')
  lines.push(`Победитель: **${valueOrDash(snapshot.manualEvaluation?.winnerTitle)}**`, '')
  lines.push(`Комментарий: ${valueOrDash(snapshot.manualEvaluation?.comment)}`, '')
  lines.push('| Способ | Средняя оценка | Правильность | Понятность | Полнота | Эффективность | Граничные случаи |')
  lines.push('|---|---:|---:|---:|---:|---:|---:|')
  for (const rating of snapshot.manualEvaluation?.ratings || []) {
    const scores = rating.scores || {}
    lines.push(`| ${rating.title} | ${valueOrDash(rating.average)} | ${valueOrDash(scores.correctness)} | ${valueOrDash(scores.clarity)} | ${valueOrDash(scores.completeness)} | ${valueOrDash(scores.efficiency)} | ${valueOrDash(scores.edgeCases)} |`)
  }
  lines.push('')

  if (snapshot.automaticJudge) {
    const judge = snapshot.automaticJudge
    lines.push('## Автоматический судья DeepSeek', '')
    lines.push(`Победитель: **${valueOrDash(judge.winnerTitle)}**`, '')
    lines.push(valueOrDash(judge.explanation), '')
    lines.push(metricLine(judge.metrics), '')
    lines.push('| Способ | Средняя оценка | Сильные стороны | Слабые стороны |')
    lines.push('|---|---:|---|---|')
    for (const evaluation of judge.evaluations || []) {
      lines.push(`| ${evaluation.title} | ${valueOrDash(evaluation.average)} | ${valueOrDash(evaluation.strengths)} | ${valueOrDash(evaluation.weaknesses)} |`)
    }
    lines.push('')
  } else {
    lines.push('## Автоматический судья DeepSeek', '', 'Не запускался.', '')
  }

  return `${lines.join('\n').trim()}\n`
}

export function downloadExperimentFile(snapshot, format) {
  const shortId = snapshot.experimentId?.slice(0, 8) || 'current'
  const isJson = format === 'json'
  const content = isJson
    ? `${JSON.stringify(snapshot, null, 2)}\n`
    : renderExperimentMarkdown(snapshot)
  const blob = new Blob([content], {
    type: isJson ? 'application/json;charset=utf-8' : 'text/markdown;charset=utf-8',
  })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `day3-experiment-${shortId}.${isJson ? 'json' : 'md'}`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}
