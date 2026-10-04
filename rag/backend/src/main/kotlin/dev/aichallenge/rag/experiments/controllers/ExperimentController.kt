package dev.aichallenge.rag.experiments.controllers

import dev.aichallenge.rag.experiments.models.ExperimentRequest
import dev.aichallenge.rag.experiments.services.ExperimentService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/** Явный запуск сравнения или preview. GET/polling не создают платные вызовы. */
@RestController
@RequestMapping("/api/v1/experiments")
class ExperimentController(private val service: ExperimentService) {
    @PostMapping("/compare") fun compare(@Valid @RequestBody request: ExperimentRequest) = service.compare(request)
}
