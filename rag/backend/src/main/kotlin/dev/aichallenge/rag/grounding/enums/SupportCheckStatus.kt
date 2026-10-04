package dev.aichallenge.rag.grounding.enums

/** Для PASSED нужен явный положительный вердикт по каждому пункту; частичного одобрения нет. */
enum class SupportCheckStatus { PASSED, REJECTED, INVALID_RESPONSE }

/** Дословная цитата может не подтверждать полный смысл утверждения или противоречить ему. */
enum class ClaimSupportVerdict { SUPPORTED, UNSUPPORTED, CONTRADICTED }
