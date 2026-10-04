package dev.aichallenge.rag.grounding.ports

import dev.aichallenge.rag.grounding.models.ClaimSupportCheck
import dev.aichallenge.rag.grounding.models.GroundedClaim
import dev.aichallenge.rag.retrieval.models.SearchHit

/** Проверяет смысл после дословности цитат, без истории пользователя, памяти и внешних знаний. */
interface ClaimSupportValidator {
    fun validate(claims: List<GroundedClaim>, included: List<SearchHit>): ClaimSupportCheck
}
