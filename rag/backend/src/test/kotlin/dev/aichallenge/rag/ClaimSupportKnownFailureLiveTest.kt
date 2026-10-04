package dev.aichallenge.rag

import dev.aichallenge.rag.answering.adapters.DeepSeekLlmClient
import dev.aichallenge.rag.answering.services.CostEstimator
import dev.aichallenge.rag.config.DeepSeekProperties
import dev.aichallenge.rag.grounding.adapters.IsolatedClaimSupportValidator
import dev.aichallenge.rag.grounding.adapters.LlmClaimSupportValidator
import dev.aichallenge.rag.grounding.enums.ClaimSupportVerdict
import dev.aichallenge.rag.grounding.models.GroundedClaim
import dev.aichallenge.rag.grounding.services.ClaimSupportPromptAssembler
import dev.aichallenge.rag.retrieval.models.SearchHit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Непройденная семантическая регрессия из ручного аудита. Отдельное разрешение: не более двух Pro-вызовов. */
@EnabledIfEnvironmentVariable(named = "RAG_RUN_LIVE_KNOWN_FAILURE", matches = "true")
class ClaimSupportKnownFailureLiveTest {
    @Test fun `actual generated merge description must preserve divergent history premise`() {
        val mapper = jacksonObjectMapper()
        val fixture = javaClass.getResourceAsStream("/grounding/merge-missing-divergence-regression.json").use { mapper.readTree(it) }
        val claims = fixture.path("claims").toList().map { mapper.treeToValue(it, GroundedClaim::class.java) }
        val included = fixture.path("included").toList().map { mapper.treeToValue(it, SearchHit::class.java) }
        val key = System.getenv("DEEPSEEK_API_KEY").orEmpty()
        require(key.isNotBlank())
        val prompt = ClaimSupportPromptAssembler(mapper)
        val checker = IsolatedClaimSupportValidator(LlmClaimSupportValidator(DeepSeekLlmClient(DeepSeekProperties(apiKey = key), mapper), prompt, mapper, CostEstimator()), prompt)
        try {
            val result = checker.validateScoped(claims, included, fixture.path("question").asText())
            val path = Path.of("../data/day24-replay-live-${Instant.now().toString().replace(':', '-')}-known-failure.json")
            Files.createDirectories(path.parent)
            Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("at" to Instant.now().toString(), "maximumCalls" to 2, "records" to listOf(mapOf("fixture" to "merge-missing-divergence", "supportCheck" to result)))))
            assertEquals(ClaimSupportVerdict.UNSUPPORTED, result.claims.singleOrNull()?.verdict, "Ожидание ручного аудита не выполнено; trace=$path")
        } finally { checker.close() }
    }
}
