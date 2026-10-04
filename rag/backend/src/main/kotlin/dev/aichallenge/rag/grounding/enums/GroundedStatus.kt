package dev.aichallenge.rag.grounding.enums

/** ANSWERED требует точных цитат и успешной проверки их смысла; внешнюю проверку фактов это не заменяет. */
enum class GroundedStatus { ANSWERED, UNKNOWN, INVALID_EVIDENCE, ERROR }
