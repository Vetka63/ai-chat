package dev.aichallenge.local.generation

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** Один слот генерации на экземпляр приложения: лаборатория и чаты не конкурируют за GPU. */
@Component
class GenerationGate {
    private val busy = AtomicBoolean(false)

    /** Не создаёт скрытой очереди или повторов: второй одновременный запрос получает конфликт. */
    fun <T> exclusive(action: () -> T): T {
        return reserve().use { action() }
    }

    /** Передаёт владение слотом фоновой задаче; закрытие lease освобождает слот ровно один раз. */
    fun reserve(): AutoCloseable {
        if (!busy.compareAndSet(false, true)) throw LabBusy()
        val closed = AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) busy.set(false) }
    }
}
