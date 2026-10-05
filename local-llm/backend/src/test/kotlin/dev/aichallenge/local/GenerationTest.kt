package dev.aichallenge.local

import dev.aichallenge.local.config.LabProperties
import dev.aichallenge.local.generation.*
import dev.aichallenge.local.runs.RunRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path

class GenerationTest {
    @TempDir lateinit var folder: Path
    private fun repository(): RunRepository = RunRepository(LabProperties().also { it.databasePath = folder.resolve("lab.sqlite").toString() }, JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())
    private fun runtime(answer: Generation? = null): LocalLlmRuntime = object : LocalLlmRuntime {
        override fun info() = RuntimeInfo(true, "test", listOf("model"), emptyList())
        override fun generate(request: GenerateRequest) = answer ?: throw RuntimeFailure("runtime unavailable")
    }
    @Test fun `answer and metrics survive a new repository instance`() {
        val repo = repository()
        val run = GenerationService(runtime(Generation("Париж", null, "stop", 10, 3, 22.0, 1.0, 50.0)), repo).generate(GenerateRequest("model", " Столица? "))
        assertEquals(RunStatus.COMPLETED, run.status)
        assertEquals("Столица?", run.request.prompt)
        assertEquals(run, repository().find(run.id))
        assertEquals(run.id, repo.summaries().single().id)
    }
    @Test fun `failure is persisted and next request remains possible`() {
        val repo = repository(); val service = GenerationService(runtime(), repo)
        repeat(2) { assertEquals(RunStatus.FAILED, service.generate(GenerateRequest("model", "q")).status) }
        assertEquals(2, repo.summaries().size)
        assertEquals("runtime unavailable", repo.find(repo.summaries()[0].id)?.error)
    }
    @Test fun `truncated output is not labelled complete`() {
        val run = GenerationService(runtime(Generation("Частичный", null, "length", 4, 2, null, null, null)), repository()).generate(GenerateRequest("model", "q"))
        assertEquals(RunStatus.TRUNCATED, run.status)
    }
    @Test fun `restart marks unfinished request failed without generating again`() {
        val repo = repository(); repo.save(Run("pending", "2026-10-05T00:00:00Z", GenerateRequest("model", "q"), RunStatus.RUNNING))
        val recovered = repository().find("pending")!!
        assertEquals(RunStatus.FAILED, recovered.status)
        assertTrue(recovered.error!!.contains("перезапустился"))
    }
}
