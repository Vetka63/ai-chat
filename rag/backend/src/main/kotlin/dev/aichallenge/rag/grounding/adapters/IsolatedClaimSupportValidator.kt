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
        return validateScoped(claims, included, null)
    }
    override fun validateScoped(claims: List<GroundedClaim>, included: List<SearchHit>, question: String?): ClaimSupportCheck {
        require(claims.size in 1..8)
        val futures = claims.map { claim -> CompletableFuture.supplyAsync({ checkOne(claim, included, question) }, executor) }
        val checks = futures.map { it.join() }
        val generations = checks.flatMap { it.generations() }.toMutableList()
        val assessments = checks.flatMapIndexed { index, checked -> checked.claims.map { it.copy(claimIndex = index) } }.toMutableList()
        val issues = checks.flatMapIndexed { index, checked -> checked.issues.map { it.copy(claimIndex = index) } }.toMutableList()
        // Уже отклонённый черновик отправляется на единственное исправление. Дополнительный
        // guard не нужен до исправления и не должен превращать смысловой отказ в сбой контракта.
        val approved = checks.indices.filter { checks[it].status == SupportCheckStatus.PASSED }
        val scope = if (checks.all { it.status == SupportCheckStatus.PASSED }) {
            val selected = approved.map { claims[it] }
            val ownIds = selected.flatMap { it.citations }.map { it.source.chunkId }.toSet()
            CompletableFuture.supplyAsync({ checkScope(selected, included.filter { it.chunk.chunkId in ownIds }, question) }, executor).join()
        } else null
        if (scope != null) {
            generations += scope.generations()
            issues += scope.issues.map { it.copy(claimIndex = it.claimIndex?.let { index -> approved[index] }) }
            if (scope.status != SupportCheckStatus.INVALID_RESPONSE) {
                assessments.removeAll { it.claimIndex in approved }
                assessments += scope.claims.map { it.copy(claimIndex = approved[it.claimIndex]) }
            }
        }
        val status = when {
            checks.any { it.status == SupportCheckStatus.INVALID_RESPONSE } || scope?.status == SupportCheckStatus.INVALID_RESPONSE -> SupportCheckStatus.INVALID_RESPONSE
            checks.any { it.status == SupportCheckStatus.REJECTED } || scope?.status == SupportCheckStatus.REJECTED -> SupportCheckStatus.REJECTED
            else -> SupportCheckStatus.PASSED
        }
        return ClaimSupportCheck(status, assessments.sortedBy { it.claimIndex }, issues, generations.first(), generations.drop(1))
    }

    /** Дополнительный guard ограничен тем же пулом; сбой не превращается в допуск. */
    private fun checkScope(claims: List<GroundedClaim>, included: List<SearchHit>, question: String?): ClaimSupportCheck {
        val started = System.nanoTime()
        return try { delegate.validateScope(claims, included, question) } catch (_: Exception) {
            val messages = prompt.assemble(claims, included, question)
            val trace = GroundingGeneration("unavailable", "error", (System.nanoTime() - started) / 1_000_000, null, null, messages, "")
            ClaimSupportCheck(SupportCheckStatus.INVALID_RESPONSE, emptyList(), listOf(EvidenceIssue("scope_check_failed", "Проверка условий источника не завершена. Ответ не опубликован; повтор не выполнялся.")), trace)
        }
    }

    /** Транспортный сбой остаётся неизвестным расходом, а не нулём или фиктивным ответом провайдера. */
    private fun checkOne(claim: GroundedClaim, included: List<SearchHit>, question: String?): ClaimSupportCheck {
        val ownIds = claim.citations.map { it.source.chunkId }.toSet()
        val ownContext = included.filter { it.chunk.chunkId in ownIds }
        val started = System.nanoTime()
        return try { delegate.validateScoped(listOf(claim), ownContext, question) } catch (_: Exception) {
            val trace = GroundingGeneration("unavailable", "error", (System.nanoTime() - started) / 1_000_000, null, null, prompt.assemble(listOf(claim), ownContext, question), "")
            ClaimSupportCheck(SupportCheckStatus.INVALID_RESPONSE, emptyList(), listOf(EvidenceIssue("support_check_failed", "Вызов проверки не завершён. Модель и расход неизвестны; повтор не выполнялся.")), trace)
        }
    }

    @PreDestroy fun close() { executor.shutdown() }
}
