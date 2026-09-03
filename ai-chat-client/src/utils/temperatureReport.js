const recommendations = {
  precise: 'Подходит для вычислений, классификации и строгих инструкций.',
  balanced: 'Подходит для обычного чата, объяснений и контролируемой генерации идей.',
  creative: 'Подходит для мозгового штурма, названий, сюжетов и творческих вариантов.',
  experimental: 'Подходит для экспериментального поиска идей, когда допустим высокий риск неточностей.',
}

function rating(value) {
  return value ? `${value} / 5` : 'не оценено'
}

export function createTemperatureReport({
  experimentId,
  task,
  results,
  ratings = {},
  diversity,
  winner,
  conclusion,
  judgeResult,
}) {
  const lines = [
    '# День 4. Влияние температуры',
    '',
    `Запуск: ${experimentId || 'не указан'}`,
    '',
    '## Исходный запрос',
    '',
    task,
  ]

  for (const result of results) {
    lines.push(
      '',
      `## Temperature = ${result.temperature} · ${result.title}`,
      '',
      result.answer || `Ошибка: ${result.error || 'ответ не получен'}`,
      '',
      `Точность: ${rating(ratings[result.id]?.accuracy)}`,
      `Креативность: ${rating(ratings[result.id]?.creativity)}`,
      `Назначение: ${recommendations[result.id] || result.description}`,
    )
  }

  const winnerResult = results.find((result) => result.id === winner)
  lines.push(
    '',
    '## Сравнение и выводы',
    '',
    `Разнообразие ответов: ${rating(diversity)}`,
    `Лучший вариант: ${winnerResult
      ? `temperature = ${winnerResult.temperature} (${winnerResult.title})`
      : 'не выбран'}`,
    `Комментарий: ${conclusion?.trim() || 'не указан'}`,
    '',
    '## Рекомендации по применению',
    '',
    '- `temperature = 0` — точные и предсказуемые задачи.',
    '- `temperature = 0.7` — баланс точности и вариативности.',
    '- `temperature = 1.2` — творческие задачи и поиск необычных идей.',
    '- `temperature = 2.0` — максимальная вариативность и экспериментальный поиск.',
  )

  if (judgeResult) {
    lines.push(
      '',
      '## Оценка AI-судьи',
      '',
      `Модель: ${judgeResult.model || 'не указана'}`,
      `Победитель: temperature = ${judgeResult.winnerTemperature} (${judgeResult.winnerTitle})`,
      `Разнообразие: ${judgeResult.diversityScore} / 10 — ${judgeResult.diversityExplanation}`,
      `Объяснение: ${judgeResult.explanation}`,
    )
    for (const evaluation of judgeResult.evaluations || []) {
      lines.push(
        '',
        `### temperature = ${evaluation.temperature} · ${evaluation.title}`,
        '',
        `Средняя оценка: ${evaluation.average} / 10`,
        `Точность: ${evaluation.scores.accuracy} / 10`,
        `Креативность: ${evaluation.scores.creativity} / 10`,
        `Следование запросу: ${evaluation.scores.instructionFollowing} / 10`,
        `Сильные стороны: ${evaluation.strengths}`,
        `Слабые стороны: ${evaluation.weaknesses}`,
      )
    }
  }
  return lines.join('\n')
}
