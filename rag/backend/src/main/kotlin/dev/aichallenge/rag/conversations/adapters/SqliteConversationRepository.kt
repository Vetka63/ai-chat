package dev.aichallenge.rag.conversations.adapters

import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.conversations.enums.TurnStatus
import dev.aichallenge.rag.conversations.models.*
import dev.aichallenge.rag.conversations.ports.ConversationRepository
import dev.aichallenge.rag.taskmemory.models.TaskMemory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/** Отдельные SQLite-таблицы чатов, обменов и памяти; общий файл не смешивает их с индексом. */
@Repository
class SqliteConversationRepository(properties: RagProperties, private val mapper: ObjectMapper) : ConversationRepository {
    private val database = Path.of(properties.databasePath).toAbsolutePath().normalize()
    init {
        Files.createDirectories(database.parent)
        connection().use { c -> c.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("CREATE TABLE IF NOT EXISTS conversations (id TEXT PRIMARY KEY, payload TEXT NOT NULL, revision INTEGER NOT NULL, updated_at TEXT NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS conversation_turns (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, request_id TEXT NOT NULL, status TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(conversation_id,request_id))")
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_pending_turn ON conversation_turns(conversation_id) WHERE status='PENDING'")
            s.execute("CREATE TABLE IF NOT EXISTS task_memory (conversation_id TEXT PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        } }
        // Один локальный backend. При старте старый pending уже не выполняется; user question сохраняем.
        write { c ->
            val ids = c.createStatement().use { s -> s.executeQuery("SELECT DISTINCT conversation_id FROM conversation_turns WHERE status='PENDING'").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
            ids.forEach { id ->
                val d = read(c, id)
                d.turns.filter { it.status == TurnStatus.PENDING }.forEach { updateTurn(c, it.copy(status = TurnStatus.INTERRUPTED, issue = "Обработка прервана перезапуском. Вопрос сохранён; автоматического повтора нет.", memoryAfter = d.memory)) }
                bump(c, id)
            }
        }
    }
    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:$database").also { c -> c.createStatement().use { s -> s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000") } }

    /** BEGIN IMMEDIATE сериализует короткие записи и исключает read→write upgrade race; API внутри него нет. */
    private fun <T> write(operation: (Connection) -> T): T = connection().use { c ->
        c.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try { val value = operation(c); c.createStatement().use { it.execute("COMMIT") }; value }
        catch (error: Exception) { c.createStatement().use { it.execute("ROLLBACK") }; throw error }
    }
    override fun create(conversation: Conversation): ConversationDetail = write { c ->
        c.prepareStatement("INSERT INTO conversations(id,payload,revision,updated_at) VALUES (?,?,?,?)").use { s -> s.setString(1, conversation.id); s.setString(2, mapper.writeValueAsString(conversation)); s.setLong(3, 0); s.setString(4, conversation.updatedAt); s.executeUpdate() }
        c.prepareStatement("INSERT INTO task_memory(conversation_id,payload) VALUES (?,?)").use { s -> s.setString(1, conversation.id); s.setString(2, mapper.writeValueAsString(TaskMemory())); s.executeUpdate() }
        read(c, conversation.id)
    }
    override fun list(): List<Conversation> = connection().use { c -> c.createStatement().use { s -> s.executeQuery("SELECT payload,revision,updated_at FROM conversations ORDER BY updated_at DESC,id").use { rs -> buildList { while (rs.next()) add(mapper.readValue(rs.getString(1), Conversation::class.java).copy(revision = rs.getLong(2), updatedAt = rs.getString(3))) } } } }
    override fun detail(id: String): ConversationDetail = connection().use { c -> c.autoCommit = false; try { read(c, id).also { c.commit() } } catch (e: Exception) { c.rollback(); throw e } }

    /** Идемпотентность проверяется до revision: потерянный HTTP-ответ можно получить без нового LLM-вызова. */
    override fun begin(id: String, request: SendTurn): TurnClaim = write { c ->
        val d = read(c, id)
        val existing = d.turns.find { it.requestId == request.requestId }
        if (existing != null) {
            if (existing.question != request.question) conflict("request_id_reused", "Этот requestId уже принадлежит другому вопросу.")
            TurnClaim(d, existing, false)
        } else {
            if (d.turns.any { it.status == TurnStatus.PENDING }) conflict("turn_pending", "В чате уже обрабатывается сообщение. Дождитесь результата.")
            if (d.conversation.revision != request.expectedRevision) conflict("stale_conversation", "Чат изменился в другой вкладке. Обновите его и повторите отправку.")
            val turn = ConversationTurn(UUID.randomUUID().toString(), request.requestId, request.question, Instant.now().toString(), TurnStatus.PENDING)
            c.prepareStatement("INSERT INTO conversation_turns(id,conversation_id,request_id,status,payload) VALUES (?,?,?,?,?)").use { s -> s.setString(1, turn.id); s.setString(2, id); s.setString(3, turn.requestId); s.setString(4, turn.status.name); s.setString(5, mapper.writeValueAsString(turn)); s.executeUpdate() }
            bump(c, id)
            TurnClaim(read(c, id), turn, true)
        }
    }
    /** Ответ и memoryAfter фиксируются одной транзакцией; ошибка не оставит полусохранённого state. */
    override fun finish(id: String, turn: ConversationTurn, memory: TaskMemory): ConversationDetail = write { c ->
        require(turn.status == TurnStatus.COMPLETED)
        val d = read(c, id)
        val pending = d.turns.find { it.id == turn.id } ?: conflict("turn_missing", "Сообщение не найдено.")
        if (pending.status != TurnStatus.PENDING) conflict("turn_not_pending", "Сообщение уже завершено или прервано.")
        require(pending.question == turn.question && pending.requestId == turn.requestId)
        updateTurn(c, turn.copy(memoryAfter = memory))
        c.prepareStatement("UPDATE task_memory SET payload=? WHERE conversation_id=?").use { s -> s.setString(1, mapper.writeValueAsString(memory)); s.setString(2, id); check(s.executeUpdate() == 1) }
        bump(c, id); read(c, id)
    }
    override fun delete(id: String) { write { c ->
        if (read(c, id).turns.any { it.status == TurnStatus.PENDING }) conflict("turn_pending", "Нельзя удалить чат во время обработки.")
        c.prepareStatement("DELETE FROM conversations WHERE id=?").use { s -> s.setString(1, id); s.executeUpdate() }; Unit
    } }
    private fun updateTurn(c: Connection, turn: ConversationTurn) { c.prepareStatement("UPDATE conversation_turns SET status=?,payload=? WHERE id=?").use { s -> s.setString(1, turn.status.name); s.setString(2, mapper.writeValueAsString(turn)); s.setString(3, turn.id); check(s.executeUpdate() == 1) } }
    private fun bump(c: Connection, id: String) { c.prepareStatement("UPDATE conversations SET revision=revision+1,updated_at=? WHERE id=?").use { s -> s.setString(1, Instant.now().toString()); s.setString(2, id); check(s.executeUpdate() == 1) } }
    private fun read(c: Connection, id: String): ConversationDetail {
        val conversation = c.prepareStatement("SELECT payload,revision,updated_at FROM conversations WHERE id=?").use { s -> s.setString(1, id); s.executeQuery().use { rs -> if (!rs.next()) throw LabException("conversation_not_found", "Чат не найден.", HttpStatus.NOT_FOUND); mapper.readValue(rs.getString(1), Conversation::class.java).copy(revision = rs.getLong(2), updatedAt = rs.getString(3)) } }
        val memory = c.prepareStatement("SELECT payload FROM task_memory WHERE conversation_id=?").use { s -> s.setString(1, id); s.executeQuery().use { rs -> check(rs.next()); mapper.readValue(rs.getString(1), TaskMemory::class.java) } }
        val turns = c.prepareStatement("SELECT payload FROM conversation_turns WHERE conversation_id=? ORDER BY rowid").use { s -> s.setString(1, id); s.executeQuery().use { rs -> buildList { while (rs.next()) add(mapper.readValue(rs.getString(1), ConversationTurn::class.java)) } } }
        return ConversationDetail(conversation, memory, turns)
    }
    private fun conflict(code: String, message: String): Nothing = throw LabException(code, message, HttpStatus.CONFLICT)
}
