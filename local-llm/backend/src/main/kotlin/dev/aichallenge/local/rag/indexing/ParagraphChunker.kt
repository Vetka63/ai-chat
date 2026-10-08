package dev.aichallenge.local.rag.indexing

import dev.aichallenge.local.rag.models.*
import org.springframework.stereotype.Component

/** Границы абзацев с overlap; длинные абзацы режутся по словам, диапазоны относятся к исходному тексту. */
@Component
class ParagraphChunker {
    fun chunk(document: RagDocument, size: Int, overlap: Int): List<Chunk> {
        require(size in 600..3000 && overlap in 0..500 && overlap < size / 2)
        val text = document.text
        val headings = Regex("(?m)^(ПРОЛОГ|ЭПИЛОГ|ГЛАВА[^\\n]{0,60}|[0-9]{1,3}|[IVXLCDM]{1,8})[ \\t]*$").findAll(text).toList()
        val result = mutableListOf<Chunk>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + size, text.length)
            if (end < text.length) {
                val paragraph = text.lastIndexOf("\n\n", end - 1)
                val space = text.lastIndexOf(' ', end - 1)
                end = when { paragraph > start + size / 2 -> paragraph; space > start + size / 2 -> space; else -> end }
                if (end > start && Character.isHighSurrogate(text[end - 1])) end--
            }
            val ordinal = result.size
            val section = headings.lastOrNull { it.range.first <= start }?.value?.trim() ?: "Начало рассказа"
            result.add(Chunk("${document.id}:$ordinal", document.id, document.filename, document.title, section, ordinal, start, end, text.substring(start, end)))
            if (end == text.length) break
            start = maxOf(start + 1, end - overlap)
            if (Character.isLowSurrogate(text[start])) start++
        }
        return result
    }
}
