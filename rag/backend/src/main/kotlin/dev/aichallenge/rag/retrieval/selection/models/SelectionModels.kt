package dev.aichallenge.rag.retrieval.selection.models

import dev.aichallenge.rag.retrieval.models.SearchHit
import dev.aichallenge.rag.retrieval.selection.enums.SelectionReason

/** Решение по каждому кандидату, включая исходный score, rank и метаданные. */
data class CandidateDecision(val hit: SearchHit, val reason: SelectionReason)
/** Выбранные фрагменты и полный журнал отбора; similarity не переименовывается в confidence. */
data class CandidateSelection(val selected: List<SearchHit>, val decisions: List<CandidateDecision>)
