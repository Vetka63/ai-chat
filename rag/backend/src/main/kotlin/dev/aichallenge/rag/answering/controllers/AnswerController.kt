package dev.aichallenge.rag.answering.controllers

import dev.aichallenge.rag.answering.models.*
import dev.aichallenge.rag.answering.services.AnswerService
import jakarta.validation.Valid
import org.springframework.core.io.ClassPathResource
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.ObjectMapper

/** REST-вход для генерации; evaluation questions обслуживаются отдельно от корпуса индекса. */
@RestController
@RequestMapping("/api/v1")
class AnswerController(private val service: AnswerService, private val mapper: ObjectMapper) {
    @GetMapping("/answer-settings") fun settings() = service.settings()
    @PostMapping("/answers") fun answer(@Valid @RequestBody request: AnswerRequest) = service.answer(request)
    @GetMapping("/evaluation/questions") fun questions(): List<ControlQuestion> = ClassPathResource("static/evaluation/day22-cases.json").inputStream.use {
        mapper.readValue(it, mapper.typeFactory.constructCollectionType(List::class.java, ControlQuestion::class.java))
    }
}
