package dev.aichallenge.rag.grounding.services

import dev.aichallenge.rag.retrieval.models.SearchHit

/** Короткие уникальные адреса evidence в одном запросе; не меняют ID чанков или snapshot. */
object EvidenceCatalog {
    data class Entry(val id: String, val hit: SearchHit, val passage: EvidencePassages.Passage)

    /** Один порядок используется и при построении промпта, и при разрешении ответа модели. */
    fun entries(included: List<SearchHit>): List<Entry> = included.flatMap { hit ->
        EvidencePassages.split(hit.chunk.text).map { hit to it }
    }.mapIndexed { index, (hit, passage) -> Entry("E${index + 1}", hit, passage) }

    /** Модель выбирает evidence_id, а не перепечатывает hash чанка и числовой адрес абзаца. */
    fun payload(included: List<SearchHit>) = entries(included).map { entry -> mapOf(
        "evidence_id" to entry.id, "section" to entry.hit.chunk.section, "text" to entry.passage.text,
    ) }
}
