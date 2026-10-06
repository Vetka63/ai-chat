package dev.aichallenge.local.chat

import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/** SQLite: отдельные chats/messages, внешние ключи, порядок реплик и атомарное завершение хода. */
@Repository
class ChatRepository(config: LabProperties, private val mapper: ObjectMapper) {
    private val file = Path.of(config.databasePath).toAbsolutePath()
    private val url = "jdbc:sqlite:$file"

    init {
        Files.createDirectories(file.parent)
        connection().use { db -> db.createStatement().use { sql ->
            sql.execute("CREATE TABLE IF NOT EXISTS chats (id TEXT PRIMARY KEY, updated_at TEXT NOT NULL, payload TEXT NOT NULL)")
            sql.execute("""CREATE TABLE IF NOT EXISTS messages (
                id TEXT PRIMARY KEY, chat_id TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
                ordinal INTEGER NOT NULL, request_id TEXT NOT NULL, role TEXT NOT NULL, status TEXT NOT NULL, payload TEXT NOT NULL,
                UNIQUE(chat_id, ordinal), UNIQUE(chat_id, request_id, role))""")
        } }
        recoverInterrupted()
    }

    private fun connection(): Connection = DriverManager.getConnection(url).also { db -> db.createStatement().use {
        it.execute("PRAGMA foreign_keys=ON"); it.execute("PRAGMA busy_timeout=5000")
    } }

    private fun <T> transaction(action: (Connection) -> T): T = connection().use { db ->
        db.autoCommit = false
        try { val result = action(db); db.commit(); result }
        catch (e: Exception) { db.rollback(); throw e }
    }

    fun create(chat: Chat): Chat = transaction { db ->
        db.prepareStatement("INSERT INTO chats(id,updated_at,payload) VALUES(?,?,?)").use {
            it.setString(1, chat.id); it.setString(2, chat.updatedAt); it.setString(3, mapper.writeValueAsString(chat)); it.executeUpdate()
        }
        chat
    }

    /** Чат и сообщения читаются в одном снимке БД, чтобы не показать половину завершённого хода. */
    fun find(id: String): Chat? = transaction { db -> readChat(db, id)?.copy(messages = readMessages(db, id)) }

    fun list(): List<ChatSummary> = connection().use { db ->
        db.createStatement().use { stmt -> stmt.executeQuery("""SELECT c.payload,
            (SELECT COUNT(*) FROM messages m WHERE m.chat_id=c.id) AS message_count
            FROM chats c ORDER BY c.updated_at DESC, c.id DESC""").use { rows -> buildList {
            while (rows.next()) {
                val chat = mapper.readValue(rows.getString(1), Chat::class.java)
                add(ChatSummary(chat.id, chat.title, chat.model, chat.updatedAt, rows.getInt(2)))
            }
        } } }
    }

    fun user(chatId: String, requestId: String): ChatMessage? = connection().use { db ->
        db.prepareStatement("SELECT payload FROM messages WHERE chat_id=? AND request_id=? AND role='USER'").use {
            it.setString(1, chatId); it.setString(2, requestId)
            it.executeQuery().use { rows -> if (rows.next()) mapper.readValue(rows.getString(1), ChatMessage::class.java) else null }
        }
    }

    /** Сохраняет USER до долгого HTTP-вызова; повтор того же requestId блокируется также UNIQUE в БД. */
    fun begin(chatId: String, request: SendMessageRequest): ChatMessage = transaction { db ->
        val chat = readChat(db, chatId) ?: throw ChatMissing()
        val now = Instant.now().toString()
        val user = ChatMessage(UUID.randomUUID().toString(), request.requestId, LlmRole.USER, request.content, RunStatus.RUNNING, now, request)
        insert(db, chatId, user)
        updateChat(db, chat.copy(title = if (chat.title == "Новый чат") request.content.take(55) else chat.title, updatedAt = now))
        user
    }

    /** Явный retry сохраняет ID и позицию пользовательского сообщения, не создавая дубликат. */
    fun retry(chatId: String, user: ChatMessage): ChatMessage = transaction { db ->
        val pending = user.copy(status = RunStatus.RUNNING, error = null, elapsedMilliseconds = null)
        updateMessage(db, pending)
        val chat = readChat(db, chatId) ?: throw ChatMissing()
        updateChat(db, chat.copy(updatedAt = Instant.now().toString()))
        pending
    }

    /** USER и ASSISTANT фиксируются одной транзакцией; частичный результат помечен TRUNCATED. */
    fun complete(chatId: String, user: ChatMessage, result: Generation, elapsed: Long, contextCount: Int) = transaction { db ->
        val status = if (result.doneReason == "length") RunStatus.TRUNCATED else RunStatus.COMPLETED
        updateMessage(db, user.copy(status = status, elapsedMilliseconds = elapsed, contextMessageCount = contextCount))
        val now = Instant.now().toString()
        insert(db, chatId, ChatMessage(UUID.randomUUID().toString(), user.requestId, LlmRole.ASSISTANT, result.answer,
            status, now, generation = result, elapsedMilliseconds = elapsed, contextMessageCount = contextCount))
        updateChat(db, (readChat(db, chatId) ?: throw ChatMissing()).copy(updatedAt = now))
    }

    fun fail(chatId: String, user: ChatMessage, error: String, elapsed: Long) = transaction { db ->
        updateMessage(db, user.copy(status = RunStatus.FAILED, error = error, elapsedMilliseconds = elapsed))
        updateChat(db, (readChat(db, chatId) ?: throw ChatMissing()).copy(updatedAt = Instant.now().toString()))
    }

    /** Каскад удаляет только сообщения указанного чата, не затрагивая runs дня 26. */
    fun delete(id: String) = transaction { db ->
        db.prepareStatement("DELETE FROM chats WHERE id=?").use {
            it.setString(1, id); if (it.executeUpdate() == 0) throw ChatMissing()
        }
    }

    private fun readChat(db: Connection, id: String): Chat? = db.prepareStatement("SELECT payload FROM chats WHERE id=?").use {
        it.setString(1, id); it.executeQuery().use { rows -> if (rows.next()) mapper.readValue(rows.getString(1), Chat::class.java) else null }
    }

    private fun readMessages(db: Connection, id: String): List<ChatMessage> = db.prepareStatement("SELECT payload FROM messages WHERE chat_id=? ORDER BY ordinal").use {
        it.setString(1, id); it.executeQuery().use { rows -> buildList { while (rows.next()) add(mapper.readValue(rows.getString(1), ChatMessage::class.java)) } }
    }

    private fun insert(db: Connection, chatId: String, message: ChatMessage) {
        db.prepareStatement("""INSERT INTO messages(id,chat_id,ordinal,request_id,role,status,payload)
            VALUES(?,?,(SELECT COALESCE(MAX(ordinal),0)+1 FROM messages WHERE chat_id=?),?,?,?,?)""").use {
            it.setString(1, message.id); it.setString(2, chatId); it.setString(3, chatId); it.setString(4, message.requestId)
            it.setString(5, message.role.name); it.setString(6, message.status.name); it.setString(7, mapper.writeValueAsString(message)); it.executeUpdate()
        }
    }

    private fun updateMessage(db: Connection, message: ChatMessage) = db.prepareStatement("UPDATE messages SET status=?,payload=? WHERE id=?").use {
        it.setString(1, message.status.name); it.setString(2, mapper.writeValueAsString(message)); it.setString(3, message.id)
        check(it.executeUpdate() == 1) { "Сообщение не найдено при сохранении" }
    }

    private fun updateChat(db: Connection, chat: Chat) = db.prepareStatement("UPDATE chats SET updated_at=?,payload=? WHERE id=?").use {
        it.setString(1, chat.updatedAt); it.setString(2, mapper.writeValueAsString(chat.copy(messages = emptyList()))); it.setString(3, chat.id)
        check(it.executeUpdate() == 1) { "Чат не найден при сохранении" }
    }

    /** После перезапуска сохраняет USER с ошибкой; автоматического повторного вызова модели нет. */
    private fun recoverInterrupted() = transaction { db ->
        val pending = db.createStatement().use { stmt -> stmt.executeQuery("SELECT payload FROM messages WHERE status='RUNNING'").use { rows ->
            buildList { while (rows.next()) add(mapper.readValue(rows.getString(1), ChatMessage::class.java)) }
        } }
        pending.forEach { updateMessage(db, it.copy(status = RunStatus.FAILED, error = "Backend перезапустился. Сообщение сохранено; ответ можно запросить повторно.")) }
    }
}
