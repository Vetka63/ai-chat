package dev.aichallenge.rag.documents.ports

import dev.aichallenge.rag.documents.models.Document

/** Преобразует доверенный локальный текст в документ; новые форматы не меняют индексатор. */
interface DocumentLoader {
    fun load(source: String, raw: String, chapter: String, sourceUrl: String): Document
}
