package dev.aichallenge.local.generation

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** Один слот генерации на экземпляр приложения: лаборатория и чаты не конкурируют за GPU. */
@Component
class GenerationGate {
    private val busy = AtomicBoolean(false)

    /** Не создаёт скрытой очереди или повторов: второй одновременный запрос получает конфликт. */
    fun <T> exclusive(action: () -> T): T {
        if (!busy.compareAndSet(false, true)) throw LabBusy()
        try { return action() } finally { busy.set(false) }
    }
}
