package dev.aichallenge.rag

import dev.aichallenge.rag.answering.adapters.DeepSeekLlmClient
import dev.aichallenge.rag.answering.services.CostEstimator
import dev.aichallenge.rag.config.DeepSeekProperties
import dev.aichallenge.rag.taskmemory.adapters.LlmDialoguePreparer
import dev.aichallenge.rag.taskmemory.enums.MemoryLayer
import dev.aichallenge.rag.taskmemory.models.*
import dev.aichallenge.rag.taskmemory.services.MemoryPatchValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Opt-in: пять синтетических prep-вызовов; не читает реальные чаты, SQLite или runtime traces. */
@EnabledIfEnvironmentVariable(named = "RAG_RUN_LIVE_MEMORY", matches = "true")
class MemoryPreparationLiveTest {
    private val mapper = jacksonObjectMapper()
    private data class Case(val id: String, val input: DialogueContext, val verify: (PreparationTrace) -> Unit)

    @Test fun `synthetic preparation extracts renames removes and distinguishes actual from conditional situation`() {
        val scenarios = mapper.readTree(Files.readString(Path.of("../evaluation/day25-scenarios.json")))
        val collaboration = scenarios.single { it.path("id").asText() == "collaboration" }
        val questions = mutableListOf<String>().apply { for (question in collaboration.path("questions")) add(question.asText()) }
        val situationPattern = Regex(collaboration.path("memoryExpectations").single { it.path("id").asText() == "same_line_changed" }.path("pattern").asText(), RegexOption.IGNORE_CASE)
        val first = "Моя цель — подготовить выпуск приложения в отдельной ветке. Назовём рабочую ветку release-payment. В общую ветку force push запрещён. Как создать рабочую ветку и переключиться на неё?"
        val renamed = "Переименуем рабочую ветку в release-payments-v2. Общая цель и запрет force push прежние. Как переименовать локальную ветку?"
        val cancelled = "Снимаю прежний запрет на force push в общую ветку. Это изменение условий учебного сценария, команды выполнять не нужно. Как посмотреть список локальных веток?"
        val goal = MemoryFact(MemoryLayer.GOAL, "goal", "подготовить выпуск приложения в отдельной ветке", "fixture-lifecycle-1", "Моя цель — подготовить выпуск приложения в отдельной ветке.")
        val branch = MemoryFact(MemoryLayer.TERMS, "working_branch", "release-payment", "fixture-lifecycle-1", "Назовём рабочую ветку release-payment.")
        val ban = MemoryFact(MemoryLayer.CONSTRAINTS, "shared_force_ban", "В общую ветку force push запрещён", "fixture-lifecycle-1", "В общую ветку force push запрещён.")
        val renamedBranch = branch.copy(value = "release-payments-v2", sourceTurnId = "fixture-lifecycle-2", quote = "Переименуем рабочую ветку в release-payments-v2.")
        val extraction = DialogueContext("extract", first, TaskMemory(), emptyList(), 0)
        val rename = DialogueContext("rename", renamed, TaskMemory(listOf(goal, branch, ban)), listOf(DialogueItem("fixture-lifecycle-1", first, null, "Как создать новую рабочую ветку?")), 0)
        val cancellation = DialogueContext("cancel", cancelled, TaskMemory(listOf(goal, renamedBranch, ban)), rename.recent + DialogueItem("fixture-lifecycle-2", renamed, null, "Как переименовать локальную ветку?"), 0)
        // Намеренно искусственная память и шестишаговый хвост из открытого scenario.
        // Это НЕ replay реального trace; assistant=null, в модель не отправляются пользовательские чаты.
        val taskFacts = listOf(
            MemoryFact(MemoryLayer.GOAL, "goal", "работать в отдельной ветке над исправлением, затем объединить изменения с командой", "fixture-collaboration-1", "Моя цель — работать в отдельной ветке над исправлением, затем объединить изменения с командой."),
            MemoryFact(MemoryLayer.CONSTRAINTS, "shared_force_ban", "В общую ветку force push запрещён", "fixture-collaboration-1", "В общую ветку force push запрещён."),
            MemoryFact(MemoryLayer.TERMS, "working_branch", "рабочая ветка называется feature-login", "fixture-collaboration-2", "Назовём эту рабочую ветку feature-login."),
            MemoryFact(MemoryLayer.CLARIFICATIONS, "merge_target", "теперь нужно объединить ветку feature-login с основной веткой", "fixture-collaboration-7", "Теперь нужно объединить feature-login с основной веткой."),
        )
        fun prior(position: Int) = questions.take(position - 1).mapIndexed { n, q -> DialogueItem("fixture-collaboration-${n + 1}", q, null, q) }.takeLast(6)
        val currentFact = DialogueContext("current-C9", questions[8], TaskMemory(taskFacts), prior(9), 2)
        val conditional = DialogueContext("conditional-C12", questions[11], TaskMemory(taskFacts + MemoryFact(MemoryLayer.CONSTRAINTS, "shared_history_ban", "Договорились не переписывать общую историю", "fixture-collaboration-11", "Мы договорились не переписывать общую историю.")), prior(12), 5)
        fun retained(trace: PreparationTrace, input: DialogueContext, except: MemoryFact? = null) {
            input.memory.facts.filter { it != except }.forEach { require(it in trace.memory.facts) { "Изменён прежний факт/его provenance: ${it.layer}/${it.key}" } }
        }
        val cases = listOf(
            Case("extract-goal-name-ban", extraction) { trace ->
                require(trace.memory.facts.any { it.layer == MemoryLayer.GOAL && it.value.contains("выпуск", ignoreCase = true) }) { "Пропущена цель" }
                require(trace.memory.facts.any { it.layer == MemoryLayer.TERMS && it.value.contains("release-payment") }) { "Пропущено назначенное имя" }
                require(trace.memory.facts.any { it.layer == MemoryLayer.CONSTRAINTS && it.value.contains("force push", ignoreCase = true) }) { "Пропущен запрет" }
            },
            Case("rename-retain-provenance", rename) { trace ->
                val updated = trace.memory.facts.single { it.layer == branch.layer && it.key == branch.key }
                require(updated.value.contains("release-payments-v2") && updated.sourceTurnId == rename.turnId) { "Переименование не обновило прежний ключ" }
                require(trace.memory.facts.none { it.layer == MemoryLayer.TERMS && it.value.contains(Regex("release-payment(?!s-v2)")) }) { "Старое имя осталось" }
                retained(trace, rename, branch)
            },
            Case("cancel-existing-ban", cancellation) { trace ->
                require(trace.memory.facts.none { it.layer == ban.layer && it.key == ban.key }) { "Явный запрет не удалён" }
                require(trace.changes.any { it.layer == ban.layer && it.key == ban.key && it.value == null }) { "Нет удаления с текущей цитатой" }
                retained(trace, cancellation, ban)
            },
            Case("actual-C9-synthetic-history", currentFact) { trace ->
                require(trace.memory.facts.any { it.layer == MemoryLayer.CLARIFICATIONS && it.sourceTurnId == currentFact.turnId && situationPattern.containsMatchIn(it.value) }) { "Реальная ситуация C9 не сохранена в CLARIFICATIONS" }
                retained(trace, currentFact)
            },
            Case("conditional-C12-is-not-completed-merge", conditional) { trace ->
                val merged = Regex("успешн.*сли|слит|слиян.*заверш", RegexOption.IGNORE_CASE)
                val condition = Regex("после|если|когда|услов|при успешн|план", RegexOption.IGNORE_CASE)
                require(trace.changes.none { it.value != null && merged.containsMatchIn(it.value) && !condition.containsMatchIn(it.value) }) { "Условие будущего действия превращено в свершившееся слияние" }
                retained(trace, conditional)
            },
        )
        val key = System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() } ?: System.getenv("LLM_API_KEY").orEmpty()
        require(key.isNotBlank()) { "Для opt-in теста нужен серверный DEEPSEEK_API_KEY/LLM_API_KEY." }
        val properties = DeepSeekProperties(apiKey = key,
            baseUrl = System.getenv("DEEPSEEK_BASE_URL")?.takeIf { it.isNotBlank() } ?: "https://api.deepseek.com",
            model = System.getenv("DEEPSEEK_MODEL")?.takeIf { it.isNotBlank() } ?: "deepseek-flash",
            preparationModel = System.getenv("DEEPSEEK_PREPARATION_MODEL")?.takeIf { it.isNotBlank() } ?: "deepseek-v4-pro",
            preparationReasoningEffort = System.getenv("DEEPSEEK_PREPARATION_REASONING_EFFORT")?.takeIf { it.isNotBlank() } ?: "high",
            preparationTimeoutSeconds = System.getenv("DEEPSEEK_PREPARATION_TIMEOUT_SECONDS")?.toLongOrNull() ?: 180)
        val preparer = LlmDialoguePreparer(DeepSeekLlmClient(properties, mapper), mapper, MemoryPatchValidator(mapper), CostEstimator())
        val directory = Path.of("../data").toAbsolutePath().normalize()
        Files.createDirectories(directory)
        val runId = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().take(8)
        val reportPath = directory.resolve("memory-preparation-live-$runId.json")
        val records = mutableListOf<MutableMap<String, Any>>()
        val report = mutableMapOf<String, Any>("at" to Instant.now().toString(), "runId" to runId,
            "status" to "RUNNING", "callsAttempted" to 0, "maxCalls" to cases.size,
            "profile" to mapOf("model" to properties.preparationModel, "thinking" to "enabled", "reasoningEffort" to properties.preparationReasoningEffort, "technicalOutputCap" to 16384, "timeoutSeconds" to properties.preparationTimeoutSeconds),
            "note" to "Пять синтетических подготовок через отдельный production profile: только public day25-scenarios и искусственные literals. НЕ replay runtime trace. Не читает реальные чаты/SQLite. Нет RAG, judge или retry.", "cases" to records)
        fun save() = Files.writeString(reportPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        save()
        println("Live memory preparation trace: $reportPath; максимум ${cases.size} платных prep-вызовов.")
        val failures = mutableListOf<String>()
        for ((index, case) in cases.withIndex()) {
            val record = mutableMapOf<String, Any>("id" to case.id, "input" to case.input, "status" to "REQUESTED")
            records.add(record)
            report["callsAttempted"] = index + 1
            save()
            val trace = try { preparer.prepare(case.input) } catch (failure: Exception) {
                record["status"] = "ERROR"; record["errorType"] = failure.javaClass.simpleName
                failures.add("${case.id}: ${failure.javaClass.simpleName}")
                save()
                continue
            }
            record["preparation"] = trace
            record["status"] = "RECEIVED"
            save() // Исходный ответ/usage/messages сохраняются до любой проверки и при неудаче.
            try {
                require(trace.issues.isEmpty()) { "Невалидная подготовка: ${trace.issues}" }
                trace.changes.forEach { require(it.quote.isNotBlank() && case.input.question.contains(it.quote)) { "Изменение без точной текущей цитаты" } }
                trace.memory.facts.forEach { require(it in case.input.memory.facts || it.sourceTurnId == case.input.turnId && it.quote.isNotBlank() && case.input.question.contains(it.quote)) { "Неверное происхождение ${it.key}" } }
                case.verify(trace)
                record["status"] = "PASSED"
            } catch (failure: Exception) {
                record["status"] = "FAILED"; record["failure"] = failure.message.orEmpty()
                failures.add("${case.id}: ${failure.message}")
            }
            save()
            println("Memory preparation ${case.id}: ${record["status"]}; model=${trace.model}; API tokens=${trace.usage?.totalTokens ?: "unknown"}; calls=1")
        }
        report["status"] = if (failures.isEmpty()) "PASSED" else "FAILED"
        report["failures"] = failures
        save()
        assertTrue(failures.isEmpty(), "${failures.joinToString("; ")}. Все результаты сохранены: $reportPath")
    }
}
