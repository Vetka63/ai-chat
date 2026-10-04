package dev.aichallenge.rag.grounding.ports

import dev.aichallenge.rag.grounding.models.EvidenceValidation
import dev.aichallenge.rag.retrieval.models.SearchHit

/** Проверяет форму и принадлежность цитат реальному контексту; не является semantic judge. */
interface CitationValidator {
    fun validate(content: String, finishReason: String, included: List<SearchHit>): EvidenceValidation
}
