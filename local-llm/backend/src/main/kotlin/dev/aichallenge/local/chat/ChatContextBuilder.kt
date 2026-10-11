package dev.aichallenge.local.chat

import dev.aichallenge.local.generation.*
import org.springframework.stereotype.Component

/** Собирает messages только из выбранного чата. Ошибки и reasoning не становятся репликами ассистента. */
@Component
class ChatContextBuilder {
    /** Предварительная проверка будущего контекста до сохранения нового пользовательского хода. */
    fun buildNext(chat: Chat, content: String): List<LlmMessage> = buildList {
        add(LlmMessage(LlmRole.SYSTEM, chat.systemPrompt))
        chat.messages.filter { it.status == RunStatus.COMPLETED || it.status == RunStatus.TRUNCATED }
            .forEach { add(LlmMessage(it.role, it.content)) }
        add(LlmMessage(LlmRole.USER, content))
    }

    fun build(chat: Chat, pending: ChatMessage): List<LlmMessage> = buildList {
        add(LlmMessage(LlmRole.SYSTEM, chat.systemPrompt))
        chat.messages.filter { it.status == RunStatus.COMPLETED || it.status == RunStatus.TRUNCATED || it.id == pending.id }
            .forEach { add(LlmMessage(it.role, it.content)) }
    }

    companion object {
        const val SYSTEM_PROMPT = "Ты полезный локальный ассистент, запущенный через Ollama. " +
            "Учитывай предыдущие сообщения текущего диалога. Если пользователь явно просит определённый язык, используй его, " +
            "иначе отвечай на языке пользователя. Не утверждай, что посещал интернет или выполнил действия, которых не выполнял. " +
            "Если нужных сведений в диалоге нет, честно скажи об этом."
    }
}
