package dev.aichallenge.rag.retrieval.selection.adapters

import dev.aichallenge.rag.retrieval.models.SearchHit
import dev.aichallenge.rag.retrieval.selection.enums.SelectionReason
import dev.aichallenge.rag.retrieval.selection.models.*
import dev.aichallenge.rag.retrieval.selection.ports.CandidateSelector
import org.springframework.stereotype.Component

/** Прозрачный cosine-фильтр, не обученный reranker. Сначала порог, затем ограничение top-K. */
@Component
class SimilarityCandidateSelector : CandidateSelector {
    override fun select(hits: List<SearchHit>, finalTopK: Int, threshold: Double?): CandidateSelection {
        require(finalTopK > 0 && (threshold == null || threshold.isFinite() && threshold in -1.0..1.0))
        var count = 0
        val decisions = hits.map { hit ->
            require(hit.similarity.isFinite())
            val reason = when {
                threshold != null && hit.similarity < threshold -> SelectionReason.BELOW_THRESHOLD
                count >= finalTopK -> SelectionReason.TOP_K_LIMIT
                else -> { count++; SelectionReason.SELECTED }
            }
            CandidateDecision(hit, reason)
        }
        return CandidateSelection(decisions.filter { it.reason == SelectionReason.SELECTED }.map { it.hit }, decisions)
    }
}
