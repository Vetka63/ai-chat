package dev.aichallenge.rag.indexing.adapters

import dev.aichallenge.rag.common.*
import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.documents.models.*
import dev.aichallenge.rag.indexing.enums.JobStatus
import dev.aichallenge.rag.indexing.models.*
import dev.aichallenge.rag.indexing.ports.IndexRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/** SQLite хранит задания отдельно от атомарно опубликованных индексов; вектора — Float32 little-endian BLOB. */
@Repository
class SqliteIndexRepository(properties: RagProperties, private val mapper: ObjectMapper) : IndexRepository {
    private val database = Path.of(properties.databasePath).toAbsolutePath().normalize()
    init {
        Files.createDirectories(database.parent)
        connection().use { c -> c.createStatement().use { s ->
            s.execute("PRAGMA journal_mode=WAL")
            s.execute("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS snapshots (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            s.execute("CREATE TABLE IF NOT EXISTS indexes (id TEXT PRIMARY KEY, payload TEXT NOT NULL, snapshot_id TEXT NOT NULL REFERENCES snapshots(id))")
            s.execute("CREATE TABLE IF NOT EXISTS chunks (index_id TEXT NOT NULL REFERENCES indexes(id), chunk_id TEXT NOT NULL, position INTEGER NOT NULL, payload TEXT NOT NULL, vector BLOB NOT NULL, PRIMARY KEY(index_id,chunk_id))")
        } }
        jobs().filter { it.status in listOf(JobStatus.QUEUED, JobStatus.RUNNING) }.forEach {
            saveJob(it.copy(status = JobStatus.FAILED, updatedAt = Instant.now().toString(), error = "Построение прервано перезапуском backend. Готовые индексы сохранены; запустите новое задание."))
        }
    }
    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:$database").also { c -> c.createStatement().use { it.execute("PRAGMA foreign_keys=ON"); it.execute("PRAGMA busy_timeout=5000") } }
    override fun saveJob(job: IndexJob) = connection().use { c -> c.prepareStatement("INSERT OR REPLACE INTO jobs(id,payload) VALUES (?,?)").use { s ->
        s.setString(1, job.id); s.setString(2, mapper.writeValueAsString(job)); s.executeUpdate(); Unit
    } }
    override fun jobs(): List<IndexJob> = readList("SELECT payload FROM jobs ORDER BY rowid DESC", IndexJob::class.java)
    override fun job(id: String): IndexJob = jobs().find { it.id == id } ?: throw LabException("job_not_found", "Задание не найдено.", HttpStatus.NOT_FOUND)
    override fun indexes(): List<IndexInfo> = readList("SELECT payload FROM indexes ORDER BY rowid DESC", IndexInfo::class.java)
    override fun index(id: String): IndexInfo = indexes().find { it.id == id } ?: throw LabException("index_not_found", "Готовый индекс не найден.", HttpStatus.NOT_FOUND)
    override fun chunks(id: String): List<Chunk> {
        index(id)
        return connection().use { c -> c.prepareStatement("SELECT payload FROM chunks WHERE index_id=? ORDER BY position").use { s ->
            s.setString(1, id)
            s.executeQuery().use { rs -> buildList { while (rs.next()) add(mapper.readValue(rs.getString(1), Chunk::class.java)) } }
        } }
    }
    override fun vectors(id: String): List<Pair<Chunk, FloatArray>> {
        val info = index(id)
        return connection().use { c -> c.prepareStatement("SELECT payload,vector FROM chunks WHERE index_id=? ORDER BY position").use { s ->
            s.setString(1, id)
            s.executeQuery().use { rs -> buildList {
                while (rs.next()) {
                    val bytes = rs.getBytes(2)
                    require(bytes.size == info.embedding.dimension * 4)
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    add(mapper.readValue(rs.getString(1), Chunk::class.java) to FloatArray(info.embedding.dimension) { buffer.float })
                }
            } }
        } }
    }
    override fun document(indexId: String, documentId: String): Document {
        val info = index(indexId)
        val snapshot = connection().use { c -> c.prepareStatement("SELECT payload FROM snapshots WHERE id=?").use { s ->
            s.setString(1, info.snapshotId)
            s.executeQuery().use { rs -> require(rs.next()); mapper.readValue(rs.getString(1), CorpusSnapshot::class.java) }
        } }
        return snapshot.documents.find { it.id == documentId } ?: throw LabException("document_not_found", "Документ этого индекса не найден.", HttpStatus.NOT_FOUND)
    }
    override fun publish(snapshot: CorpusSnapshot, index: IndexInfo, chunks: List<Chunk>, vectors: List<FloatArray>, job: IndexJob) {
        require(chunks.size == vectors.size && chunks.size == index.metrics.chunkCount)
        connection().use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("INSERT OR IGNORE INTO snapshots(id,payload) VALUES (?,?)").use { s -> s.setString(1, snapshot.snapshotId); s.setString(2, mapper.writeValueAsString(snapshot)); s.executeUpdate() }
                c.prepareStatement("INSERT INTO indexes(id,payload,snapshot_id) VALUES (?,?,?)").use { s -> s.setString(1, index.id); s.setString(2, mapper.writeValueAsString(index)); s.setString(3, snapshot.snapshotId); s.executeUpdate() }
                c.prepareStatement("INSERT INTO chunks(index_id,chunk_id,position,payload,vector) VALUES (?,?,?,?,?)").use { s ->
                    chunks.forEachIndexed { position, chunk ->
                        val vector = vectors[position]
                        require(vector.size == index.embedding.dimension)
                        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                        vector.forEach { buffer.putFloat(it) }
                        s.setString(1, index.id); s.setString(2, chunk.chunkId); s.setInt(3, position); s.setString(4, mapper.writeValueAsString(chunk)); s.setBytes(5, buffer.array()); s.addBatch()
                    }
                    s.executeBatch()
                }
                c.prepareStatement("UPDATE jobs SET payload=? WHERE id=?").use { s -> s.setString(1, mapper.writeValueAsString(job)); s.setString(2, job.id); require(s.executeUpdate() == 1) }
                c.commit()
            } catch (error: Exception) { c.rollback(); throw error }
        }
    }
    private fun <T> readList(sql: String, type: Class<T>): List<T> = connection().use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(mapper.readValue(rs.getString(1), type)) } } } }
}
