package dev.aichallenge.rag.conversations.controllers

import dev.aichallenge.rag.conversations.models.*
import dev.aichallenge.rag.conversations.services.ConversationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

/** Отдельный API дня 25; одноразовые лаборатории дней 21–24 остаются прежними. */
@RestController
@RequestMapping("/api/v1/conversations")
class ConversationController(private val service: ConversationService) {
    @GetMapping fun list() = service.list()
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@Valid @RequestBody request: CreateConversation) = service.create(request)
    @GetMapping("/{id}") fun detail(@PathVariable id: String) = service.detail(id)
    @PostMapping("/{id}/turns") fun send(@PathVariable id: String, @Valid @RequestBody request: SendTurn) = service.send(id, request)
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) fun delete(@PathVariable id: String) = service.delete(id)
}
