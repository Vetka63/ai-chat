package dev.aichallenge.rag.indexing.services

import dev.aichallenge.rag.common.*
import dev.aichallenge.rag.documents.models.*
import dev.aichallenge.rag.indexing.enums.ChunkStrategy
import dev.aichallenge.rag.indexing.models.*
import dev.aichallenge.rag.indexing.ports.ChunkingStrategy
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.round

/** Фиксированные окна не учитывают заголовки: это контролируемый baseline. */
@Component
class FixedSizeChunking : ChunkingStrategy {
    override val name = ChunkStrategy.FIXED
    override fun ranges(document: Document, config: ChunkConfig): List<IntRange> = windows(0, document.text.length, config)
}

/** Структурные окна не пересекают разделы; внутри выбирают конец целого смыслового блока. */
@Component
class StructuralChunking : ChunkingStrategy {
    override val name = ChunkStrategy.STRUCTURAL
    override fun ranges(document: Document, config: ChunkConfig): List<IntRange> = document.sections.flatMap { section ->
        windows(section.start, section.endExclusive, config) { start, hardEnd ->
            val candidates = document.blocks.filter { it.endExclusive > start + config.maxCharacters / 3 && it.endExclusive <= hardEnd }
            candidates.lastOrNull()?.endExclusive ?: hardEnd
        }
    }
}

/** Общий цикл гарантирует продвижение, заданную верхнюю границу и отсутствие пропущенных диапазонов. */
internal fun windows(start: Int, endExclusive: Int, config: ChunkConfig, boundary: (Int, Int) -> Int = { _, end -> end }): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var cursor = start
    while (cursor < endExclusive) {
        val hardEnd = minOf(cursor + config.maxCharacters, endExclusive)
        val end = if (hardEnd == endExclusive) hardEnd else boundary(cursor, hardEnd)
        require(end > cursor && end <= hardEnd)
        ranges.add(cursor until end)
        if (end == endExclusive) break
        cursor = maxOf(cursor + 1, end - config.overlapCharacters)
    }
    return ranges
}

/** Реестр стратегий, стабильные ID и измерение покрытия централизованы, алгоритмы разделены. */
@Service
class ChunkingService(strategies: List<ChunkingStrategy>) {
    private val registry = strategies.associateBy { it.name }
    fun validate(config: ChunkConfig) {
        if (config.maxCharacters !in 300..10000 || config.overlapCharacters !in 0..3000 || config.overlapCharacters >= config.maxCharacters / 2)
            throw LabException("invalid_chunk_config", "Размер: 300–10000 символов. Перекрытие должно быть меньше половины размера и не больше 3000.")
    }
    fun preview(snapshot: CorpusSnapshot, config: ChunkConfig): ChunkPreview {
        validate(config)
        val chunks = snapshot.documents.flatMap { doc ->
            registry.getValue(config.strategy).ranges(doc, config).mapIndexed { ordinal, range ->
                val sectionNames = doc.sections.filter { it.start < range.last + 1 && it.endExclusive > range.first }.map { it.title }
                val blockRange = doc.blocks.filter { it.start < range.last + 1 && it.endExclusive > range.first }
                val value = doc.text.substring(range.first, range.last + 1)
                val id = sha256("${snapshot.snapshotId}|${config.strategy}|${config.maxCharacters}|${config.overlapCharacters}|${doc.id}|${range.first}|${range.last + 1}").take(24)
                Chunk(id, doc.id, doc.source, doc.title, sectionNames.joinToString(" / "), sectionNames, ordinal, range.first, range.last + 1, blockRange.firstOrNull()?.sourceLine ?: 1, blockRange.lastOrNull()?.sourceLine ?: 1, value, sha256(value))
            }
        }
        return ChunkPreview(snapshot.snapshotId, config, measure(snapshot, chunks), chunks)
    }
    private fun measure(snapshot: CorpusSnapshot, chunks: List<Chunk>): ChunkMetrics {
        val sizes = chunks.map { it.text.length }.sorted()
        val covered = snapshot.documents.sumOf { doc ->
            val bits = BitSet(doc.text.length)
            chunks.filter { it.documentId == doc.id }.forEach { bits.set(it.start, it.endExclusive) }
            bits.cardinality()
        }
        val unique = snapshot.documents.sumOf { it.text.length }
        val splitCode = snapshot.documents.sumOf { doc ->
            val ends = chunks.filter { it.documentId == doc.id }.flatMap { listOf(it.start, it.endExclusive) }
            doc.blocks.count { block -> block.kind == "code" && ends.any { it > block.start && it < block.endExclusive } }
        }
        return ChunkMetrics(chunks.size, sizes.firstOrNull() ?: 0, sizes.getOrNull(sizes.size / 2) ?: 0, sizes.getOrNull((ceil(sizes.size * .95).toInt() - 1).coerceAtLeast(0)) ?: 0, sizes.lastOrNull() ?: 0, unique, sizes.sumOf { it.toLong() }, if (unique == 0) 0.0 else round(covered * 10000.0 / unique) / 100, chunks.count { it.sections.size > 1 }, splitCode)
    }
}
