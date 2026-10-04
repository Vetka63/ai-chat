package dev.aichallenge.rag.indexing.ports

import dev.aichallenge.rag.documents.models.Document
import dev.aichallenge.rag.indexing.enums.ChunkStrategy
import dev.aichallenge.rag.indexing.models.ChunkConfig

/** Возвращает диапазоны в каноническом документе; индексатор не знает деталей конкретной стратегии. */
interface ChunkingStrategy {
    val name: ChunkStrategy
    fun ranges(document: Document, config: ChunkConfig): List<IntRange>
}
