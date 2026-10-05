package dev.aichallenge.local.generation

import dev.aichallenge.local.runs.RunRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Выполняет независимый запрос: сохраняет RUNNING, вызывает runtime, сохраняет фактический исход. */
@Service
class GenerationService(private val runtime: LocalLlmRuntime, private val repository: RunRepository) {
    private val running = AtomicBoolean(false)

    fun generate(request: GenerateRequest): Run {
        if (!running.compareAndSet(false, true)) throw LabBusy()
        try {
            val initial = Run(UUID.randomUUID().toString(), Instant.now().toString(), request.copy(prompt = request.prompt.trim()), RunStatus.RUNNING)
            repository.save(initial)
            val start = System.nanoTime()
            val result = try {
                val generation = runtime.generate(initial.request)
                initial.copy(status = if (generation.doneReason == "length") RunStatus.TRUNCATED else RunStatus.COMPLETED,
                    generation = generation, elapsedMilliseconds = (System.nanoTime() - start) / 1_000_000)
            } catch (e: RuntimeFailure) {
                initial.copy(status = RunStatus.FAILED, error = e.message, elapsedMilliseconds = (System.nanoTime() - start) / 1_000_000)
            } catch (e: InvalidModel) {
                initial.copy(status = RunStatus.FAILED, error = e.message, elapsedMilliseconds = (System.nanoTime() - start) / 1_000_000)
            }
            repository.save(result)
            return result
        } finally { running.set(false) }
    }
}

class LabBusy : RuntimeException("Другой запрос ещё выполняется. Дождитесь завершения; повтор не запущен.")
