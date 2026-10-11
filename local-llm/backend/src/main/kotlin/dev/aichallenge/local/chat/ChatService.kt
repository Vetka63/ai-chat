package dev.aichallenge.local.chat

import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import dev.aichallenge.local.privateaccess.ServiceLimits
import dev.aichallenge.local.privateaccess.PrivateProperties
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Оркестрация диалога: серверная история, идемпотентность, явный retry и только локальный runtime. */
@Service
class ChatService(private val repository: ChatRepository, private val runtime: LocalLlmRuntime,
    private val context: ChatContextBuilder, private val gate: GenerationGate, private val config: LabProperties,
    private val limits: ServiceLimits = ServiceLimits(PrivateProperties())) {

    fun create(request: CreateChatRequest): Chat {
        if (request.model !in config.models) throw InvalidModel("Модель не разрешена серверной конфигурацией.")
        val now = Instant.now().toString()
        return repository.create(Chat(UUID.randomUUID().toString(), request.title.trim().ifBlank { "Новый чат" }, request.model,
            now, now, ChatContextBuilder.SYSTEM_PROMPT))
    }

    fun get(id: String): Chat = repository.find(id) ?: throw ChatMissing()
    fun list(): List<ChatSummary> = repository.list()
    fun delete(id: String) = gate.exclusive { repository.delete(id) }

    fun send(id: String, incoming: SendMessageRequest): Chat {
        val request = incoming.copy(requestId = incoming.requestId.lowercase(), content = incoming.content.trim())
        repository.user(id, request.requestId)?.let { return replay(id, it, request) }
        return gate.exclusive {
            repository.user(id, request.requestId)?.let { return@exclusive replay(id, it, request) }
            if (get(id).messages.any { it.status == RunStatus.RUNNING })
                throw ChatConflict("Предыдущее сообщение ещё обрабатывается. Обновите чат.")
            limits.context(context.buildNext(get(id), request.content))
            limits.generation()
            val pending = repository.begin(id, request)
            answer(id, pending)
        }
    }

    /** Повторять можно только последний неуспешный ход: более поздний контекст нельзя переписывать. */
    fun retry(id: String, requestId: String): Chat = gate.exclusive {
        val chat = get(id)
        val failed = chat.messages.lastOrNull { it.role == LlmRole.USER }
        if (failed?.requestId != requestId || failed.status != RunStatus.FAILED)
            throw ChatConflict("Повторить можно только последнее сообщение с ошибкой.")
        limits.context(context.build(chat, failed))
        limits.generation()
        answer(id, repository.retry(id, failed))
    }

    private fun replay(id: String, saved: ChatMessage, request: SendMessageRequest): Chat {
        if (saved.parameters != request) throw ChatConflict("Этот requestId уже использован с другим текстом или параметрами.")
        if (saved.status == RunStatus.RUNNING) throw ChatConflict("Сообщение уже обрабатывается. Обновите чат, повтор не запущен.")
        return get(id)
    }

    private fun answer(id: String, pending: ChatMessage): Chat {
        val start = System.nanoTime()
        val chat = get(id)
        val params = requireNotNull(pending.parameters)
        val messages = context.build(chat, pending)
        try {
            val generation = runtime.chat(LlmChatRequest(chat.model, messages, params.temperature, params.thinking, params.maxOutputTokens))
            repository.complete(id, pending, generation, elapsed(start), messages.size - 1)
        } catch (e: RuntimeFailure) { repository.fail(id, pending, e.message ?: "Локальная модель недоступна.", elapsed(start)) }
        catch (e: InvalidModel) { repository.fail(id, pending, e.message ?: "Модель недоступна.", elapsed(start)) }
        return get(id)
    }

    private fun elapsed(start: Long): Long = (System.nanoTime() - start) / 1_000_000
}
