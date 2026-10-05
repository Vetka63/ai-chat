package dev.aichallenge.local.runs

import dev.aichallenge.local.generation.Run

/** Создаёт отчёт из сохранённого запуска, не вызывая модель повторно. */
fun Run.toMarkdown(): String = buildString {
    appendLine("# День 26 — локальная LLM")
    appendLine()
    appendLine("Модель: ${request.model}")
    appendLine("Дата: $createdAt")
    appendLine("Статус: $status")
    appendLine("ID: $id")
    appendLine()
    appendLine("## Параметры")
    appendLine()
    appendLine("Температура: ${request.temperature}")
    appendLine("Reasoning: ${if (request.thinking) "включён" else "выключен"}")
    appendLine("Максимум выходных токенов: ${request.maxOutputTokens ?: "не задан приложением"}")
    appendLine()
    appendLine("## Запрос")
    appendLine()
    appendLine(request.prompt)
    appendLine()
    appendLine("## Ответ")
    appendLine()
    appendLine(generation?.answer ?: error ?: "Запрос ещё выполняется")
    appendLine()
    appendLine("## Метрики")
    appendLine()
    appendLine("Input tokens: ${generation?.inputTokens ?: "—"}")
    appendLine("Output tokens: ${generation?.outputTokens ?: "—"}")
    appendLine("Время backend, мс: ${elapsedMilliseconds ?: "—"}")
    appendLine("Время Ollama, мс: ${generation?.totalMilliseconds ?: "—"}")
    appendLine("Загрузка модели, мс: ${generation?.loadMilliseconds ?: "—"}")
    appendLine("Скорость генерации, токенов/с: ${generation?.tokensPerSecond ?: "—"}")
    appendLine("Причина завершения: ${generation?.doneReason ?: "—"}")
}
