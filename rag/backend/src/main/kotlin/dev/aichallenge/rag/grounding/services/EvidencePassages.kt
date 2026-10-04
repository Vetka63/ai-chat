package dev.aichallenge.rag.grounding.services

/** Серверные адресуемые фрагменты: LLM выбирает номер, текст и координаты берутся из snapshot. */
object EvidencePassages {
    data class Passage(val start: Int, val text: String)

    /** Не нормализует пробелы/переносы. Длинный абзац делится без пропусков и изменения символов. */
    fun split(text: String): List<Passage> {
        val result = mutableListOf<Passage>()
        val boundaries = Regex("\\n[ \\t]*\\n").findAll(text).map { it.range.first }.toList() + text.length
        var start = 0
        for (end in boundaries) {
            var cursor = start
            while (cursor < end) {
                var stop = (cursor + 1200).coerceAtMost(end)
                if (stop < end) {
                    val space = text.lastIndexOf(' ', stop - 1)
                    if (space >= cursor + 600) stop = space + 1
                    if (stop > cursor && text[stop - 1].isHighSurrogate()) stop--
                }
                val part = text.substring(cursor, stop)
                if (part.isNotBlank()) result.add(Passage(cursor, part))
                cursor = stop
            }
            start = end
        }
        return result
    }
}
