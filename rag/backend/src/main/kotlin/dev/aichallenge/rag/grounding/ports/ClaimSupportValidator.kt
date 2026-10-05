package dev.aichallenge.rag.grounding.ports

import dev.aichallenge.rag.grounding.models.ClaimSupportCheck
import dev.aichallenge.rag.grounding.enums.EvidenceScope
import dev.aichallenge.rag.grounding.models.GroundedClaim
import dev.aichallenge.rag.retrieval.models.SearchHit

/** Проверяет смысл после дословности цитат, без истории пользователя, памяти и внешних знаний. */
interface ClaimSupportValidator {
    fun validate(claims: List<GroundedClaim>, included: List<SearchHit>): ClaimSupportCheck
    /** Вопрос уточняет предмет/условия, но никогда не заменяет книжные доказательства. */
    fun validateScoped(claims: List<GroundedClaim>, included: List<SearchHit>, question: String?): ClaimSupportCheck = validate(claims, included)
    /** Независимый контроль области/условий уже поддержанных пунктов; отдельный измеряемый вызов. */
    fun validateScope(claims: List<GroundedClaim>, included: List<SearchHit>, question: String?): ClaimSupportCheck = validateScoped(claims, included, question)
    /** Первичная оценка области сохраняется для проверки согласованности с независимой разметкой источника. */
    fun validateScope(claims: List<GroundedClaim>, included: List<SearchHit>, question: String?, claimScopes: List<EvidenceScope?>): ClaimSupportCheck = validateScope(claims, included, question)
}
