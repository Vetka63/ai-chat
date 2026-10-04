package dev.aichallenge.rag

import dev.aichallenge.rag.answering.adapters.DeepSeekLlmClient
import dev.aichallenge.rag.answering.services.CostEstimator
import dev.aichallenge.rag.config.DeepSeekProperties
import dev.aichallenge.rag.grounding.adapters.ExactCitationValidator
import dev.aichallenge.rag.grounding.adapters.LlmClaimSupportValidator
import dev.aichallenge.rag.grounding.enums.GroundedStatus
import dev.aichallenge.rag.grounding.enums.SupportCheckStatus
import dev.aichallenge.rag.grounding.services.ClaimSupportPromptAssembler
import dev.aichallenge.rag.indexing.models.Chunk
import dev.aichallenge.rag.retrieval.models.SearchHit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Опциональные четыре платных вызова настоящего проверяющего адаптера; без генерации, rewrite и повторов. */
@EnabledIfEnvironmentVariable(named = "RAG_RUN_LIVE_SUPPORT", matches = "true")
class ClaimSupportLiveTest {
    private data class Case(val id: String, val source: String, val section: String, val claim: String, val quote: String, val expected: SupportCheckStatus)

    @Test fun `real checker rejects audit failures and accepts supported paraphrase`() {
        val mapper = jacksonObjectMapper()
        val key = System.getenv("DEEPSEEK_API_KEY").orEmpty()
        require(key.isNotBlank()) { "Для явно включённого live-теста нужен серверный DEEPSEEK_API_KEY." }
        val properties = DeepSeekProperties(
            apiKey = key,
            baseUrl = System.getenv("DEEPSEEK_BASE_URL")?.takeIf { it.isNotBlank() } ?: "https://api.deepseek.com",
            model = System.getenv("DEEPSEEK_MODEL")?.takeIf { it.isNotBlank() } ?: "deepseek-flash",
        )
        val checker = LlmClaimSupportValidator(DeepSeekLlmClient(properties, mapper), ClaimSupportPromptAssembler(mapper), mapper, CostEstimator())
        val cases = listOf(
            Case("add-does-not-delete", "02-git-basics/sections/recording-changes.asc", "Индексация изменённых файлов",
                "Команда git add удаляет изменённый файл из рабочего каталога.",
                "Если вы изменили файл после выполнения `git add`, вам придётся снова выполнить `git add`, чтобы проиндексировать последнюю версию файла:", SupportCheckStatus.REJECTED),
            Case("soft-needs-own-evidence", "07-git-tools/sections/reset.asc", "Шаг 2: Обновление Индекса (--mixed)",
                "Подходит --soft: этот режим останавливается до изменения индекса и сохраняет его содержимое.",
                "Если вы указали опцию `--mixed`, выполнение `reset` остановится на этом шаге.", SupportCheckStatus.REJECTED),
            Case("rebase-example-is-not-universal", "03-git-branching/sections/rebasing.asc", "Основы перебазирования",
                "Конечный результат слияния и перебазирования всегда одинаков для любых веток и изменений.",
                "Нет абсолютно никакой разницы в конечном результате между двумя показанными примерами, но перебазирование делает историю коммитов чище.", SupportCheckStatus.REJECTED),
            Case("stash-supported-paraphrase", "07-git-tools/sections/stashing-cleaning.asc", "Необычное припрятывание",
                "Чтобы git stash также сохранил созданные неотслеживаемые файлы, укажите -u или --include-untracked.",
                "Если вы укажете опцию `--include-untracked` или `-u`, Git также припрячет все неотслеживаемые файлы, которые вы создали.", SupportCheckStatus.PASSED),
        )
        val directory = Path.of("../data").toAbsolutePath().normalize()
        Files.createDirectories(directory)
        val runId = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID().toString().take(8)
        val reportPath = directory.resolve("day24-support-live-$runId.json")
        val records = mutableListOf<Map<String, Any>>()
        val report = mapOf("at" to Instant.now().toString(), "runId" to runId, "note" to "Четыре вызова настоящей проверки смысла, без retry. Цитаты взяты из локального Pro Git. Это выборка регрессий, не доказательство безошибочности модели.", "cases" to records)
        fun save() = Files.writeString(reportPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        save()
        println("Live support trace: $reportPath; максимум четыре платных вызова.")
        val failures = mutableListOf<String>()
        for (case in cases) {
            val document = Files.readString(Path.of("../corpus/progit-ru/book").resolve(case.source))
            val quoteStart = document.indexOf(case.quote)
            assertTrue(quoteStart >= 0, "Опорная цитата отсутствует в локальном корпусе: ${case.id}")
            val start = (quoteStart - 1000).coerceAtLeast(0)
            val end = (quoteStart + case.quote.length + 1000).coerceAtMost(document.length)
            val text = document.substring(start, end)
            val hit = SearchHit(1, .8, Chunk(case.id, case.source, case.source, "Pro Git", case.section, listOf(case.section), 0, start, end, 1, 1, text, "live-fixture"))
            val json = mapper.writeValueAsString(mapOf("status" to "known", "claims" to listOf(mapOf("text" to case.claim, "citations" to listOf(mapOf("chunk_id" to case.id, "quote" to case.quote))))))
            val exact = ExactCitationValidator(mapper).validate(json, "stop", listOf(hit))
            assertEquals(GroundedStatus.ANSWERED, exact.status, "Проверка дословности fixture: ${case.id}")
            val checked = try { checker.validate(exact.claims, listOf(hit)) } catch (failure: Exception) {
                records.add(mapOf("id" to case.id, "expected" to case.expected, "errorType" to failure.javaClass.simpleName))
                save()
                throw failure
            }
            records.add(mapOf("id" to case.id, "expected" to case.expected, "claim" to case.claim, "source" to case.source, "quote" to case.quote, "supportCheck" to checked))
            save()
            if (checked.status != case.expected) failures.add("${case.id}: ожидался ${case.expected}, получен ${checked.status}")
        }
        assertTrue(failures.isEmpty(), "${failures.joinToString("; ")}. Все результаты сохранены: $reportPath")
    }
}
