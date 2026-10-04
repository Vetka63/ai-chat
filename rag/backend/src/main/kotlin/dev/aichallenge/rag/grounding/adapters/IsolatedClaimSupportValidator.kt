package dev.aichallenge.rag.grounding.adapters

import dev.aichallenge.rag.grounding.enums.SupportCheckStatus
import dev.aichallenge.rag.grounding.models.*
import dev.aichallenge.rag.grounding.ports.ClaimSupportValidator
import dev.aichallenge.rag.grounding.services.ClaimSupportPromptAssembler
import dev.aichallenge.rag.retrieval.models.SearchHit
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import jakarta.annotation.PreDestroy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Один вызов на пункт; до трёх одновременно на экземпляр backend, без смешивания доказательств. */
@Primary
@Component
class IsolatedClaimSupportValidator(
    @Qualifier("llmClaimSupportValidator") private val delegate: ClaimSupportValidator,
    private val prompt: ClaimSupportPromptAssembler,
    @Value("\${rag.support-parallelism:3}") parallelism: Int = 3,
) : ClaimSupportValidator {
    private val executor = Executors.newFixedThreadPool(parallelism.also { require(it in 1..3) }) { task ->
        Thread(task, "rag-support-check").apply { isDaemon = true }
    }

    /** Все отправленные проверки дожидаются завершения и учитываются, даже если одна уже отклонена. */
    override fun validate(claims: List<GroundedClaim>, included: List<SearchHit>): ClaimSupportCheck {
        require(claims.size in 1..8)
        val futures = claims.map { claim -> CompletableFuture.supplyAsync({ checkOne(claim, included) }, executor) }
        val checks = futures.map { it.join() }
        val generations = checks.flatMap { it.generations() }
        val assessments = checks.flatMapIndexed { index, checked -> checked.claims.map { it.copy(claimIndex = index) } }
        val issues = checks.flatMapIndexed { index, checked -> checked.issues.map { it.copy(claimIndex = index) } }
        val status = when {
            checks.any { it.status == SupportCheckStatus.INVALID_RESPONSE } -> SupportCheckStatus.INVALID_RESPONSE
            checks.any { it.status == SupportCheckStatus.REJECTED } -> SupportCheckStatus.REJECTED
            else -> SupportCheckStatus.PASSED
        }
        return ClaimSupportCheck(status, assessments, issues, generations.first(), generations.drop(1))
    }

    /** Транспортный сбой остаётся неизвестным расходом, а не нулём или фиктивным ответом провайдера. */
    private fun checkOne(claim: GroundedClaim, included: List<SearchHit>): ClaimSupportCheck {
        val ownIds = claim.citations.map { it.source.chunkId }.toSet()
        val ownContext = included.filter { it.chunk.chunkId in ownIds }
        val started = System.nanoTime()
        return try { delegate.validate(listOf(claim), ownContext) } catch (_: Exception) {
            val trace = GroundingGeneration("unavailable", "error", (System.nanoTime() - started) / 1_000_000, null, null, prompt.assemble(listOf(claim), ownContext), "")
            ClaimSupportCheck(SupportCheckStatus.INVALID_RESPONSE, emptyList(), listOf(EvidenceIssue("support_check_failed", "Вызов проверки не завершён. Модель и расход неизвестны; повтор не выполнялся.")), trace)
        }
    }

    @PreDestroy fun close() { executor.shutdown() }
}
