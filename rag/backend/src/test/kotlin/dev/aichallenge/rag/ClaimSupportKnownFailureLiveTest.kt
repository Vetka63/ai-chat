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

/** Исходный провал и положительная пара; через денежный шлюз, максимум шесть Pro-вызовов. */
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
        val baseUrl = requireNotNull(System.getenv("DEEPSEEK_BASE_URL")) { "Live-регрессия требует явно заданный бюджетный шлюз." }
        val checker = IsolatedClaimSupportValidator(LlmClaimSupportValidator(DeepSeekLlmClient(DeepSeekProperties(apiKey = key, baseUrl = baseUrl), mapper), prompt, mapper, CostEstimator()), prompt)
        val path = Path.of("../data/day24-replay-live-${Instant.now().toString().replace(':', '-')}-known-failure.json")
        Files.createDirectories(path.parent)
        val records = mutableListOf<Map<String, Any>>()
        fun save() = Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf("at" to Instant.now().toString(), "maximumCalls" to 6, "complete" to (records.size == 2), "records" to records)))
        save()
        try {
            val result = checker.validateScoped(claims, included, fixture.path("question").asText())
            records += mapOf("fixture" to "merge-missing-divergence", "supportCheck" to result)
            save() // Первая завершённая половина не теряется при остановке второго вызова.
            val chunk = included.single().chunk
            val premise = "Если вы вернётесь к более раннему примеру из <<r_basic_merging>>, вы увидите, что разделили свою работу и сделали коммиты в две разные ветки."
            val start = chunk.text.indexOf(premise).also { require(it >= 0) }
            val premiseCitation = claims.single().citations.single().copy(quote = premise, startInChunk = start, endInChunkExclusive = start + premise.length, canonicalStart = chunk.start + start, canonicalEndExclusive = chunk.start + start + premise.length)
            val positive = checker.validateScoped(listOf(claims.single().copy(text = "В описанном в книге примере работу разделили и сделали коммиты в две разные ветки. " + claims.single().text, citations = claims.single().citations + premiseCitation)), included, fixture.path("question").asText())
            records += mapOf("fixture" to "merge-preserved-divergence", "supportCheck" to positive)
            save()
            assertEquals(ClaimSupportVerdict.UNSUPPORTED, result.claims.singleOrNull()?.verdict, "Ожидание ручного аудита не выполнено; trace=$path")
            assertEquals(ClaimSupportVerdict.SUPPORTED, positive.claims.singleOrNull()?.verdict, "Корректная оговорка не должна отклоняться; trace=$path")
        } finally { checker.close() }
    }
}
