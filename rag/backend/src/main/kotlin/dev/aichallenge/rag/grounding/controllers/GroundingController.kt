package dev.aichallenge.rag.grounding.controllers

import dev.aichallenge.rag.grounding.models.GroundingRequest
import dev.aichallenge.rag.grounding.services.GroundingService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/** Отдельный API дня 24; старые ответы и сравнения не получают новый обязательный формат. */
@RestController
@RequestMapping("/api/v1/grounded-answers")
class GroundingController(private val service: GroundingService) {
    @PostMapping fun answer(@Valid @RequestBody request: GroundingRequest) = service.answer(request)
}
