package dev.aichallenge.rag.retrieval.selection.ports

import dev.aichallenge.rag.retrieval.models.SearchHit
import dev.aichallenge.rag.retrieval.selection.models.CandidateSelection

/** Порт второго этапа поиска. Null threshold означает контрольный режим без фильтра. */
interface CandidateSelector {
    fun select(hits: List<SearchHit>, finalTopK: Int, threshold: Double?): CandidateSelection
}
