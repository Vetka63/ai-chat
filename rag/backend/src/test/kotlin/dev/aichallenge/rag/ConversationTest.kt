package dev.aichallenge.rag

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.answering.services.CostEstimator
import dev.aichallenge.rag.common.LabException
import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.conversations.adapters.SqliteConversationRepository
import dev.aichallenge.rag.conversations.enums.TurnStatus
import dev.aichallenge.rag.conversations.models.*
import dev.aichallenge.rag.conversations.services.ConversationService
import dev.aichallenge.rag.grounding.enums.GroundedStatus
import dev.aichallenge.rag.grounding.models.*
import dev.aichallenge.rag.grounding.services.GroundingService
import dev.aichallenge.rag.indexing.models.IndexInfo
import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.taskmemory.enums.MemoryLayer
import dev.aichallenge.rag.taskmemory.adapters.LlmDialoguePreparer
import dev.aichallenge.rag.taskmemory.models.*
import dev.aichallenge.rag.taskmemory.ports.DialoguePreparer
import dev.aichallenge.rag.taskmemory.services.MemoryPatchValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Изоляция, persistence, provenance и ошибки проверяются без платных API. */
class ConversationTest {
    @TempDir lateinit var temp: Path
    private val mapper = jacksonObjectMapper()
    private val patch = MemoryPatchValidator(mapper)
    private fun props() = RagProperties("corpus", temp.resolve("test.sqlite").toString(), "http://localhost", "test")
    private fun repo() = SqliteConversationRepository(props(), mapper)
    private fun chat(id: String) = Conversation(id, id, ConversationSettings("index"), "snapshot", Instant.now().toString(), Instant.now().toString(), 0)
    private fun context(question: String = "Цель — сохранить изменения. Нельзя force push.", memory: TaskMemory = TaskMemory()) = DialogueContext("current", question, memory, emptyList(), 0)
    private fun raw(quote: String = "Нельзя force push.") = mapper.writeValueAsString(mapOf("query" to "Как сохранить изменения Git?", "updates" to listOf(mapOf("layer" to "CONSTRAINTS", "key" to "force", "value" to "Без force push", "quote" to quote)), "removals" to emptyList<Any>()))

    @Test fun `memory upsert has server turn id and exact user provenance`() {
        val p = patch.validate(raw(), "stop", context())
        assertEquals("current", p.second.facts.single().sourceTurnId)
        val updated = patch.validate(raw(), "stop", context(memory = p.second))
        assertEquals(1, updated.second.facts.size)
    }
    @Test fun `bad quote truncation extra fields and duplicate keys reject whole patch`() {
        val duplicate = mapper.readTree(raw()).path("updates").first()
        for (bad in listOf(raw("Придуманный факт"), raw().dropLast(1) + ",\"sourceTurnId\":\"old\"}", mapper.writeValueAsString(mapOf("query" to "Q", "updates" to listOf(duplicate, duplicate), "removals" to emptyList<Any>())))) assertThrows(Exception::class.java) { patch.validate(bad, "stop", context()) }
        assertThrows(Exception::class.java) { patch.validate(raw(), "length", context()) }
    }
    @Test fun `unchanged goal retained and explicit removal does not clear other layers`() {
        val goal = MemoryFact(MemoryLayer.GOAL, "goal", "Сохранить изменения", "old", "Сохранить изменения")
        val first = patch.validate(raw(), "stop", context(memory = TaskMemory(listOf(goal)))).second
        val removal = """{"query":"Git", "updates":[],"removals":[{"layer":"CONSTRAINTS","key":"force","quote":"Снимаю запрет"}]}"""
        val after = patch.validate(removal, "stop", context("Снимаю запрет", first)).second
        assertEquals(listOf(goal), after.facts)
        assertThrows(Exception::class.java) { patch.validate(removal, "stop", context("Снимаю запрет")) }
    }
    @Test fun `memory limits fail explicitly without evicting old facts`() {
        val previous = TaskMemory((1..12).map { MemoryFact(MemoryLayer.CONSTRAINTS, "$it", "value", "old", "quote") })
        assertThrows(Exception::class.java) { patch.validate(raw(), "stop", context(memory = previous)) }
    }
    @Test fun `global goal cannot drift to local step but explicit new goal works`() {
        val goal = MemoryFact(MemoryLayer.GOAL, "goal", "Сохранить изменения", "old", "сохранить изменения")
        val input = context("Теперь хочу убрать файлы из индекса", TaskMemory(listOf(goal)))
        val raw = """{"query":"Git reset", "updates":[{"layer":"GOAL","key":"goal","value":"Убрать из индекса","quote":"Теперь хочу убрать файлы из индекса"}],"removals":[]}"""
        assertEquals(listOf(goal), patch.validate(raw, "stop", input).second.facts)
        val newGoal = raw.replace("Теперь хочу убрать файлы из индекса", "Новая цель: убрать файлы из индекса")
        assertEquals("Убрать из индекса", patch.validate(newGoal, "stop", context("Новая цель: убрать файлы из индекса", input.memory)).second.facts.single().value)
    }
    @Test fun `invalid preparation preserves measured usage and cannot mutate old memory`() {
        val usage = TokenUsage(10, 5, 15, null, null)
        var cap: Int? = 100
        val fake = object : LlmClient {
            override fun settings() = AnswerSettings(true, "deepseek-flash", 0.0, "disabled", 16000, null, "")
            override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion { cap = maxOutputTokens; return LlmCompletion("id", "deepseek-flash", raw("quote from other chat"), "stop", 1, usage) }
        }
        val input = context(memory = TaskMemory(listOf(MemoryFact(MemoryLayer.GOAL, "goal", "Existing", "old", "Existing"))))
        val r = LlmDialoguePreparer(fake, mapper, patch, CostEstimator()).prepare(input)
        assertEquals(input.memory, r.memory); assertTrue(r.issues.isNotEmpty()); assertEquals(usage, r.usage); assertNull(cap)
        assertTrue(r.messages[0].content.contains("GOAL")); assertTrue(r.messages[1].content.contains("user_question"))
    }
    @Test fun `preparation separates latest exchange without duplicating bounded history`() {
        val fake = object : LlmClient {
            override fun settings() = AnswerSettings(true, "deepseek-flash", 0.0, "disabled", 16000, null, "")
            override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?) = LlmCompletion("id", "deepseek-flash", """{"query":"Как включить файл в временное сохранение?","updates":[],"removals":[]}""", "stop", 1, null)
        }
        val recent = listOf(DialogueItem("older", "Обсуждали другую операцию", "Старый ответ"), DialogueItem("latest", "Временное сохранение", "Последний ответ"))
        val trace = LlmDialoguePreparer(fake, mapper, patch, CostEstimator()).prepare(context("А как включить файл?").copy(recent = recent))
        val payload = mapper.readTree(trace.messages[1].content)
        assertEquals("latest", payload.path("latest_exchange").path("turnId").asText())
        assertEquals(1, payload.path("recent_dialogue").size())
        assertEquals("older", payload.path("recent_dialogue").first().path("turnId").asText())
        assertTrue(trace.issues.isEmpty())
    }
    @Test fun `history memory and immutable index survive restart and stay isolated`() {
        val r = repo(); r.create(chat("a")); r.create(chat("b"))
        val c = r.begin("a", SendTurn("one", "Первый вопрос", 0))
        val memory = TaskMemory(listOf(MemoryFact(MemoryLayer.GOAL, "goal", "Цель", c.turn.id, "Первый вопрос")))
        r.finish("a", c.turn.copy(status = TurnStatus.COMPLETED), memory)
        val restarted = repo()
        assertEquals(memory, restarted.detail("a").memory); assertEquals("Первый вопрос", restarted.detail("a").turns.single().question)
        assertEquals("snapshot", restarted.detail("a").conversation.snapshotId); assertTrue(restarted.detail("b").memory.facts.isEmpty()); assertTrue(restarted.detail("b").turns.isEmpty())
    }
    @Test fun `idempotent duplicate pending and completed never owns second execution`() {
        val r = repo(); r.create(chat("a")); val request = SendTurn("same", "Question", 0)
        val c = r.begin("a", request); assertTrue(c.owned)
        assertFalse(r.begin("a", request).owned)
        r.finish("a", c.turn.copy(status = TurnStatus.COMPLETED), TaskMemory())
        assertFalse(r.begin("a", request).owned); assertEquals(1, r.detail("a").turns.size)
        assertThrows(LabException::class.java) { r.begin("a", request.copy(question = "different")) }
    }
    @Test fun `stale and pending writes rejected and deletes cascade only completed chat`() {
        val r = repo(); r.create(chat("a"))
        assertThrows(LabException::class.java) { r.begin("a", SendTurn("one", "Q", 3)) }
        val c = r.begin("a", SendTurn("one", "Q", 0))
        assertThrows(LabException::class.java) { r.begin("a", SendTurn("two", "Q2", 1)) }; assertThrows(LabException::class.java) { r.delete("a") }
        r.finish("a", c.turn.copy(status = TurnStatus.COMPLETED), TaskMemory())
        r.delete("a"); assertTrue(r.list().isEmpty()); assertThrows(LabException::class.java) { r.detail("a") }
    }
    @Test fun `restart interrupts pending but preserves question and permits continuation`() {
        val r = repo(); r.create(chat("a")); r.begin("a", SendTurn("one", "Question before restart", 0))
        val restarted = repo(); val d = restarted.detail("a")
        assertEquals(TurnStatus.INTERRUPTED, d.turns.single().status); assertEquals("Question before restart", d.turns.single().question)
        assertTrue(restarted.begin("a", SendTurn("two", "Continue", d.conversation.revision)).owned)
    }
    @Test fun `two simultaneous sends exactly one owner`() {
        val r = repo(); r.create(chat("a")); val latch = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { n -> executor.submit<Boolean> { latch.await(); try { r.begin("a", SendTurn("r$n", "Q", 0)).owned } catch (_: LabException) { false } } }
            latch.countDown(); assertEquals(1, futures.count { it.get() }); assertEquals(1, r.detail("a").turns.size)
        } finally { executor.shutdownNow() }
    }
    private inner class Fixture(val fail: Boolean = false, val invalid: Boolean = false) {
        val r = repo(); val indexes = mock(IndexRepository::class.java); val grounding = mock(GroundingService::class.java)
        val received = mutableListOf<DialogueContext>(); val usage = TokenUsage(10, 5, 15, null, null)
        val preparer = object : DialoguePreparer { override fun prepare(context: DialogueContext): PreparationTrace {
            received.add(context); if (fail) throw LabException("llm_unavailable", "Провайдер недоступен.")
            val memory = if (context.question.contains("Цель")) TaskMemory(listOf(MemoryFact(MemoryLayer.GOAL, "goal", context.question, context.turnId, context.question))) else context.memory
            return PreparationTrace("resolved: ${context.question}", memory, emptyList(), "fixture", "stop", 1, usage, null, emptyList(), "{}", if (invalid) listOf("invalid") else emptyList())
        } }
        val service = ConversationService(r, indexes, preparer, grounding)
        init {
            val index = mock(IndexInfo::class.java); `when`(index.snapshotId).thenReturn("snapshot"); `when`(indexes.index("index")).thenReturn(index)
            `when`(grounding.answer(any(GroundingRequest::class.java) ?: GroundingRequest("Q", "index"), any(GroundingDialogue::class.java) ?: GroundingDialogue("Q", TaskMemory(), emptyList(), 0))).thenAnswer { call ->
                val request = call.getArgument<GroundingRequest>(0)
                GroundedResult(request, "snapshot", GroundedStatus.UNKNOWN, "Не знаю", "Уточните", emptyList(), emptyList(), emptyList(), null, null, null, 1, usage, null, 1, emptyList())
            }
        }
        fun create(history: Int = 6) = service.create(CreateConversation("Chat", ConversationSettings("index", historyTurns = history)))
        fun send(d: ConversationDetail, q: String) = service.send(d.conversation.id, SendTurn("r${d.conversation.revision}", q, d.conversation.revision))
    }
    @Test fun `every turn resolves history retains goal fresh grounding and accounts prep once`() {
        val f = Fixture(); var d = f.create(); d = f.send(d, "Цель: сохранить изменения"); d = f.send(d, "А если он отправлен?")
        assertEquals(1, f.received.last().recent.size); assertEquals("Цель: сохранить изменения", f.received.last().memory.facts.single().value)
        assertEquals(30L, d.turns.last().totalUsage!!.totalTokens); assertEquals(2, d.turns.last().llmStagesAttempted)
        verify(f.grounding, times(2)).answer(any(GroundingRequest::class.java) ?: GroundingRequest("Q", "index"), any(GroundingDialogue::class.java) ?: GroundingDialogue("Q", TaskMemory(), emptyList(), 0))
        assertEquals("А если он отправлен?", d.turns.last().question)
    }
    @Test fun `bounded tail is visible but full history persists`() {
        val f = Fixture(); var d = f.create(2); for (n in 1..5) d = f.send(d, "Q$n")
        assertEquals(5, d.turns.size); assertEquals(2, d.turns.last().includedHistoryTurnIds.size); assertEquals(2, d.turns.last().omittedHistoryTurnCount)
    }
    @Test fun `preparation failure preserves user old memory and blocks grounding`() {
        for (invalid in listOf(false, true)) {
            val f = Fixture(fail = !invalid, invalid = invalid); val d = f.send(f.create(), "Цель: новая")
            assertEquals(TurnStatus.COMPLETED, d.turns.single().status); assertEquals("Цель: новая", d.turns.single().question)
            assertTrue(d.memory.facts.isEmpty()); assertNotNull(d.turns.single().issue); assertNull(d.turns.single().result)
            verifyNoInteractions(f.grounding)
        }
    }
    @Test fun `invalid create never bills and rejects finalK greater than candidates`() {
        val f = Fixture(); assertThrows(IllegalArgumentException::class.java) { f.service.create(CreateConversation("Chat", ConversationSettings("index", candidateTopK = 2, finalTopK = 3))) }
        assertTrue(f.received.isEmpty()); verifyNoInteractions(f.indexes)
    }
}
