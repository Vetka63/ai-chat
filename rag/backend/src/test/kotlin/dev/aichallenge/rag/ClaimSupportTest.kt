package dev.aichallenge.rag

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.answering.services.CostEstimator
import dev.aichallenge.rag.grounding.adapters.ExactCitationValidator
import dev.aichallenge.rag.grounding.adapters.LlmClaimSupportValidator
import dev.aichallenge.rag.grounding.enums.ClaimSupportVerdict
import dev.aichallenge.rag.grounding.enums.GroundedStatus
import dev.aichallenge.rag.grounding.enums.SupportCheckStatus
import dev.aichallenge.rag.grounding.models.GroundedClaim
import dev.aichallenge.rag.grounding.services.ClaimSupportPromptAssembler
import dev.aichallenge.rag.indexing.models.Chunk
import dev.aichallenge.rag.retrieval.models.SearchHit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/** Примеры найденных ошибок проверяют протокол; подставленные вердикты не оценивают качество настоящей модели. */
class ClaimSupportTest {
    private val mapper = jacksonObjectMapper()
    private val prompt = ClaimSupportPromptAssembler(mapper)
    private val usage = TokenUsage(20, 10, 30, 0, 20)

    private fun hit(id: String, text: String, section: String = "Книжный пример") = SearchHit(1, .8, Chunk(id, "doc", "book.asc", "Git", section, listOf(section), 0, 0, text.length, 1, 2, text, "sha"))
    private fun claim(text: String, quote: String, hit: SearchHit): GroundedClaim {
        val json = mapper.writeValueAsString(mapOf("status" to "known", "claims" to listOf(mapOf("text" to text, "citations" to listOf(mapOf("chunk_id" to hit.chunk.chunkId, "quote" to quote))))))
        val exact = ExactCitationValidator(mapper).validate(json, "stop", listOf(hit))
        assertEquals(GroundedStatus.ANSWERED, exact.status)
        return exact.claims.single()
    }
    private fun response(vararg verdicts: String) = mapper.writeValueAsString(mapOf("claims" to verdicts.mapIndexed { index, verdict -> mapOf("claim_index" to index, "verdict" to verdict, "reason" to "Проверены смысл и границы цитаты.") }))

    private inner class Fixture(val raw: String, val finish: String = "stop") {
        var calls = 0
        var sent = emptyList<LlmMessage>()
        val llm = object : LlmClient {
            override fun settings() = AnswerSettings(true, "deepseek-flash", 0.0, "disabled", 16000, null, "")
            override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion {
                calls++; sent = messages; assertEquals(2048, maxOutputTokens)
                return LlmCompletion("check", "deepseek-flash", raw, finish, 5, usage)
            }
        }
        val validator = LlmClaimSupportValidator(llm, prompt, mapper, CostEstimator())
    }

    @Test fun `audit regressions keep exact quotes but reject unsupported or contradictory claims`() {
        val cases = listOf(
            Triple("Подходит --soft: он останавливается до изменения индекса.", "Если вы указали опцию `--mixed`, выполнение `reset` остановится на этом шаге.", "unsupported"),
            Triple("git add удаляет файлы из рабочего каталога.", "Git индексирует файл в состоянии, в котором он находился на момент git add.", "contradicted"),
            Triple("Конечный результат merge и rebase всегда одинаков.", "Нет абсолютно никакой разницы в конечном результате между двумя показанными примерами.", "unsupported"),
            Triple("Если текущий коммит не прямой родитель сливаемого, всегда создаётся коммит слияния.", "В этом случае Git выполняет простое трёхстороннее слияние.", "unsupported"),
            Triple("Все коммиты разрешено переписывать через rebase.", "Можно использовать перебазирование для наведения порядка в истории ваших локальных изменений.", "unsupported"),
        )
        for ((text, quote, verdict) in cases) {
            val evidence = hit("c1", "Обсуждается отдельный пример.\n$quote")
            val claim = claim(text, quote, evidence)
            val f = Fixture(response(verdict))
            val checked = f.validator.validate(listOf(claim), listOf(evidence))
            assertEquals(SupportCheckStatus.REJECTED, checked.status, text)
            assertEquals(0, checked.issues.single().claimIndex)
            assertEquals(1, f.calls); assertEquals(30L, checked.generation.usage!!.totalTokens)
            val payload = mapper.readTree(f.sent[1].content)
            assertEquals(text, payload.path("claims")[0].path("text").asText())
            assertEquals(quote, payload.path("claims")[0].path("citations")[0].path("quote").asText())
        }
    }

    @Test fun `checker sees only cited source context without uncited chunks or dialogue knowledge`() {
        val quote = "Если указана опция --mixed, выполнение остановится после обновления индекса."
        val cited = hit("mixed", "Условия этого примера важны.\n$quote", "Шаг 2")
        val omittedProof = hit("soft", "Опция --soft сохраняет индекс и рабочий каталог без изменений.")
        val f = Fixture(response("unsupported"))
        f.validator.validate(listOf(claim("Режим --soft сохраняет индекс.", quote, cited)), listOf(cited, omittedProof))
        val payload = mapper.readTree(f.sent[1].content)
        assertEquals(2, payload.size())
        assertTrue(payload.has("claims")); assertTrue(payload.has("cited_context"))
        assertEquals(1, payload.path("cited_context").size())
        assertEquals("mixed", payload.path("cited_context")[0].path("chunk_id").asText())
        assertEquals(cited.chunk.text, payload.path("cited_context")[0].path("surrounding_context").asText())
        assertFalse(f.sent[1].content.contains(omittedProof.chunk.text))
    }

    @Test fun `valid paraphrase can pass and one rejection prevents aggregate approval`() {
        val quote = "Git индексирует файл в том состоянии, в котором он находился на момент git add."
        val evidence = hit("c", quote)
        val claims = listOf(claim("В индексе сохраняется версия на момент git add.", quote, evidence), claim("git add удаляет файл.", quote, evidence))
        val checked = Fixture(response("supported", "contradicted")).validator.validate(claims, listOf(evidence))
        assertEquals(SupportCheckStatus.REJECTED, checked.status)
        assertEquals(1, checked.issues.single().claimIndex)
        assertEquals(ClaimSupportVerdict.SUPPORTED, checked.claims.first().verdict)
        assertEquals(SupportCheckStatus.PASSED, Fixture(response("supported")).validator.validate(claims.take(1), listOf(evidence)).status)
    }

    @Test fun `malformed missing duplicate out of range fractional and unknown verdict responses fail closed`() {
        val quote = "Git сохраняет проиндексированную версию файла."
        val evidence = hit("c", quote)
        val claims = listOf(claim("Версия сохраняется в индексе.", quote, evidence), claim("Индекс хранит подготовленную версию.", quote, evidence))
        val valid = response("supported", "supported")
        val bad = listOf(
            "not json", "null", "[]", "{}", "{\"claims\":[]}", response("supported"),
            valid.replace("\"claim_index\":1", "\"claim_index\":0"),
            valid.replace("\"claim_index\":1", "\"claim_index\":2"),
            valid.replace("\"claim_index\":1", "\"claim_index\":-1"),
            valid.replace("\"claim_index\":1", "\"claim_index\":1.0"),
            valid.replace("\"claim_index\":1", "\"claim_index\":\"1\""),
            valid.replace("\"claim_index\":1", "\"claim_index\":2147483648"),
            valid.replace("supported", "maybe"),
            valid.replace("\"verdict\":\"supported\"", "\"verdict\":true"),
            valid.replace("\"verdict\":\"supported\"", "\"verdict\":\"unsupported\",\"verdict\":\"supported\""),
            valid.replace("Проверены смысл и границы цитаты.", " "),
            valid.replace("Проверены смысл и границы цитаты.", "x".repeat(301)),
            valid.replace("\"reason\":", "\"extra\":true,\"reason\":"),
            valid.dropLast(1) + ",\"approved\":true}", valid + " {}",
        )
        for (raw in bad) {
            val f = Fixture(raw)
            val checked = f.validator.validate(claims, listOf(evidence))
            assertEquals(SupportCheckStatus.INVALID_RESPONSE, checked.status, raw)
            assertTrue(checked.claims.isEmpty()); assertFalse(checked.issues.isEmpty())
            assertEquals(1, f.calls); assertEquals(30L, checked.generation.usage!!.totalTokens)
        }
    }

    @Test fun `each verdict is required but order is immaterial and truncated output never approves`() {
        val quote = "Git сохраняет проиндексированную версию файла."
        val evidence = hit("c", quote)
        val claims = listOf(claim("Версия сохраняется в индексе.", quote, evidence), claim("Индекс хранит версию.", quote, evidence))
        val reversed = """{"claims":[{"claim_index":1,"verdict":"supported","reason":"Второй подтверждён."},{"claim_index":0,"verdict":"supported","reason":"Первый подтверждён."}]}"""
        val result = Fixture(reversed).validator.validate(claims, listOf(evidence))
        assertEquals(SupportCheckStatus.PASSED, result.status); assertEquals(listOf(0, 1), result.claims.map { it.claimIndex })
        val truncated = Fixture(reversed, "length").validator.validate(claims, listOf(evidence))
        assertEquals(SupportCheckStatus.INVALID_RESPONSE, truncated.status); assertEquals("truncated_support_check", truncated.issues.single().code)
    }
}
