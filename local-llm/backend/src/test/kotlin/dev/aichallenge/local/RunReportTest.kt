package dev.aichallenge.local

import dev.aichallenge.local.generation.*
import dev.aichallenge.local.runs.toMarkdown
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RunReportTest {
    @Test fun `report keeps saved prompt answer settings and exact metrics`() {
        val run = Run("sample", "2026-10-05T11:49:00Z", GenerateRequest("qwen3:8b", "Мой запрос"),
            RunStatus.COMPLETED, Generation("Реальный ответ", null, "stop", 100, 117, 1344.1, 1.2, 107.1), 1355)
        val report = run.toMarkdown()
        assertTrue(report.contains("Мой запрос"))
        assertTrue(report.contains("Реальный ответ"))
        assertTrue(report.contains("Максимум выходных токенов: не задан приложением"))
        assertTrue(report.contains("Output tokens: 117"))
        assertTrue(report.contains("Время backend, мс: 1355"))
        assertTrue(report.contains("Причина завершения: stop"))
        val failed = run.copy(status = RunStatus.FAILED, generation = null, error = "Нет ответа")
        assertTrue(failed.toMarkdown().contains("Нет ответа"))
        assertFalse(failed.toMarkdown().contains("Реальный ответ"))
    }
}
