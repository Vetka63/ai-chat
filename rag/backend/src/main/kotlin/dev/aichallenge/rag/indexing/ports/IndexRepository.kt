package dev.aichallenge.rag.indexing.ports

import dev.aichallenge.rag.documents.models.*
import dev.aichallenge.rag.indexing.models.*

/** Граница persistence: сервисы знают операции над индексами, но не JDBC и устройство SQLite. */
interface IndexRepository {
    fun saveJob(job: IndexJob)
    fun jobs(): List<IndexJob>
    fun job(id: String): IndexJob
    fun indexes(): List<IndexInfo>
    fun index(id: String): IndexInfo
    fun chunks(id: String): List<Chunk>
    fun vectors(id: String): List<Pair<Chunk, FloatArray>>
    fun document(indexId: String, documentId: String): Document
    fun publish(snapshot: CorpusSnapshot, index: IndexInfo, chunks: List<Chunk>, vectors: List<FloatArray>, job: IndexJob)
}
