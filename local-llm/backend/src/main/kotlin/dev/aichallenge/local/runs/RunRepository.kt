package dev.aichallenge.local.runs

import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** SQLite хранит полные запросы, ответы, ошибки и метрики отдельно от браузера. */
@Repository
class RunRepository(config: LabProperties, private val mapper: ObjectMapper) {
    private val file = Path.of(config.databasePath).toAbsolutePath()
    private val url = "jdbc:sqlite:$file"
    init {
        Files.createDirectories(file.parent)
        connection().use { db ->
            db.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS runs (id TEXT PRIMARY KEY, created_at TEXT NOT NULL, payload TEXT NOT NULL)") }
        }
        // JVM могла завершиться в середине HTTP-вызова. Не повторяем незавершённый запуск.
        listAll().filter { it.status == RunStatus.RUNNING }.forEach {
            save(it.copy(status = RunStatus.FAILED, error = "Backend перезапустился до сохранения ответа. Запрос автоматически не повторён."))
        }
    }

    private fun connection() = DriverManager.getConnection(url).also { db ->
        db.createStatement().use { it.execute("PRAGMA busy_timeout=5000") }
    }

    /** Атомарно заменяет запись одного запуска; соседние запуски не затрагиваются. */
    fun save(run: Run) = connection().use { db ->
        db.prepareStatement("INSERT INTO runs(id,created_at,payload) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload").use {
            it.setString(1, run.id); it.setString(2, run.createdAt); it.setString(3, mapper.writeValueAsString(run)); it.executeUpdate()
        }
    }

    fun find(id: String): Run? = connection().use { db ->
        db.prepareStatement("SELECT payload FROM runs WHERE id=?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rows -> if (rows.next()) mapper.readValue(rows.getString(1), Run::class.java) else null }
        }
    }

    fun summaries(): List<RunSummary> = listAll().map { RunSummary(it.id, it.createdAt, it.request.prompt, it.request.model, it.status) }

    private fun listAll(): List<Run> = connection().use { db ->
        db.createStatement().use { stmt -> stmt.executeQuery("SELECT payload FROM runs ORDER BY created_at DESC, id DESC").use { rows ->
            buildList { while (rows.next()) add(mapper.readValue(rows.getString(1), Run::class.java)) }
        } }
    }
}
