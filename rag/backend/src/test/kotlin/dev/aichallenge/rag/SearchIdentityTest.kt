package dev.aichallenge.rag

import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.embeddings.ports.*
import dev.aichallenge.rag.indexing.models.*
import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.retrieval.models.SearchRequest
import dev.aichallenge.rag.retrieval.services.SearchService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*

/** Поиск не сравнивает векторы, если tag изменился между identity и embedding вопроса. */
class SearchIdentityTest {
    private val identity = EmbeddingIdentity("test", "a", 2)
    private fun repository(): IndexRepository {
        val repository = mock(IndexRepository::class.java)
        val metrics = ChunkMetrics(1, 1, 1, 1, 1, 1, 1, 100.0, 0, 0)
        `when`(repository.index("index")).thenReturn(IndexInfo("index", "snapshot", "now", ChunkConfig(), identity, metrics, 0, 0, 1, 8))
        `when`(repository.vectors("index")).thenReturn(emptyList())
        return repository
    }
    @Test fun `same dimension different digest after embedding fails before vector search`() {
        val repository = repository()
        val provider = object : EmbeddingProvider {
            var changed = false
            override fun identity() = if (changed) identity.copy(digest = "b") else identity
            override fun embed(texts: List<String>): EmbeddingBatch { changed = true; return EmbeddingBatch(listOf(floatArrayOf(0f, 1f)), 1, 1) }
        }
        val error = assertThrows(LabException::class.java) { SearchService(repository, provider).search("index", SearchRequest("вопрос", 5)) }
        assertEquals("embedding_space_mismatch", error.code)
        verify(repository, never()).vectors("index")
    }
    @Test fun `stable digest permits retrieval and preserves query usage`() {
        val provider = object : EmbeddingProvider {
            override fun identity() = identity
            override fun embed(texts: List<String>) = EmbeddingBatch(listOf(floatArrayOf(1f, 0f)), 17, 1)
        }
        val result = SearchService(repository(), provider).search("index", SearchRequest("вопрос", 5))
        assertEquals(17L, result.inputTokens)
    }
}
