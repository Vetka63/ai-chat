package dev.aichallenge.local.chat

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

/** Клиент передаёт ID чата и новую реплику, но не произвольную историю или системный prompt. */
@RestController
@RequestMapping("/api/v1/chats")
class ChatController(private val service: ChatService) {
    @GetMapping fun list(): List<ChatSummary> = service.list()
    @GetMapping("/{id}") fun get(@PathVariable id: String): Chat = service.get(id)
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateChatRequest): Chat = service.create(request)
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) { service.delete(id) }
    @PostMapping("/{id}/messages")
    fun send(@PathVariable id: String, @Valid @RequestBody request: SendMessageRequest): Chat = service.send(id, request)
    @PostMapping("/{id}/messages/{requestId}/retry")
    fun retry(@PathVariable id: String, @PathVariable requestId: String): Chat = service.retry(id, requestId)
}
