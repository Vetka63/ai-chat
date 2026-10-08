package dev.aichallenge.local.optimization.storage

import dev.aichallenge.local.optimization.config.OptimizationProperties
import dev.aichallenge.local.optimization.models.*
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** Отдельная SQLite сохраняет запросы, точные промпты, ответы, оценки и параметры без API-ключа. */
@Repository
class ExperimentRepository(config: OptimizationProperties, private val mapper: ObjectMapper) {
    private val file = Path.of(config.databasePath).toAbsolutePath()
    private val url = "jdbc:sqlite:$file"
    init {
        Files.createDirectories(file.parent)
        connection().use { db -> db.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS optimization_experiments (id TEXT PRIMARY KEY, created_at TEXT NOT NULL, payload TEXT NOT NULL)") } }
        list().filter { it.status == ExperimentStatus.RUNNING }.forEach { experiment ->
            save(experiment.copy(status = ExperimentStatus.INTERRUPTED, items = experiment.items.map {
                if (it.status in setOf(ItemStatus.PENDING, ItemStatus.RUNNING)) it.copy(status = ItemStatus.INTERRUPTED,
                    error = "Backend перезапустился. Генерация автоматически не повторяется.") else it
            }))
        }
    }
    private fun connection() = DriverManager.getConnection(url).also { db -> db.createStatement().use { it.execute("PRAGMA busy_timeout=10000") } }
    fun save(experiment: Experiment) = connection().use { db ->
        db.prepareStatement("INSERT INTO optimization_experiments(id,created_at,payload) VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload").use {
            it.setString(1, experiment.id); it.setString(2, experiment.createdAt); it.setString(3, mapper.writeValueAsString(experiment)); it.executeUpdate()
        }
    }
    fun find(id: String): Experiment? = connection().use { db -> db.prepareStatement("SELECT payload FROM optimization_experiments WHERE id=?").use { stmt ->
        stmt.setString(1, id); stmt.executeQuery().use { row -> if (row.next()) mapper.readValue(row.getString(1), Experiment::class.java) else null }
    } }
    fun list(): List<Experiment> = connection().use { db -> db.createStatement().use { stmt ->
        stmt.executeQuery("SELECT payload FROM optimization_experiments ORDER BY created_at DESC,id DESC").use { rows ->
            buildList { while (rows.next()) add(mapper.readValue(rows.getString(1), Experiment::class.java)) }
        }
    } }
    fun summaries() = list().map { ExperimentSummary(it.id, it.createdAt, it.request.provider, it.model, it.request.promptProfile,
        it.request.skillEnabled, it.status, it.items.count { item -> item.status !in setOf(ItemStatus.RUNNING, ItemStatus.PENDING) }, it.items.size) }
    @Synchronized fun note(id: String, value: String): Experiment {
        val experiment = find(id) ?: throw OptimizationMissing()
        if (experiment.status == ExperimentStatus.RUNNING) throw OptimizationFailure("Дождитесь завершения эксперимента.")
        if (value.length > 3000) throw OptimizationFailure("Вывод должен быть не длиннее 3000 символов.")
        return experiment.copy(note = value).also(::save)
    }
}
