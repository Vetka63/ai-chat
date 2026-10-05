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

/** Отдельная цепочка проверок на пункт; до трёх одновременно на backend, без смешивания доказательств. */
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
        val generations = checks.flatMap { it.generations() }
        val assessments = checks.flatMapIndexed { index, checked -> checked.claims.map { it.copy(claimIndex = index) } }
        val issues = checks.flatMapIndexed { index, checked -> checked.issues.map { it.copy(claimIndex = index) } }
        val status = when {
            checks.any { it.status == SupportCheckStatus.INVALID_RESPONSE } -> SupportCheckStatus.INVALID_RESPONSE
            checks.any { it.status == SupportCheckStatus.REJECTED } -> SupportCheckStatus.REJECTED
            else -> SupportCheckStatus.PASSED
        }
        return ClaimSupportCheck(status, assessments.sortedBy { it.claimIndex }, issues, generations.first(), generations.drop(1))
    }

    /** Дополнительный guard ограничен тем же пулом; сбой не превращается в допуск. */
    private fun checkScope(claims: List<GroundedClaim>, included: List<SearchHit>, question: String?, primary: ClaimSupportCheck): ClaimSupportCheck {
        val started = System.nanoTime()
        return try { delegate.validateScope(claims, included, question, primary.claims.sortedBy { it.claimIndex }.map { it.claimScope }) } catch (_: Exception) {
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
        val primary = try { delegate.validateScoped(listOf(claim), ownContext, question) } catch (_: Exception) {
            val trace = GroundingGeneration("unavailable", "error", (System.nanoTime() - started) / 1_000_000, null, null, prompt.assemble(listOf(claim), ownContext, question), "")
            ClaimSupportCheck(SupportCheckStatus.INVALID_RESPONSE, emptyList(), listOf(EvidenceIssue("support_check_failed", "Вызов проверки не завершён. Модель и расход неизвестны; повтор не выполнялся.")), trace)
        }
        if (primary.status != SupportCheckStatus.PASSED) return primary
        // Выполняем в том же worker: вложенная очередь в этот пул могла бы создать deadlock.
        // Другие пункты заканчивают собственные проверки даже при отказе этого пункта.
        val scope = checkScope(listOf(claim), ownContext, question, primary)
        return ClaimSupportCheck(scope.status, if (scope.status == SupportCheckStatus.INVALID_RESPONSE) primary.claims else scope.claims,
            primary.issues + scope.issues, primary.generation, primary.additionalGenerations + scope.generations())
    }

    @PreDestroy fun close() { executor.shutdown() }
}
