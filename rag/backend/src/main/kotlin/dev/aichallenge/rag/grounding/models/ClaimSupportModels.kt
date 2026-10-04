package dev.aichallenge.rag.grounding.models

import dev.aichallenge.rag.grounding.enums.ClaimSupportVerdict
import dev.aichallenge.rag.grounding.enums.SupportCheckStatus

/** Объяснение проверяющей модели остаётся диагностикой, не дополнительным утверждением публичного ответа. */
data class ClaimSupportAssessment(val claimIndex: Int, val verdict: ClaimSupportVerdict, val reason: String)

/** Сохраняет расход платной стадии и исходный ответ даже при отклонении JSON или вердиктов. */
data class ClaimSupportCheck(val status: SupportCheckStatus, val claims: List<ClaimSupportAssessment>, val issues: List<EvidenceIssue>, val generation: GroundingGeneration)
