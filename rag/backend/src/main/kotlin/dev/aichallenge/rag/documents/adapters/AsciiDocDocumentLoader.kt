package dev.aichallenge.rag.documents.adapters

import dev.aichallenge.rag.common.sha256
import dev.aichallenge.rag.documents.models.*
import dev.aichallenge.rag.documents.ports.DocumentLoader
import org.springframework.stereotype.Component

/** Без исполнения AsciiDoc обрабатывает текстовые секции Pro Git, сохраняет заголовки и команды. */
@Component
class AsciiDocDocumentLoader : DocumentLoader {
    override fun load(source: String, raw: String, chapter: String, sourceUrl: String): Document {
        val text = StringBuilder()
        val blocks = mutableListOf<TextBlock>()
        val headings = mutableListOf<Pair<String, Int>>()
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines()
        var inCode = false
        var pending = mutableListOf<String>()
        var blockLine = 1
        fun flush(kind: String) {
            val content = pending.joinToString("\n").trim()
            pending = mutableListOf()
            if (content.isEmpty()) return
            if (text.isNotEmpty()) text.append("\n\n")
            val start = text.length
            text.append(content)
            blocks.add(TextBlock(kind, start, text.length, blockLine))
        }
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed == "----" || trimmed == "....") {
                flush(if (inCode) "code" else "paragraph")
                inCode = !inCode
                blockLine = index + 2
            } else if (inCode) {
                if (pending.isEmpty()) blockLine = index + 1
                pending.add(line)
            } else if (trimmed.matches(Regex("^={1,6} .+"))) {
                flush("paragraph")
                val title = trimmed.replaceFirst(Regex("^=+ "), "")
                val position = text.length + if (text.isEmpty()) 0 else 2
                headings.add(title to position)
                blockLine = index + 1
                pending.add(title)
                flush("heading")
            } else if (trimmed.isEmpty()) {
                flush("paragraph")
            } else if (trimmed.startsWith("include::")) {
                throw IllegalArgumentException("Файл секции содержит include; нельзя молча терять включённый текст: $source")
            } else if (trimmed.startsWith("//") || trimmed.startsWith("image:") || trimmed.startsWith("[[") ||
                trimmed.matches(Regex("^\\[.+]$")) || trimmed.matches(Regex("^:[\\w-]+:.*"))) {
                // Атрибуты и изображения не считаются текстовыми доказательствами; команды никогда не выполняются.
            } else {
                if (pending.isEmpty()) blockLine = index + 1
                pending.add(line.replace(Regex("xref:([^\\[]+)\\[([^]]+)]"), "$2").replace(Regex("<<[^,>]+,([^>]+)>>"), "$1"))
            }
        }
        flush(if (inCode) "code" else "paragraph")
        val value = text.toString()
        val title = headings.firstOrNull()?.first ?: source.substringAfterLast('/').removeSuffix(".asc")
        val sections = if (headings.isEmpty()) listOf(DocumentSection(title, 0, value.length)) else (if (headings.first().second > 0) listOf(DocumentSection("Введение", 0, headings.first().second)) else emptyList()) + headings.mapIndexed { index, h ->
            DocumentSection(h.first, h.second, headings.getOrNull(index + 1)?.second ?: value.length)
        }
        return Document(sha256(source).take(20), source, sourceUrl, title, chapter, value, sha256(value), sections, blocks)
    }
}
