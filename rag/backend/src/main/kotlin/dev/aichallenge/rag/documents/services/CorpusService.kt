package dev.aichallenge.rag.documents.services

import dev.aichallenge.rag.common.*
import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.documents.models.*
import dev.aichallenge.rag.documents.ports.DocumentLoader
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.round

/** Загружает только manifest-whitelist, проверяет хеши и фиксирует корпус на срок работы процесса. */
@Service
class CorpusService(private val properties: RagProperties, private val mapper: ObjectMapper, private val loader: DocumentLoader) {
    private val snapshot: CorpusSnapshot by lazy { readSnapshot() }
    fun snapshot(): CorpusSnapshot = snapshot
    fun info(): CorpusInfo {
        val docs = snapshot().documents
        val words = docs.sumOf { wordCount(it.text) }
        return CorpusInfo(snapshot().snapshotId, snapshot().manifest, docs.size, docs.sumOf { it.sections.size }, docs.sumOf { it.text.length }, words, round(words / 400.0 * 100) / 100, "Слова / 400; условные текстовые страницы, не страницы PDF")
    }
    fun list(): List<DocumentInfo> = snapshot().documents.map { DocumentInfo(it.id, it.title, it.chapter, it.source, it.text.length, wordCount(it.text), it.sections.size) }
    fun document(id: String): Document = snapshot().documents.find { it.id == id } ?: throw LabException("document_not_found", "Документ не найден.", org.springframework.http.HttpStatus.NOT_FOUND)
    private fun readSnapshot(): CorpusSnapshot {
        val root = Path.of(properties.corpusPath).toAbsolutePath().normalize()
        if (!Files.isRegularFile(root.resolve("manifest.json"))) throw LabException("corpus_missing", "Корпус ещё не подготовлен. Выполните scripts/prepare-corpus.ps1.", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
        val manifest = mapper.readValue(Files.readString(root.resolve("manifest.json")), CorpusManifest::class.java)
        require(manifest.revision.matches(Regex("[a-f0-9]{40}")) && manifest.files.isNotEmpty())
        require(manifest.files.map { it.path }.distinct().size == manifest.files.size)
        val verified = (manifest.files + manifest.resources).associate { file ->
            val path = root.resolve(file.path).normalize()
            require(path.startsWith(root) && file.path.startsWith("book/"))
            val bytes = Files.readAllBytes(path)
            if (sha256(bytes) != file.sha256) throw LabException("corpus_checksum", "Изменился исходный файл ${file.path}. Восстановите закреплённый snapshot.")
            file.path to bytes.toString(Charsets.UTF_8)
        }
        val docs = manifest.files.sortedBy { it.path }.map { file ->
            val path = root.resolve(file.path).normalize()
            require(path.startsWith(root) && file.path.endsWith(".asc") && file.path.startsWith("book/"))
            val bytes = Files.readAllBytes(path)
            if (sha256(bytes) != file.sha256) throw LabException("corpus_checksum", "Изменился исходный файл ${file.path}. Восстановите закреплённый snapshot.")
            val chapter = file.path.split('/')[1]
            val raw = bytes.toString(Charsets.UTF_8).replace(Regex("(?m)^include::([^\\[\\r\\n]+)\\[\\]\\s*$")) { match ->
                val target = root.relativize(path.parent.resolve(match.groupValues[1]).normalize()).toString().replace('\\', '/')
                val included = verified[target] ?: throw LabException("corpus_include", "Include отсутствует в проверенном manifest: $target", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                if (manifest.files.any { it.path == target }) "Включённый раздел доступен отдельным документом: $target."
                else included
            }
            loader.load(file.path, raw, chapter, "${manifest.repository}/blob/${manifest.revision}/${file.path}")
        }.filter { it.text.isNotBlank() }
        require(docs.isNotEmpty())
        val snapshotId = sha256(manifest.revision + docs.joinToString { it.source + it.sha256 } + "asciidoc-safe-v2")
        return CorpusSnapshot(manifest, docs, snapshotId, "asciidoc-safe-v2")
    }
}

/** Считает слова как непустые последовательности между пробелами; это не подсчёт токенов. */
fun wordCount(text: String): Int = Regex("\\S+").findAll(text).count()
