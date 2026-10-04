package dev.aichallenge.rag.experiments.enums

/** Независимые факторы эксперимента: меняется запрос поиска и/или отбор, не системный промпт ответа. */
enum class RetrievalMode(val rewrite: Boolean, val filter: Boolean) {
    RAW(false, false), FILTERED(false, true), REWRITE(true, false), REWRITE_FILTERED(true, true)
}
/** NO_CONTEXT не означает доказанную нерелевантность: только отсутствие кандидатов после выбранного отбора. */
enum class ExperimentStatus { ANSWERED, RETRIEVED, NO_CONTEXT, ERROR }
