package dev.aichallenge.rag.retrieval.controllers

import dev.aichallenge.rag.retrieval.models.SearchRequest
import dev.aichallenge.rag.retrieval.services.SearchService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/** Поиск возвращает доказательства из сохранённого индекса, а не свободный ответ модели. */
@RestController
@RequestMapping("/api/v1/indexes")
class SearchController(private val search: SearchService) {
    @PostMapping("/{id}/search") fun search(@PathVariable id: String, @Valid @RequestBody request: SearchRequest) = search.search(id, request)
}
