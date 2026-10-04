package dev.aichallenge.rag.conversations.ports

import dev.aichallenge.rag.conversations.models.*
import dev.aichallenge.rag.taskmemory.models.TaskMemory

/** Атомарные операции чата без сетевых вызовов под транзакцией; один pending turn на чат. */
interface ConversationRepository {
    fun create(conversation: Conversation): ConversationDetail
    fun list(): List<Conversation>
    fun detail(id: String): ConversationDetail
    fun begin(id: String, request: SendTurn): TurnClaim
    fun finish(id: String, turn: ConversationTurn, memory: TaskMemory): ConversationDetail
    fun delete(id: String)
}
