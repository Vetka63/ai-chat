package dev.aichallenge.local.generation

import dev.aichallenge.local.runs.RunRepository
import dev.aichallenge.local.privateaccess.ServiceLimits
import dev.aichallenge.local.privateaccess.PrivateProperties
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** Выполняет независимый запрос: сохраняет RUNNING, вызывает runtime, сохраняет фактический исход. */
@Service
class GenerationService(private val runtime: LocalLlmRuntime, private val repository: RunRepository, private val gate: GenerationGate = GenerationGate(),
    private val limits: ServiceLimits = ServiceLimits(PrivateProperties())) {

    fun generate(request: GenerateRequest): Run {
        return gate.exclusive {
            limits.context(listOf(LlmMessage(LlmRole.USER, request.prompt)))
            limits.generation()
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
            result
        }
    }
}

class LabBusy : RuntimeException("Другой запрос ещё выполняется. Дождитесь завершения; повтор не запущен.")
