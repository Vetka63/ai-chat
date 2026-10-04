package dev.aichallenge.rag.documents.controllers

import dev.aichallenge.rag.documents.services.CorpusService
import org.springframework.web.bind.annotation.*

/** Публикует только подготовленный локальный корпус; произвольных URL и путей API не принимает. */
@RestController
@RequestMapping("/api/v1")
class DocumentController(private val corpus: CorpusService) {
    @GetMapping("/corpus") fun corpus() = corpus.info()
    @GetMapping("/documents") fun documents() = corpus.list()
    @GetMapping("/documents/{id}") fun document(@PathVariable id: String) = corpus.document(id)
}
