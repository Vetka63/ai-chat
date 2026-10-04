package dev.aichallenge.rag.retrieval.models

import dev.aichallenge.rag.indexing.models.Chunk
import jakarta.validation.constraints.*

/** Один поисковый вопрос; topK — число показанных фрагментов, не порог достоверности. */
data class SearchRequest(@field:NotBlank @field:Size(max = 2000) val query: String, @field:Min(1) @field:Max(20) val topK: Int = 5)

/** Найденный фрагмент с точным cosine и фактической позицией в выдаче. */
data class SearchHit(val rank: Int, val similarity: Double, val chunk: Chunk)

/** Диагностический поиск не содержит генеративного ответа или обещания, что similarity — вероятность. */
data class SearchResult(val indexId: String, val query: String, val milliseconds: Long, val inputTokens: Long?, val hits: List<SearchHit>)
