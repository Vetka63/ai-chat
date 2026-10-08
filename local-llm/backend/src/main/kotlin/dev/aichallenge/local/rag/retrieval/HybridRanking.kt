package dev.aichallenge.local.rag.retrieval

import dev.aichallenge.local.rag.models.Chunk
import org.springframework.stereotype.Component
import kotlin.math.ln

/** Локальный BM25 для редких имён/деталей. RRF объединяет порядки, не выдаёт новый score за cosine. */
@Component
class HybridRanking {
    private val stop = setOf("что", "как", "какой", "какая", "какие", "кто", "это", "его", "её", "в", "на", "и", "по", "из", "с", "за", "для", "был", "была", "были", "ли", "рассказ", "рассказе", "сколько", "почему", "своей", "свою", "каким", "какому", "текст", "тексте")
    private fun terms(text: String) = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase().replace('ё','е')).map { it.value }
        .filter { it.length > 2 && it !in stop }.map { word -> if (word.length > 3) word.replace(Regex("(иями|ями|ами|ого|ему|ому|ыми|ими|ых|их|ые|ие|ий|ый|ой|ая|яя|ое|ее|ов|ев|ам|ям|ах|ях|ом|ем|ы|и|а|я|у|ю|е|о|ь)$"), "") else word }.toList()
    /** Сопоставляет название с учётом простых окончаний, например «Молниеносном рейсе». */
    fun mentionsTitle(question: String, title: String): Boolean {
        val titleTerms = terms(title)
        if (titleTerms.isEmpty()) return false
        val questionTerms = terms(question)
        return questionTerms.windowed(titleTerms.size).any { it == titleTerms }
    }
    fun rank(question: String, dense: List<Pair<Chunk, Double>>): List<RankedChunk> {
        if (dense.isEmpty()) return emptyList()
        val query = terms(question).distinct()
        val documents = dense.associate { it.first.id to terms("${it.first.title} ${it.first.text}") }
        val average = documents.values.map { it.size }.average().coerceAtLeast(1.0)
        val counts = documents.mapValues { it.value.groupingBy { word -> word }.eachCount() }
        val frequencies = query.associateWith { term -> counts.values.count { (it[term] ?: 0) > 0 } }
        val lexical = dense.map { (chunk, _) ->
            val size = documents.getValue(chunk.id).size
            val score = query.sumOf { term ->
                val tf = (counts.getValue(chunk.id)[term] ?: 0).toDouble()
                val df = frequencies.getValue(term)
                val idf = ln(1.0 + (dense.size - df + 0.5) / (df + 0.5))
                idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * size / average))
            }
            chunk.id to score
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        val lexicalRanks = lexical.mapIndexed { i, pair -> pair.first to i + 1 }.toMap()
        return dense.mapIndexed { i, pair ->
            val lexicalRank = lexicalRanks[pair.first.id]
            RankedChunk(pair.first, pair.second, i + 1, lexicalRank, 1.0 / (60 + i + 1) + (lexicalRank?.let { 2.0 / (60 + it) } ?: 0.0))
        }.sortedWith(compareByDescending<RankedChunk> { it.fusionScore }.thenBy { it.denseRank })
    }
}
data class RankedChunk(val chunk: Chunk, val similarity: Double, val denseRank: Int, val lexicalRank: Int?, val fusionScore: Double)
