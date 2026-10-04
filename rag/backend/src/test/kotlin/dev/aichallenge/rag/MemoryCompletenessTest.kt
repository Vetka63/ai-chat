package dev.aichallenge.rag

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.ports.LlmClient
import dev.aichallenge.rag.answering.services.CostEstimator
import dev.aichallenge.rag.config.RagProperties
import dev.aichallenge.rag.conversations.adapters.SqliteConversationRepository
import dev.aichallenge.rag.conversations.models.*
import dev.aichallenge.rag.conversations.services.ConversationService
import dev.aichallenge.rag.grounding.enums.GroundedStatus
import dev.aichallenge.rag.grounding.models.*
import dev.aichallenge.rag.grounding.services.GroundingService
import dev.aichallenge.rag.indexing.models.IndexInfo
import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.taskmemory.adapters.LlmDialoguePreparer
import dev.aichallenge.rag.taskmemory.enums.MemoryLayer
import dev.aichallenge.rag.taskmemory.models.*
import dev.aichallenge.rag.taskmemory.services.MemoryPatchValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path

/** Проверяет применение и полноту найденных фактов, но не подменяет живую проверку извлечения LLM. */
class MemoryCompletenessTest {
    @TempDir lateinit var temp: Path
    private val mapper = jacksonObjectMapper()
    private val validator = MemoryPatchValidator(mapper)
    private fun context(question: String, memory: TaskMemory = TaskMemory()) = DialogueContext("current", question, memory, emptyList(), 0)
    private fun change(layer: String, key: String, value: String, quote: String) = mapOf("layer" to layer, "key" to key, "value" to value, "quote" to quote)
    private fun item(change: Map<String, String>, action: String = "UPSERT") = change + ("action" to action)
    private fun raw(updates: List<Any> = emptyList(), removals: List<Any> = emptyList(), inventory: List<Any> = emptyList()) = mapper.writeValueAsString(mapOf("query" to "Вопрос для поиска", "inventory" to inventory, "updates" to updates, "removals" to removals))
    private fun validate(raw: String, input: DialogueContext) = validator.validate(raw, "stop", input, requireInventory = true)
    private fun fact(layer: MemoryLayer, key: String, value: String) = MemoryFact(layer, key, value, "previous", value)

    @Test fun `natural names are applied with current user provenance without remember command`() {
        for (name in listOf("release-payment", "исследование-ветка", "trial_42")) {
            val question = "Назовём рабочую ветку $name. Как создать её?"
            val c = change("TERMS", "working_branch", name, "Назовём рабочую ветку $name.")
            val result = validate(raw(listOf(c), inventory = listOf(item(c))), context(question))
            assertEquals(name, result.second.facts.single().value)
            assertEquals("current", result.second.facts.single().sourceTurnId)
            assertEquals(c["quote"], result.second.facts.single().quote)
        }
    }

    @Test fun `discovered fact cannot silently disappear or become an ungrounded patch`() {
        val q = "Проект называется Атлас."
        val c = change("TERMS", "project_name", "Атлас", q)
        for (invalid in listOf(raw(inventory = listOf(item(c))), raw(updates = listOf(c)))) {
            assertThrows(IllegalArgumentException::class.java) { validate(invalid, context(q)) }
        }
        val legacy = """{"query":"Q","updates":[],"removals":[]}"""
        assertThrows(IllegalArgumentException::class.java) { validate(legacy, context("Что такое индекс?")) }
    }

    @Test fun `retain must reference identical existing layer key and value`() {
        val q = "По-прежнему используем имя Атлас."
        val old = TaskMemory(listOf(fact(MemoryLayer.TERMS, "project_name", "Атлас")))
        val retain = item(change("TERMS", "project_name", "Атлас", q), "RETAIN")
        assertEquals(old, validate(raw(inventory = listOf(retain)), context(q, old)).second)
        for (bad in listOf(retain + ("key" to "missing"), retain + ("value" to "Другое имя"), retain + ("layer" to "CLARIFICATIONS"))) {
            assertThrows(IllegalArgumentException::class.java) { validate(raw(inventory = listOf(bad)), context(q, old)) }
        }
    }

    @Test fun `minimal retain preserves existing fact and original provenance in production preparation`() {
        val oldFact = MemoryFact(MemoryLayer.CONSTRAINTS, "offline", "Не обращаться к сети", "original-user", "Работаем без обращений к сети.")
        val old = TaskMemory(listOf(oldFact, fact(MemoryLayer.TERMS, "project", "Атлас")))
        val retain = mapOf("action" to "RETAIN", "layer" to "CONSTRAINTS", "key" to "offline")
        val usage = TokenUsage(10, 5, 15, null, null)
        val fake = object : LlmClient {
            override fun settings() = AnswerSettings(true, "deepseek-flash", 0.0, "disabled", 16000, null, "")
            override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?) = LlmCompletion("id", "deepseek-flash", raw(inventory = listOf(retain)), "stop", 1, usage)
        }
        val trace = LlmDialoguePreparer(fake, mapper, validator, CostEstimator()).prepare(context("Как выполнить задачу без сети?", old))
        assertTrue(trace.issues.isEmpty())
        assertTrue(trace.changes.isEmpty())
        assertEquals(old, trace.memory)
        assertEquals("original-user", trace.memory.facts.first().sourceTurnId)
        assertEquals("Работаем без обращений к сети.", trace.memory.facts.first().quote)
        assertFalse(mapper.readTree(trace.rawJson).path("inventory").first().has("quote"))
        assertFalse(mapper.readTree(trace.rawJson).path("inventory").first().has("value"))
        assertEquals(usage, trace.usage)
    }

    @Test fun `quote optional retain still rejects unknown mismatched duplicate and forged references`() {
        val q = "По-прежнему используем имя Атлас."
        val old = TaskMemory(listOf(fact(MemoryLayer.TERMS, "project_name", "Атлас")))
        val retain = mapOf("action" to "RETAIN", "layer" to "TERMS", "key" to "project_name", "value" to "Атлас")
        for (bad in listOf(retain + ("key" to "missing"), retain + ("value" to "Другое имя"), retain + ("layer" to "CLARIFICATIONS"), retain + ("sourceTurnId" to "forged"), retain - "key", retain + ("action" to "IGNORE"))) {
            assertThrows(IllegalArgumentException::class.java) { validate(raw(inventory = listOf(bad)), context(q, old)) }
        }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(inventory = listOf(retain)), context(q)) }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(inventory = listOf(retain, retain)), context(q, old)) }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(change("TERMS", "project_name", "Атлас", q)), inventory = listOf(retain)), context(q, old)) }
        val removal = mapOf("layer" to "TERMS", "key" to "project_name", "quote" to q)
        assertThrows(IllegalArgumentException::class.java) { validate(raw(removals = listOf(removal), inventory = listOf(retain)), context(q, old)) }
    }

    @Test fun `optional retain annotation is bounded but cannot change existing provenance`() {
        val q = "По-прежнему используем имя Атлас."
        val old = TaskMemory(listOf(MemoryFact(MemoryLayer.TERMS, "project_name", "Атлас", "original", "Назовём проект Атлас.")))
        val retain = mapOf("action" to "RETAIN", "layer" to "TERMS", "key" to "project_name")
        for (quote in listOf(q, "Назовём проект Атлас.", "Перефразированная аннотация", "x".repeat(500))) {
            val result = validate(raw(inventory = listOf(retain + ("quote" to quote))), context(q, old))
            assertEquals(old, result.second)
            assertTrue(result.third.isEmpty())
            assertSame(old.facts.single(), result.second.facts.single())
        }
        for (quote in listOf<Any?>(null, "", "  \n\t", "x".repeat(501), false, 0, emptyList<String>(), emptyMap<String, String>())) {
            assertThrows(IllegalArgumentException::class.java) { validate(raw(inventory = listOf(retain + ("quote" to quote))), context(q, old)) }
        }
    }

    @Test fun `synthetic shared history followup accepts nonexact retain annotation without new provenance`() {
        val ban = MemoryFact(MemoryLayer.CONSTRAINTS, "no_rewrite_shared_branch_history", "Не переписывать общую историю", "previous-user", "Мы договорились не переписывать общую историю.")
        val old = TaskMemory(listOf(ban, fact(MemoryLayer.GOAL, "goal", "Сохранить изменения")))
        val q = "С учётом запрета переписывать общую историю чем revert отличается от reset?"
        val annotation = "запретом переписывать общую историю"
        assertFalse(q.contains(annotation))
        val retain = mapOf("action" to "RETAIN", "layer" to "CONSTRAINTS", "key" to ban.key, "quote" to annotation)
        val payload = raw(inventory = listOf(retain))
        val fake = object : LlmClient {
            override fun settings(): AnswerSettings = error("Not needed")
            override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion = error("Regular generation must not run")
            override fun completePreparationJson(messages: List<LlmMessage>, maxOutputTokens: Int?) = LlmCompletion("fixture", "synthetic-preparer", payload, "stop", 1, null)
        }
        val trace = LlmDialoguePreparer(fake, mapper, validator, CostEstimator()).prepare(context(q, old))
        assertTrue(trace.issues.isEmpty())
        assertTrue(trace.changes.isEmpty())
        assertEquals(old, trace.memory)
        assertSame(ban, trace.memory.facts.first())
        assertEquals(annotation, mapper.readTree(trace.rawJson).path("inventory").first().path("quote").asText())
        assertEquals(ban.quote, trace.memory.facts.first().quote)
        assertEquals("previous-user", trace.memory.facts.first().sourceTurnId)
    }

    @Test fun `retain value and quote are independently optional but supplied value is exact`() {
        val q = "По-прежнему используем имя Атлас."
        val old = TaskMemory(listOf(fact(MemoryLayer.TERMS, "project_name", "Атлас")))
        val retain = mapOf("action" to "RETAIN", "layer" to "TERMS", "key" to "project_name")
        for (entry in listOf(retain, retain + ("value" to "Атлас"), retain + ("quote" to q), retain + mapOf("value" to "Атлас", "quote" to q))) {
            val result = validate(raw(inventory = listOf(entry)), context(q, old))
            assertEquals(old, result.second)
            assertTrue(result.third.isEmpty())
        }
        for (value in listOf(null, "", " Атлас ", "атлас", "Другое имя")) {
            assertThrows(IllegalArgumentException::class.java) { validate(raw(inventory = listOf(retain + ("value" to value))), context(q, old)) }
        }
        for (bad in listOf(retain + ("key" to "unknown"), retain + ("sourceTurnId" to "forged"), retain - "layer")) {
            assertThrows(IllegalArgumentException::class.java) { validate(raw(inventory = listOf(bad)), context(q, old)) }
        }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(inventory = listOf(retain)), context(q)) }
    }

    @Test fun `rename upsert with minimal retain references is atomic and preserves old provenance`() {
        val goal = MemoryFact(MemoryLayer.GOAL, "goal", "Подготовить выпуск приложения", "first", "Моя цель — подготовить выпуск приложения.")
        val ban = MemoryFact(MemoryLayer.CONSTRAINTS, "shared_force_ban", "Force push в общую ветку запрещён", "first", "В общую ветку force push запрещён.")
        val branch = MemoryFact(MemoryLayer.TERMS, "working_branch", "release-payment", "first", "Назовём рабочую ветку release-payment.")
        val old = TaskMemory(listOf(goal, ban, branch))
        val quote = "Переименуем рабочую ветку в release-payments-v2."
        val q = "$quote Общая цель и запрет force push прежние. Как переименовать локальную ветку?"
        val update = change("TERMS", branch.key, "release-payments-v2", quote)
        val retained = listOf(mapOf("action" to "RETAIN", "layer" to "GOAL", "key" to goal.key), mapOf("action" to "RETAIN", "layer" to "CONSTRAINTS", "key" to ban.key, "quote" to "Перефразированное старое ограничение"))
        val result = validate(raw(listOf(update), inventory = listOf(item(update)) + retained), context(q, old))
        assertEquals(3, result.second.facts.size)
        assertEquals(goal, result.second.facts.single { it.layer == MemoryLayer.GOAL })
        assertEquals(ban, result.second.facts.single { it.layer == MemoryLayer.CONSTRAINTS })
        assertEquals(MemoryFact(MemoryLayer.TERMS, branch.key, "release-payments-v2", "current", quote), result.second.facts.single { it.layer == MemoryLayer.TERMS })
        assertEquals(listOf(MemoryChange(MemoryLayer.TERMS, branch.key, "release-payments-v2", quote)), result.third)
        assertEquals(listOf(goal, ban, branch), old.facts)
        val badRetain = retained.last() + ("key" to "unknown")
        assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(update), inventory = listOf(item(update), retained.first(), badRetain)), context(q, old)) }
        assertEquals(listOf(goal, ban, branch), old.facts)
        assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(update), inventory = listOf(item(update + ("quote" to "Чужая цитата"))) + retained), context(q, old)) }
        assertEquals(listOf(goal, ban, branch), old.facts)
    }

    @Test fun `upsert and remove still require exact quotes in both patch and inventory`() {
        val q = "Не обращаться к сети"
        val update = change("CONSTRAINTS", "offline", q, q)
        val upsert = item(update)
        assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(update), inventory = listOf(upsert - "quote")), context(q)) }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(update - "quote"), inventory = listOf(upsert)), context(q)) }
        for (wrongQuote in listOf("Не обращаться к интернету", "Придуманный текст")) {
            val badUpdate = update + ("quote" to wrongQuote)
            assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(badUpdate), inventory = listOf(item(badUpdate))), context(q)) }
            assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(update), inventory = listOf(upsert + ("quote" to wrongQuote))), context(q)) }
        }
        val old = TaskMemory(listOf(fact(MemoryLayer.CONSTRAINTS, "offline", q)))
        val cancel = "Снимаю ограничение на обращения к сети."
        val removal = mapOf("layer" to "CONSTRAINTS", "key" to "offline", "quote" to cancel)
        val inventory = removal + mapOf("action" to "REMOVE", "value" to null)
        assertThrows(IllegalArgumentException::class.java) { validate(raw(removals = listOf(removal), inventory = listOf(inventory - "quote")), context(cancel, old)) }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(removals = listOf(removal - "quote"), inventory = listOf(inventory)), context(cancel, old)) }
        for (wrongQuote in listOf(q, "Придуманный текст")) {
            assertThrows(IllegalArgumentException::class.java) { validate(raw(removals = listOf(removal + ("quote" to wrongQuote)), inventory = listOf(inventory + ("quote" to wrongQuote))), context(cancel, old)) }
        }
    }

    @Test fun `duplicate JSON keys and trailing objects cannot override memory inventory`() {
        val q = context("Что такое индекс?")
        assertThrows(Exception::class.java) { validate(raw() + " {}", q) }
        assertThrows(Exception::class.java) { validate(raw().dropLast(1) + ",\"inventory\":[]}", q) }
    }

    @Test fun `a broader new ban cannot be retained as a narrower existing ban`() {
        val q = "Мы договорились не переписывать общую историю."
        val old = TaskMemory(listOf(fact(MemoryLayer.CONSTRAINTS, "force_push_ban", "Force push в общую ветку запрещён")))
        val general = change("CONSTRAINTS", "shared_history_immutable", "Не переписывать общую историю", q)
        val after = validate(raw(listOf(general), inventory = listOf(item(general))), context(q, old)).second
        assertEquals(2, after.facts.size)
        assertTrue(after.facts.contains(old.facts.single()))
        val badRetain = item(general + ("key" to "force_push_ban"), "RETAIN")
        assertThrows(IllegalArgumentException::class.java) { validate(raw(inventory = listOf(badRetain)), context(q, old)) }
    }

    @Test fun `rename and changed situation replace the existing keys without contradiction`() {
        val old = TaskMemory(listOf(fact(MemoryLayer.TERMS, "working_branch", "review-alpha"), fact(MemoryLayer.CLARIFICATIONS, "published", "Коммит локальный")))
        val q = "Переименуем рабочую ветку в review-beta. Коммит теперь опубликован."
        val updates = listOf(change("TERMS", "working_branch", "review-beta", "Переименуем рабочую ветку в review-beta."), change("CLARIFICATIONS", "published", "Коммит опубликован", "Коммит теперь опубликован."))
        val after = validate(raw(updates, inventory = updates.map { item(it) }), context(q, old)).second
        assertEquals(2, after.facts.size)
        assertEquals(setOf("review-beta", "Коммит опубликован"), after.facts.map { it.value }.toSet())
        assertTrue(after.facts.all { it.sourceTurnId == "current" })
    }

    @Test fun `explicit removal has provenance and cannot silently remove another rule`() {
        val first = fact(MemoryLayer.CONSTRAINTS, "offline", "Не обращаться к сети")
        val second = fact(MemoryLayer.CONSTRAINTS, "no_deletion", "Не удалять файлы")
        val old = TaskMemory(listOf(first, second))
        val q = "Снимаю ограничение на обращения к сети."
        val removal = mapOf("layer" to "CONSTRAINTS", "key" to "offline", "quote" to q)
        val inventory = removal + mapOf("action" to "REMOVE", "value" to null)
        assertEquals(listOf(second), validate(raw(removals = listOf(removal), inventory = listOf(inventory)), context(q, old)).second.facts)
        assertThrows(IllegalArgumentException::class.java) { validate(raw(removals = listOf(removal)), context(q, old)) }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(removals = listOf(removal), inventory = listOf(inventory)), context(q)) }
    }

    @Test fun `all four optional null removal value combinations are equivalent in production preparation`() {
        val ban = MemoryFact(MemoryLayer.CONSTRAINTS, "force_push_common_branch", "в общую ветку force push запрещён", "first", "В общую ветку force push запрещён.")
        val goal = fact(MemoryLayer.GOAL, "goal", "подготовить выпуск приложения в отдельной ветке")
        val branch = MemoryFact(MemoryLayer.TERMS, "working_branch", "рабочая ветка называется release-payments-v2", "rename", "Переименуем рабочую ветку в release-payments-v2.")
        val old = TaskMemory(listOf(goal, branch, ban))
        val quote = "Снимаю прежний запрет на force push в общую ветку."
        val q = "$quote Это изменение условий учебного сценария, команды выполнять не нужно. Как посмотреть список локальных веток?"
        val removal = mapOf("layer" to "CONSTRAINTS", "key" to ban.key, "quote" to quote)
        val reference = removal + ("action" to "REMOVE")
        for (entry in listOf(removal, removal + ("value" to null))) for (inventory in listOf(reference, reference + ("value" to null))) {
            val payload = raw(removals = listOf(entry), inventory = listOf(inventory))
            val fake = object : LlmClient {
                override fun settings() = AnswerSettings(true, "deepseek-flash", 0.0, "disabled", 16000, null, "")
                override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?) = LlmCompletion("id", "deepseek-flash", payload, "stop", 1, null)
            }
            val trace = LlmDialoguePreparer(fake, mapper, validator, CostEstimator()).prepare(context(q, old))
            assertTrue(trace.issues.isEmpty())
            assertEquals(listOf(goal, branch), trace.memory.facts)
            assertEquals(listOf(MemoryChange(MemoryLayer.CONSTRAINTS, ban.key, null, quote)), trace.changes)
            assertEquals(payload, trace.rawJson)
            assertEquals(listOf(goal, branch, ban), old.facts)
        }
    }

    @Test fun `explicit null removal still requires exact current provenance and complete inventory`() {
        val ban = fact(MemoryLayer.CONSTRAINTS, "offline", "Не обращаться к сети")
        val old = TaskMemory(listOf(ban))
        val quote = "Снимаю ограничение на обращения к сети."
        val q = "$quote Как посмотреть ветки?"
        val removal = mapOf("layer" to "CONSTRAINTS", "key" to ban.key, "value" to null, "quote" to quote)
        val inventory = removal + ("action" to "REMOVE")
        for (bad in listOf(removal - "quote", removal + ("quote" to null), removal + ("quote" to ""), removal + ("quote" to ban.quote))) {
            assertThrows(IllegalArgumentException::class.java) { validate(raw(removals = listOf(bad), inventory = listOf(bad + ("action" to "REMOVE"))), context(q, old)) }
        }
        for (badInventory in listOf(inventory - "quote", inventory - "quote" - "value", inventory + ("quote" to "Как посмотреть ветки?"), inventory + ("sourceTurnId" to "forged"))) {
            assertThrows(IllegalArgumentException::class.java) { validate(raw(removals = listOf(removal), inventory = listOf(badInventory)), context(q, old)) }
        }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(removals = listOf(removal)), context(q, old)) }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(inventory = listOf(inventory)), context(q, old)) }
        val unknown = removal + ("key" to "unknown")
        assertThrows(IllegalArgumentException::class.java) { validate(raw(removals = listOf(unknown), inventory = listOf(unknown + ("action" to "REMOVE"))), context(q, old)) }
        assertEquals(listOf(ban), old.facts)
    }

    @Test fun `removal rejects nonnull values extra fields and duplicate keys atomically`() {
        val ban = fact(MemoryLayer.CONSTRAINTS, "offline", "Не обращаться к сети")
        val old = TaskMemory(listOf(ban))
        val quote = "Снимаю ограничение на обращения к сети."
        val q = "$quote Проект называется Атлас."
        val update = change("TERMS", "project", "Атлас", "Проект называется Атлас.")
        val removal = mapOf("layer" to "CONSTRAINTS", "key" to ban.key, "quote" to quote)
        val inventory = listOf(item(update), removal + mapOf("action" to "REMOVE", "value" to null))
        for (value in listOf<Any>("null", "", ban.value, false, 0, emptyList<String>(), emptyMap<String, String>())) {
            assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(update), listOf(removal + ("value" to value)), inventory), context(q, old)) }
            val badInventory = listOf(item(update), removal + mapOf("action" to "REMOVE", "value" to value))
            assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(update), listOf(removal), badInventory), context(q, old)) }
            assertEquals(listOf(ban), old.facts)
        }
        for (extra in listOf("action" to "REMOVE", "sourceTurnId" to "forged", "extra" to "ignored")) {
            assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(update), listOf(removal + ("value" to null) + extra), inventory), context(q, old)) }
        }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(update), listOf(removal, removal + ("value" to null)), inventory), context(q, old)) }
        assertEquals(listOf(ban), old.facts)
    }

    @Test fun `assistant and old user claims cannot supply current memory provenance`() {
        val quote = "Будем использовать main"
        val c = change("TERMS", "target", "main", quote)
        val input = context("Что дальше?").copy(recent = listOf(DialogueItem("old", "Старый вопрос", quote)))
        assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(c), inventory = listOf(item(c))), input) }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(c), inventory = listOf(item(c))), input.copy(recent = listOf(DialogueItem("old", quote, null)))) }
        assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(c), inventory = listOf(item(c))), input.copy(recent = listOf(DialogueItem("old", "Старый вопрос", null, quote)))) }
    }

    @Test fun `inventory cannot bypass protected goal or create a duplicate value under another key`() {
        val goal = fact(MemoryLayer.GOAL, "goal", "Сохранить изменения")
        val q = "Теперь хочу убрать файлы из индекса"
        val c = change("GOAL", "goal", "Убрать файлы из индекса", q)
        assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(c), inventory = listOf(item(c))), context(q, TaskMemory(listOf(goal)))) }
        val explicit = "Новая цель: убрать файлы из индекса"
        val allowed = c + ("quote" to explicit)
        assertEquals(allowed["value"], validate(raw(listOf(allowed), inventory = listOf(item(allowed))), context(explicit, TaskMemory(listOf(goal)))).second.facts.single().value)
        val old = TaskMemory(listOf(fact(MemoryLayer.CONSTRAINTS, "preserve", "Не удалять файлы")))
        val duplicated = change("CONSTRAINTS", "alias", "Не удалять файлы", "Не удалять файлы")
        assertThrows(IllegalArgumentException::class.java) { validate(raw(listOf(duplicated), inventory = listOf(item(duplicated))), context("Не удалять файлы", old)) }
        assertEquals(1, old.facts.size)
    }

    @Test fun `incomplete production inventory fails closed and keeps measured usage and old memory`() {
        val q = "Рабочая ветка называется release-blue."
        val discovered = change("TERMS", "working_branch", "release-blue", q)
        val usage = TokenUsage(10, 5, 15, null, null)
        val fake = object : LlmClient {
            override fun settings() = AnswerSettings(true, "deepseek-flash", 0.0, "disabled", 16000, null, "")
            override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion {
                assertEquals(16384, maxOutputTokens)
                return LlmCompletion("id", "deepseek-flash", raw(inventory = listOf(item(discovered))), "stop", 2, usage)
            }
        }
        val old = TaskMemory(listOf(fact(MemoryLayer.CONSTRAINTS, "preserve", "Не удалять файлы")))
        val trace = LlmDialoguePreparer(fake, mapper, validator, CostEstimator()).prepare(context(q, old))
        assertEquals(old, trace.memory)
        assertEquals(usage, trace.usage)
        assertEquals(listOf("invalid_dialogue_preparation"), trace.issues)
        assertTrue(trace.changes.isEmpty())
        assertTrue(trace.rawJson.contains("release-blue"))
    }

    @Test fun `production preparer uses separate profile with technical cap`() {
        var calls = 0
        val fake = object : LlmClient {
            override fun settings(): AnswerSettings = error("Not needed")
            override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion = error("Regular generation must not run")
            override fun completeJson(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion = error("Generic JSON must not run")
            override fun completePreparationJson(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion {
                calls++
                assertEquals(16384, maxOutputTokens)
                return LlmCompletion("fixture", "separate-preparer", raw(), "stop", 1, null)
            }
        }
        val trace = LlmDialoguePreparer(fake, mapper, validator, CostEstimator()).prepare(context("Как создать ветку?"))
        assertTrue(trace.issues.isEmpty())
        assertEquals("separate-preparer", trace.model)
        assertEquals(1, calls)
    }

    @Test fun `facts survive beyond six exchanges restart and stay isolated from another chat`() {
        val properties = RagProperties("corpus", temp.resolve("memory.sqlite").toString(), "http://localhost", "test")
        var repository = SqliteConversationRepository(properties, mapper)
        val indexes = mock(IndexRepository::class.java)
        val index = mock(IndexInfo::class.java)
        `when`(index.snapshotId).thenReturn("snapshot")
        `when`(indexes.index("index")).thenReturn(index)
        val grounding = mock(GroundingService::class.java)
        `when`(grounding.answer(any(GroundingRequest::class.java) ?: GroundingRequest("Q", "index"), any(GroundingDialogue::class.java) ?: GroundingDialogue("Q", TaskMemory(), emptyList(), 0))).thenAnswer { call ->
            GroundedResult(call.getArgument(0), "snapshot", GroundedStatus.UNKNOWN, "Не знаю", "Уточните", emptyList(), emptyList(), emptyList(), null, null, null, 1, null, null, 0, emptyList())
        }
        val seen = mutableListOf<tools.jackson.databind.JsonNode>()
        val fake = object : LlmClient {
            override fun settings() = AnswerSettings(true, "deepseek-flash", 0.0, "disabled", 16000, null, "")
            override fun complete(messages: List<LlmMessage>, maxOutputTokens: Int?): LlmCompletion {
                val input = mapper.readTree(messages.last().content); seen.add(input)
                val question = input.path("user_question").asText()
                val updates = when (question) {
                    "Рабочую ветку назовём release-audit." -> listOf(change("TERMS", "working_branch", "release-audit", question))
                    "Переписывать общую историю нельзя." -> listOf(change("CONSTRAINTS", "shared_history_immutable", "Не переписывать общую историю", question))
                    else -> emptyList()
                }
                return LlmCompletion("id", "deepseek-flash", raw(updates, inventory = updates.map { item(it) }), "stop", 1, null)
            }
        }
        val preparer = LlmDialoguePreparer(fake, mapper, validator, CostEstimator())
        var service = ConversationService(repository, indexes, preparer, grounding)
        var chat = service.create(CreateConversation("A", ConversationSettings("index", historyTurns = 6)))
        val other = service.create(CreateConversation("B", ConversationSettings("index", historyTurns = 6)))
        val questions = listOf("Рабочую ветку назовём release-audit.", "Переписывать общую историю нельзя.") + (3..10).map { "Как работает операция номер $it?" }
        questions.forEachIndexed { n, q -> chat = service.send(chat.conversation.id, SendTurn("r$n", q, chat.conversation.revision)) }
        assertEquals(2, chat.memory.facts.size)
        assertEquals(3, chat.turns.last().omittedHistoryTurnCount)
        assertFalse(chat.turns.last().includedHistoryTurnIds.contains(chat.memory.facts.first().sourceTurnId))
        assertTrue(seen.last().path("memory").toString().contains("release-audit"))
        repository = SqliteConversationRepository(properties, mapper)
        service = ConversationService(repository, indexes, preparer, grounding)
        val restored = service.detail(chat.conversation.id)
        assertEquals(chat.memory, restored.memory)
        service.send(other.conversation.id, SendTurn("separate", "Как называется моя ветка?", 0))
        assertEquals(0, seen.last().path("memory").path("facts").size())
        assertTrue(service.detail(other.conversation.id).memory.facts.isEmpty())
        assertEquals(10, restored.turns.size)
    }
}
