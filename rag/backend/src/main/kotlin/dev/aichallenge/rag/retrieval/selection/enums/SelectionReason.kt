package dev.aichallenge.rag.retrieval.selection.enums

/** Причина решения после retrieval; исключение из-за бюджета ответа относится к следующей стадии. */
enum class SelectionReason { SELECTED, BELOW_THRESHOLD, TOP_K_LIMIT }
