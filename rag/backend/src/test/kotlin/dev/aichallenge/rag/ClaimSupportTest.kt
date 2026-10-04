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

    /** Доказывает только целостность fixture: точная цитата не является доказательством правильного обобщения. */
    @Test fun `latest semantic failure keeps exact quotes and original premise for future review`() {
        val root = javaClass.getResourceAsStream("/grounding/merge-missing-divergence-regression.json").use { mapper.readTree(it) }
        val claims = root.path("claims").toList().map { mapper.treeToValue(it, GroundedClaim::class.java) }
        val included = root.path("included").toList().map { mapper.treeToValue(it, SearchHit::class.java) }
        assertEquals(1, claims.size)
        for (citation in claims.single().citations) {
            val chunk = included.single { it.chunk.chunkId == citation.source.chunkId }.chunk
            assertTrue(chunk.text.contains(citation.quote))
            assertTrue(chunk.text.contains("сделали коммиты в две разные ветки"))
        }
        val sent = mapper.readTree(prompt.assemble(claims, included, root.path("question").asText()).last().content)
        assertEquals(root.path("question").asText(), sent.path("question").asText())
        assertEquals(claims.single().text, sent.path("items")[0].path("statement").asText())
    }

    private fun hit(id: String, text: String, section: String = "Книжный пример") = SearchHit(1, .8, Chunk(id, "doc", "book.asc", "Git", section, listOf(section), 0, 0, text.length, 1, 2, text, "sha"))
    private fun claim(text: String, quote: String, hit: SearchHit): GroundedClaim {
        val json = mapper.writeValueAsString(mapOf("status" to "known", "claims" to listOf(mapOf("text" to text, "citations" to listOf(mapOf("chunk_id" to hit.chunk.chunkId, "quote" to quote))))))
        val exact = ExactCitationValidator(mapper).validate(json, "stop", listOf(hit))
        assertEquals(GroundedStatus.ANSWERED, exact.status)
        return exact.claims.single()
    }
    private fun response(vararg verdicts: String) = mapper.writeValueAsString(mapOf("claims" to verdicts.mapIndexed { index, verdict -> mapOf("claim_index" to index, "verdict" to verdict, "reason" to "Проверены смысл и границы цитаты.", "conditions" to emptyList<Any>(), "evidence_scope" to "general", "claim_scope" to "general") }))

    @Test fun `scope condition cannot be silently imported from source into the answer`() {
        val quote = "При двух исправных датчиках получены два показания."
        val evidence = hit("c", quote)
        val original = claim("Получены два показания.", quote, evidence)
        fun checked(anchor: String, preserved: Boolean, text: String = original.text, question: String? = null): SupportCheckStatus {
            val condition = mapOf("id" to "R0", "applicable" to true, "preserved" to preserved, "anchor" to anchor, "reason" to "Проверка условия")
            val raw = mapper.writeValueAsString(mapOf("checks" to listOf(condition)))
            return Fixture(raw).validator.validateScope(listOf(original.copy(text = text)), listOf(evidence), question).status
        }
        assertEquals(SupportCheckStatus.INVALID_RESPONSE, checked("двух исправных датчиках", true))
        assertEquals(SupportCheckStatus.INVALID_RESPONSE, checked("", true))
        assertEquals(SupportCheckStatus.REJECTED, checked("", false))
        assertEquals(SupportCheckStatus.PASSED, checked("двух исправных датчиках", true, quote))
        assertEquals(SupportCheckStatus.PASSED, checked("двух исправных датчиках", true, question = "Что происходит при двух исправных датчиках?"))
    }

    private inner class Fixture(val raw: String, val finish: String = "stop") {
        var calls = 0
        var sent = emptyList<LlmMessage>()
        val llm = object : LlmClient {
            override fun settings() = AnswerSettings(true, "deepseek-flash", 0.0, "disabled", 16000, null, "")
            override fun completeVerifiedJson(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion {
                if (messages.first().content == dev.aichallenge.rag.grounding.services.SourceScopeInspector.system) {
                    calls++
                    return LlmCompletion("source", "deepseek-flash", """{"bindings":[{"claim_index":0,"chunk_id":"c","scope":"general","premise_spans":[0]}]}""", "stop", 5, usage)
                }
                return complete(messages, maxOutputTokens)
            }
            override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion {
                calls++; sent = messages; assertEquals(16384, maxOutputTokens)
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
            assertEquals(text, payload.path("items")[0].path("statement").asText())
            assertEquals(quote, payload.path("items")[0].path("evidence")[0].path("quote").asText())
        }
    }

    @Test fun `checker sees only cited source context without uncited chunks or dialogue knowledge`() {
        val quote = "Если указана опция --mixed, выполнение остановится после обновления индекса."
        val cited = hit("mixed", "Условия этого примера важны.\n$quote", "Шаг 2")
        val omittedProof = hit("soft", "Опция --soft сохраняет индекс и рабочий каталог без изменений.")
        val f = Fixture(response("unsupported"))
        f.validator.validate(listOf(claim("Режим --soft сохраняет индекс.", quote, cited)), listOf(cited, omittedProof))
        val payload = mapper.readTree(f.sent[1].content)
        assertEquals(3, payload.size())
        assertTrue(payload.path("question").isNull)
        assertTrue(payload.has("items")); assertTrue(payload.has("source_context"))
        assertEquals(1, payload.path("source_context").size())
        assertEquals("mixed", payload.path("source_context")[0].path("chunk_id").asText())
        assertEquals(ClaimSupportPromptAssembler.passages(cited.chunk.text), payload.path("source_context")[0].path("passages").toList().map { it.path("text").asText() })
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
    @Test fun `citation echo is allowed only when it exactly preserves the original evidence`() {
        val quote = "Git сохраняет подготовленный снимок файла."
        val evidence = hit("c", quote)
        val claim = claim("Подготовленная версия сохраняется.", quote, evidence)
        val echoes = listOf(
            listOf(mapOf("chunk_id" to "c", "quote" to quote)),
            listOf(mapOf("chunk_id" to "other", "quote" to quote)),
            listOf(mapOf("chunk_id" to "c", "quote" to "Изменённое доказательство")),
            emptyList<Map<String, String>>(),
        )
        echoes.forEachIndexed { index, echo ->
            val raw = response("supported").replace("\"conditions\":[]", "\"conditions\":[],\"citations\":" + mapper.writeValueAsString(echo))
            val checked = Fixture(raw).validator.validate(listOf(claim), listOf(evidence))
            assertEquals(if (index == 0) SupportCheckStatus.PASSED else SupportCheckStatus.INVALID_RESPONSE, checked.status)
        }
    }
    @Test fun `condition evidence is exact own source and missing premise overrides model approval`() {
        val quote = "При активном флаге X обработчик повторяет запрос."
        val evidence = hit("c", quote)
        val claims = listOf(claim("Обработчик повторяет запрос.", quote, evidence))
        fun raw(conditions: Any?) = mapper.writeValueAsString(mapOf("claims" to listOf(mapOf(
            "claim_index" to 0, "conditions" to conditions, "verdict" to "supported", "reason" to "Условие проверено.",
            "evidence_scope" to "general", "claim_scope" to "general",
        ))))
        val condition = mapOf("chunk_id" to "c", "span_index" to 0, "preserved" to false)
        val rejected = Fixture(raw(listOf(condition))).validator.validate(claims, listOf(evidence))
        assertEquals(SupportCheckStatus.REJECTED, rejected.status)
        assertEquals(ClaimSupportVerdict.UNSUPPORTED, rejected.claims.single().verdict)
        assertTrue(rejected.claims.single().reason.startsWith("Потеряно условие источника."))
        assertEquals(SupportCheckStatus.PASSED, Fixture(raw(listOf(condition + ("preserved" to true)))).validator.validate(claims, listOf(evidence)).status)
        val bad = listOf(null, "none", emptyMap<String, Any>(), List(9) { condition }, listOf(condition - "preserved"),
            listOf(condition + ("preserved" to "false")), listOf(condition + ("span_index" to 1)), listOf(condition + ("span_index" to -1)),
            listOf(condition + ("span_index" to 0.0)), listOf(condition + ("span_index" to "0")), listOf(condition, condition),
            listOf(condition + ("chunk_id" to "foreign")), listOf(condition + ("extra" to 1)))
        for (conditions in bad) {
            assertEquals(SupportCheckStatus.INVALID_RESPONSE, Fixture(raw(conditions)).validator.validate(claims, listOf(evidence, hit("foreign", quote))).status)
        }
        val missing = response("supported").replace("\"conditions\":[],", "")
        assertEquals(SupportCheckStatus.INVALID_RESPONSE, Fixture(missing).validator.validate(claims, listOf(evidence)).status)
    }

    @Test fun `example evidence cannot approve a general claim even when model verdict is supported`() {
        val quote = "В этом примере синхронизация создала конфликт."
        val evidence = hit("c", "Два пользователя одновременно изменили документ. $quote")
        val claims = listOf(claim("Синхронизация создаёт конфликт.", quote, evidence))
        val raw = mapper.writeValueAsString(mapOf("claims" to listOf(mapOf("claim_index" to 0, "verdict" to "supported", "reason" to "x".repeat(300), "conditions" to emptyList<Any>(), "evidence_scope" to "example", "claim_scope" to "general", "text" to claims.single().text))))
        val f = Fixture(raw)
        val checked = f.validator.validate(claims, listOf(evidence))
        assertEquals(SupportCheckStatus.REJECTED, checked.status)
        assertEquals(ClaimSupportVerdict.UNSUPPORTED, checked.claims.single().verdict)
        assertEquals("unsupported_claim", checked.issues.single().code)
        assertEquals(0, checked.issues.single().claimIndex)
        assertTrue(checked.claims.single().reason.startsWith("Обобщён частный пример."))
        assertEquals(300, checked.claims.single().reason.length)
        assertEquals(raw, checked.generation.rawJson)
        assertEquals(1, f.calls)
    }

    @Test fun `scope rule accepts scoped example and does not reject the reverse or promote negative verdicts`() {
        val quote = "В этом примере синхронизация создала конфликт."
        val evidence = hit("c", quote)
        val claims = listOf(claim("В описанном примере синхронизация создала конфликт.", quote, evidence))
        for ((evidenceScope, claimScope) in listOf("example" to "example", "general" to "example", "general" to "general")) {
            val raw = response("supported").replace("\"evidence_scope\":\"general\"", "\"evidence_scope\":\"$evidenceScope\"").replace("\"claim_scope\":\"general\"", "\"claim_scope\":\"$claimScope\"")
            assertEquals(SupportCheckStatus.PASSED, Fixture(raw).validator.validate(claims, listOf(evidence)).status, "$evidenceScope -> $claimScope")
        }
        for (verdict in listOf("unsupported", "contradicted")) {
            val raw = response(verdict).replace("\"evidence_scope\":\"general\"", "\"evidence_scope\":\"example\"")
            val checked = Fixture(raw).validator.validate(claims, listOf(evidence))
            assertEquals(SupportCheckStatus.REJECTED, checked.status)
            assertEquals(if (verdict == "contradicted") ClaimSupportVerdict.CONTRADICTED else ClaimSupportVerdict.UNSUPPORTED, checked.claims.single().verdict)
            assertEquals("Проверены смысл и границы цитаты.", checked.claims.single().reason)
        }
    }

    @Test fun `both scopes are required exact enum strings and unknown values fail closed`() {
        val quote = "В этом примере синхронизация создала конфликт."
        val evidence = hit("c", quote)
        val claims = listOf(claim("В описанном примере синхронизация создала конфликт.", quote, evidence))
        val valid = mapOf<String, Any?>("claim_index" to 0, "verdict" to "supported", "reason" to "Проверен исходный пункт.", "conditions" to emptyList<Any>(), "evidence_scope" to "general", "claim_scope" to "general")
        for (field in listOf("evidence_scope", "claim_scope")) {
            val badNodes = listOf(valid - field) + listOf<Any?>(null, true, 1, "", "General", "general ", "unknown", listOf("general"), mapOf("value" to "general")).map { valid + (field to it) }
            for (node in badNodes) {
                val raw = mapper.writeValueAsString(mapOf("claims" to listOf(node)))
                val checked = Fixture(raw).validator.validate(claims, listOf(evidence))
                assertEquals(SupportCheckStatus.INVALID_RESPONSE, checked.status, raw)
                assertTrue(checked.claims.isEmpty())
                assertEquals("invalid_support_shape", checked.issues.single().code)
            }
        }
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
            valid.replace("Проверены смысл и границы цитаты.", "x".repeat(1001)),
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

    @Test fun `bounded long explanation never discards a valid negative verdict or starts retry`() {
        val quote = "Git сохраняет проиндексированную версию файла."
        val evidence = hit("c", quote)
        val raw = response("unsupported").replace("Проверены смысл и границы цитаты.", "Объяснение. ".repeat(60))
        val f = Fixture(raw)
        val result = f.validator.validate(listOf(claim("Версия сохраняется в индексе.", quote, evidence)), listOf(evidence))
        assertEquals(SupportCheckStatus.REJECTED, result.status)
        assertEquals(1, f.calls)
        assertEquals(raw, result.generation.rawJson)
        assertTrue(result.claims.single().reason.length > 300)
    }
    @Test fun `optional exact text echo preserves supported and unsupported verdicts`() {
        val quote = "Git сохраняет проиндексированную версию файла."
        val evidence = hit("c", quote)
        val claims = listOf(claim("Версия сохраняется в индексе.", quote, evidence))
        for (verdict in listOf("supported", "unsupported")) {
            val raw = mapper.writeValueAsString(mapOf("claims" to listOf(mapOf("claim_index" to 0, "text" to claims.single().text, "verdict" to verdict, "reason" to "Проверен исходный пункт.", "conditions" to emptyList<Any>(), "evidence_scope" to "general", "claim_scope" to "general"))))
            val f = Fixture(raw)
            val checked = f.validator.validate(claims, listOf(evidence))
            assertEquals(if (verdict == "supported") SupportCheckStatus.PASSED else SupportCheckStatus.REJECTED, checked.status)
            assertEquals(if (verdict == "supported") ClaimSupportVerdict.SUPPORTED else ClaimSupportVerdict.UNSUPPORTED, checked.claims.single().verdict)
            assertEquals(raw, checked.generation.rawJson)
            assertEquals(1, f.calls)
        }
    }

    @Test fun `optional text echo must match the indexed claim even when verdicts are reordered`() {
        val quote = "Git сохраняет проиндексированную версию файла."
        val evidence = hit("c", quote)
        val claims = listOf(claim("Версия сохраняется в индексе.", quote, evidence), claim("Индекс хранит подготовленную версию.", quote, evidence))
        fun echoed(index: Int, text: String) = mapOf("claim_index" to index, "text" to text, "verdict" to "supported", "reason" to "Проверен исходный пункт.", "conditions" to emptyList<Any>(), "evidence_scope" to "general", "claim_scope" to "general")
        val reordered = mapper.writeValueAsString(mapOf("claims" to listOf(echoed(1, claims[1].text), echoed(0, claims[0].text))))
        assertEquals(SupportCheckStatus.PASSED, Fixture(reordered).validator.validate(claims, listOf(evidence)).status)
        val mismatched = mapper.writeValueAsString(mapOf("claims" to listOf(echoed(1, claims[0].text), echoed(0, claims[1].text))))
        assertEquals(SupportCheckStatus.INVALID_RESPONSE, Fixture(mismatched).validator.validate(claims, listOf(evidence)).status)
    }

    @Test fun `altered nonstring incomplete and extra text echo responses fail closed`() {
        val quote = "Git сохраняет проиндексированную версию файла."
        val evidence = hit("c", quote)
        val claims = listOf(claim("Версия сохраняется в индексе.", quote, evidence))
        val valid = mapOf<String, Any?>("claim_index" to 0, "text" to claims.single().text, "verdict" to "supported", "reason" to "Проверен исходный пункт.", "conditions" to emptyList<Any>(), "evidence_scope" to "general", "claim_scope" to "general")
        val badNodes = listOf(
            valid + ("text" to "Другая версия."),
            valid + ("text" to " ${claims.single().text}"),
            valid + ("text" to null),
            valid + ("text" to true),
            valid + ("text" to 42),
            valid + ("text" to listOf(claims.single().text)),
            valid + ("text" to mapOf("value" to claims.single().text)),
            valid - "claim_index", valid - "verdict", valid - "reason", valid - "evidence_scope", valid - "claim_scope",
            valid + ("extra" to true),
            (valid - "text") + ("extra" to claims.single().text),
            valid + ("claim_index" to -1),
            valid + ("claim_index" to 1),
            valid + ("claim_index" to "0"),
        )
        for (node in badNodes) {
            val raw = mapper.writeValueAsString(mapOf("claims" to listOf(node)))
            val f = Fixture(raw)
            val checked = f.validator.validate(claims, listOf(evidence))
            assertEquals(SupportCheckStatus.INVALID_RESPONSE, checked.status, raw)
            assertTrue(checked.claims.isEmpty())
            assertEquals("invalid_support_shape", checked.issues.single().code)
            assertEquals(1, f.calls)
        }
    }

    @Test fun `each verdict is required but order is immaterial and truncated output never approves`() {
        val quote = "Git сохраняет проиндексированную версию файла."
        val evidence = hit("c", quote)
        val claims = listOf(claim("Версия сохраняется в индексе.", quote, evidence), claim("Индекс хранит версию.", quote, evidence))
        val reversed = """{"claims":[{"claim_index":1,"verdict":"supported","reason":"Второй подтверждён.","conditions":[],"evidence_scope":"general","claim_scope":"general"},{"claim_index":0,"verdict":"supported","reason":"Первый подтверждён.","conditions":[],"evidence_scope":"general","claim_scope":"general"}]}"""
        val result = Fixture(reversed).validator.validate(claims, listOf(evidence))
        assertEquals(SupportCheckStatus.PASSED, result.status); assertEquals(listOf(0, 1), result.claims.map { it.claimIndex })
        val truncated = Fixture(reversed, "length").validator.validate(claims, listOf(evidence))
        assertEquals(SupportCheckStatus.INVALID_RESPONSE, truncated.status); assertEquals("truncated_support_check", truncated.issues.single().code)
    }
}
