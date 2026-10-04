package dev.aichallenge.rag.answering.services

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.ports.LlmClient
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Общая генерация для дней 22 и 23: контекст уже выбран, retrieval здесь не выполняется. */
@Service
class AnswerGenerator(private val llm: LlmClient, private val assembler: PromptAssembler, private val costs: CostEstimator) {
    /** Передаёт исходный вопрос, возвращает финальный текст и реальные метрики API. */
    fun generate(request: AnswerRequest, context: AnswerContext, started: Long): AnswerResult {
        val messages = assembler.assemble(request.question.trim(), request.mode, context.included)
        val completion = llm.complete(messages, request.maxOutputTokens)
        val warnings = mutableListOf("Источники ниже — переданные фрагменты, не проверенные цитаты ответа. Проверка цитат появится в дне 24.")
        if (completion.finishReason == "length") warnings.add("Провайдер завершил ответ по лимиту: текст может быть неполным.")
        if (completion.usage == null) warnings.add("API не предоставил usage: точный расход и стоимость неизвестны.")
        if (context.omittedChunkIds.isNotEmpty()) warnings.add("Часть найденных чанков исключена из-за бюджета. Частичные чанки не отправлялись.")
        return AnswerResult(UUID.randomUUID().toString(), Instant.now().toString(), request.question.trim(), request.mode, completion.model, llm.settings().temperature, llm.settings().thinking, completion.content, completion.finishReason, completion.finishReason == "length", request.maxOutputTokens, (System.nanoTime() - started) / 1_000_000, completion.milliseconds, completion.usage, costs.estimate(completion.model, completion.usage), context, messages, warnings)
    }
}
