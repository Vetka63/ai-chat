package dev.aichallenge.local.rag.corpus

import dev.aichallenge.local.rag.models.*
import dev.aichallenge.local.rag.storage.RagRepository
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Импортирует предоставленный текст; не открывает URL и не выполняет инструкции из книги. */
@Service
class CorpusService(private val repository: RagRepository) {
    @Synchronized
    fun import(bytes: ByteArray, filename: String, title: String, startMarker: String?): DocumentInfo {
        if (bytes.isEmpty() || bytes.size > 5_000_000) throw RagFailure("Нужен UTF-8 TXT размером до 5 МБ.")
        val decoded = try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
        catch (_: Exception) { throw RagFailure("Файл должен содержать корректный UTF-8 текст.") }
        var text = decoded.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").trim()
        if (text.contains('\u0000') || text.length < 100 || title.isBlank() || title.length > 150) throw RagFailure("Проверьте название и текст документа.")
        // Маркер задаёт человек: модель не угадывает границу между рекламой и рассказом.
        if (!startMarker.isNullOrBlank()) {
            val marker = startMarker.trim()
            val matches = Regex("(?m)^${Regex.escape(marker)}[ \\t]*$").findAll(text).toList()
            if (matches.size != 1) throw RagFailure("Начальный заголовок должен встретиться отдельной строкой ровно один раз.")
            text = text.substring(matches.single().range.first)
        }
        val document = RagDocument(UUID.randomUUID().toString(), title.trim(), filename.substringAfterLast('/').substringAfterLast('\\').take(180),
            sha256(text), text, Instant.now().toString())
        val existing = repository.documents().firstOrNull { it.sha256 == document.sha256 && it.title == document.title }
        if (existing != null) return info(existing)
        if (repository.documents().sumOf { it.text.length.toLong() } + text.length > 10_000_000) throw RagFailure("Корпус превышает 10 млн символов.")
        repository.saveDocument(document)
        return info(document)
    }
    fun documents() = repository.documents().map(::info)
    private fun info(d: RagDocument) = DocumentInfo(d.id, d.title, d.filename, d.sha256, d.text.length, d.createdAt)
}

/** Хеш нормализованного текста / prompt, не секретного ключа. */
fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
