package dev.aichallenge.local.rag.storage

import dev.aichallenge.local.rag.config.RagProperties
import dev.aichallenge.local.rag.models.*
import dev.aichallenge.local.generation.RunStatus
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** Отдельные таблицы корпуса, индексов, векторов и экспериментов; не меняет БД чатов дня 27. */
@Repository
class RagRepository(config: RagProperties, private val mapper: ObjectMapper) {
    private val file = Path.of(config.databasePath).toAbsolutePath()
    private val url = "jdbc:sqlite:$file"
    init {
        Files.createDirectories(file.parent)
        connection().use { db -> db.createStatement().use { stmt ->
            for (table in listOf("rag_documents", "rag_indexes", "rag_experiments")) stmt.execute("CREATE TABLE IF NOT EXISTS $table (id TEXT PRIMARY KEY, created_at TEXT NOT NULL, payload TEXT NOT NULL)")
            stmt.execute("CREATE TABLE IF NOT EXISTS rag_vectors (index_id TEXT NOT NULL, chunk_id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(index_id,chunk_id))")
            stmt.execute("CREATE TABLE IF NOT EXISTS rag_query_embeddings (cache_key TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        } }
        indexes().filter { it.status == IndexStatus.BUILDING }.forEach { saveIndex(it.copy(status = IndexStatus.INTERRUPTED, error = "Индексация прервана перезапуском. Создайте новый индекс; готовые индексы сохранены.")) }
        experiments().filter { it.status in setOf(ExperimentStatus.PREPARING, ExperimentStatus.RUNNING) }.forEach {
            saveExperiment(it.copy(status = ExperimentStatus.INTERRUPTED, error = "Запуск прерван перезапуском. Автоматического повтора нет.",
                answers = it.answers.map { answer -> if (answer.status == RunStatus.RUNNING) answer.copy(status = RunStatus.FAILED, error = "Генерация прервана перезапуском.") else answer }))
        }
    }
    private fun connection() = DriverManager.getConnection(url).also { db -> db.createStatement().use { it.execute("PRAGMA busy_timeout=10000") } }
    private fun save(table: String, id: String, created: String, value: Any) = connection().use { db ->
        db.prepareStatement("INSERT INTO $table(id,created_at,payload) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload").use {
            it.setString(1, id); it.setString(2, created); it.setString(3, mapper.writeValueAsString(value)); it.executeUpdate()
        }
    }
    private fun <T> all(table: String, type: Class<T>): List<T> = connection().use { db ->
        db.createStatement().use { stmt -> stmt.executeQuery("SELECT payload FROM $table ORDER BY created_at DESC,id DESC").use { rows ->
            buildList { while (rows.next()) add(mapper.readValue(rows.getString(1), type)) }
        } }
    }
    private fun <T> find(table: String, id: String, type: Class<T>): T? = connection().use { db ->
        db.prepareStatement("SELECT payload FROM $table WHERE id=?").use { stmt -> stmt.setString(1, id); stmt.executeQuery().use { rows ->
            if (rows.next()) mapper.readValue(rows.getString(1), type) else null
        } }
    }
    fun documents() = all("rag_documents", RagDocument::class.java)
    fun document(id: String) = find("rag_documents", id, RagDocument::class.java) ?: throw RagFailure("Документ не найден.")
    fun saveDocument(d: RagDocument) = save("rag_documents", d.id, d.createdAt, d)
    fun indexes() = all("rag_indexes", RagIndex::class.java)
    fun index(id: String) = find("rag_indexes", id, RagIndex::class.java) ?: throw RagFailure("Индекс не найден.")
    fun saveIndex(i: RagIndex) = save("rag_indexes", i.id, i.createdAt, i)
    /** READY и все вектора публикуются одной транзакцией: неполный индекс нельзя искать. */
    fun publish(index: RagIndex, vectors: List<VectorChunk>) = connection().use { db ->
        db.autoCommit = false
        try {
            db.prepareStatement("INSERT INTO rag_vectors(index_id,chunk_id,payload) VALUES(?,?,?)").use { stmt ->
                vectors.forEach { stmt.setString(1, index.id); stmt.setString(2, it.chunk.id); stmt.setString(3, mapper.writeValueAsString(it)); stmt.addBatch() }; stmt.executeBatch()
            }
            db.prepareStatement("UPDATE rag_indexes SET payload=? WHERE id=?").use { it.setString(1, mapper.writeValueAsString(index)); it.setString(2, index.id); it.executeUpdate() }
            db.commit()
        } catch (failure: Exception) { db.rollback(); throw failure }
    }
    fun vectors(indexId: String): List<VectorChunk> = connection().use { db ->
        db.prepareStatement("SELECT payload FROM rag_vectors WHERE index_id=? ORDER BY chunk_id").use { stmt -> stmt.setString(1, indexId); stmt.executeQuery().use { rows ->
            buildList { while (rows.next()) add(mapper.readValue(rows.getString(1), VectorChunk::class.java)) }
        } }
    }
    fun experiment(id: String) = findExperiment(id) ?: throw RagFailure("Эксперимент не найден.")
    fun findExperiment(id: String) = find("rag_experiments", id, RagExperiment::class.java)
    fun experiments() = all("rag_experiments", RagExperiment::class.java)
    fun summaries() = experiments().map { ExperimentInfo(it.id, it.createdAt, it.request.question, it.request.mode, it.status) }
    fun saveExperiment(e: RagExperiment) = save("rag_experiments", e.id, e.createdAt, e)
    /** Ключ включает пространство и точный embedding input; старый digest не даст cache hit. */
    fun cachedQuery(key: String): CachedQueryEmbedding? = connection().use { db ->
        db.prepareStatement("SELECT payload FROM rag_query_embeddings WHERE cache_key=?").use { stmt ->
            stmt.setString(1, key); stmt.executeQuery().use { rows -> if (rows.next()) mapper.readValue(rows.getString(1), CachedQueryEmbedding::class.java) else null }
        }
    }
    fun cacheQuery(key: String, vector: List<Double>) = connection().use { db ->
        db.prepareStatement("INSERT OR REPLACE INTO rag_query_embeddings(cache_key,payload) VALUES(?,?)").use {
            it.setString(1, key); it.setString(2, mapper.writeValueAsString(CachedQueryEmbedding(vector))); it.executeUpdate()
        }
        // Небольшое учебное хранилище: не растёт бесконечно с каждым новым вопросом.
        db.createStatement().use { it.executeUpdate("DELETE FROM rag_query_embeddings WHERE rowid NOT IN (SELECT rowid FROM rag_query_embeddings ORDER BY rowid DESC LIMIT 1000)") }
    }
    /** Два провайдера обновляют разные карточки без lost update. Одна JVM — один владелец БД. */
    @Synchronized
    fun updateExperiment(id: String, change: (RagExperiment) -> RagExperiment): RagExperiment = change(experiment(id)).also { saveExperiment(it) }
}
