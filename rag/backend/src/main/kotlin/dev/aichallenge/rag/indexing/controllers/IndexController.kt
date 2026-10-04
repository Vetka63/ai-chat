package dev.aichallenge.rag.indexing.controllers

import dev.aichallenge.rag.indexing.ports.IndexRepository
import dev.aichallenge.rag.indexing.models.*
import dev.aichallenge.rag.indexing.services.IndexingService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

/** REST-фасад index jobs, preview, сохранённых чанков и контролируемого сравнения. */
@RestController
@RequestMapping("/api/v1")
class IndexController(private val indexing: IndexingService, private val repository: IndexRepository) {
    @PostMapping("/preview") fun preview(@Valid @RequestBody config: ChunkConfig) = indexing.preview(config)
    @PostMapping("/jobs") @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(@Valid @RequestBody config: ChunkConfig) = indexing.start(config)
    @GetMapping("/jobs") fun jobs() = repository.jobs()
    @GetMapping("/jobs/{id}") fun job(@PathVariable id: String) = repository.job(id)
    @GetMapping("/indexes") fun indexes() = repository.indexes()
    @GetMapping("/indexes/{id}/chunks") fun chunks(@PathVariable id: String) = repository.chunks(id)
    @GetMapping("/indexes/{id}/documents/{documentId}") fun document(@PathVariable id: String, @PathVariable documentId: String) = repository.document(id, documentId)
    @PostMapping("/compare") fun compare(@RequestBody request: CompareRequest) = indexing.compare(request.indexIds)
}
