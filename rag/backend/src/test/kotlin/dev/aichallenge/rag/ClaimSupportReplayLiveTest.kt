package dev.aichallenge.rag

import dev.aichallenge.rag.answering.adapters.DeepSeekLlmClient
import dev.aichallenge.rag.answering.services.CostEstimator
import dev.aichallenge.rag.config.DeepSeekProperties
import dev.aichallenge.rag.grounding.adapters.*
import dev.aichallenge.rag.grounding.enums.ClaimSupportVerdict
import dev.aichallenge.rag.grounding.models.GroundedClaim
import dev.aichallenge.rag.grounding.services.ClaimSupportPromptAssembler
import dev.aichallenge.rag.retrieval.models.SearchHit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Реальный многопунктный false-positive сохранён как fixture, без подмены и повторной генерации. */
@EnabledIfEnvironmentVariable(named = "RAG_RUN_LIVE_REPLAY", matches = "true")
class ClaimSupportReplayLiveTest {
    @Test fun `generated multi claim scope regression stays rejected in isolated checks`() {
        val mapper = jacksonObjectMapper()
        val key = System.getenv("DEEPSEEK_API_KEY").orEmpty()
        require(key.isNotBlank())
        val properties = DeepSeekProperties(apiKey = key, baseUrl = requireNotNull(System.getenv("DEEPSEEK_BASE_URL")), supportThinkingEnabled = System.getenv("RAG_SUPPORT_TEST_THINKING")?.toBooleanStrict() ?: true)
        val prompt = ClaimSupportPromptAssembler(mapper)
        val checker = IsolatedClaimSupportValidator(LlmClaimSupportValidator(DeepSeekLlmClient(properties, mapper), prompt, mapper, CostEstimator()), prompt)
        val root = javaClass.getResourceAsStream("/grounding/rebase-batch-regression.json").use { mapper.readTree(it) }
        val claims = root.path("claims").toList().map { mapper.treeToValue(it, GroundedClaim::class.java) }
        val included = root.path("included").toList().map { mapper.treeToValue(it, SearchHit::class.java) }
        val repeats = System.getenv("RAG_SUPPORT_TEST_REPEATS")?.toInt() ?: 1
        require(repeats in 1..3)
        val path = Path.of("../data/day24-replay-live-${Instant.now().toString().replace(':', '-')}-${UUID.randomUUID().toString().take(8)}.json")
        val records = mutableListOf<Any>()
        val failures = mutableListOf<String>()
        for (round in 1..repeats) {
            val result = checker.validate(claims, included)
            records.add(mapOf("round" to round, "supportCheck" to result))
            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("thinkingEnabled" to properties.supportThinkingEnabled, "scopeThinkingEnabled" to properties.scopeThinkingEnabled, "maximumCalls" to claims.size * 3 * repeats, "records" to records)))
            for (index in listOf(0, 2)) if (result.claims.firstOrNull { it.claimIndex == index }?.verdict != ClaimSupportVerdict.UNSUPPORTED) failures.add("round=$round index=$index")
            println("Replay round=$round ${result.status}, calls=${result.generations().size}; trace=$path")
        }
        assertTrue(failures.isEmpty(), "Неправильный допуск scope: $failures; trace=$path")
    }
}
