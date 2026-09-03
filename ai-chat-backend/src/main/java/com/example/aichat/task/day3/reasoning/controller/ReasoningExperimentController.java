package com.example.aichat.task.day3.reasoning.controller;

import com.example.aichat.task.day3.reasoning.service.ReasoningExperimentService;
import com.example.aichat.task.day3.reasoning.controller.dto.ReasoningExperimentRequest;
import com.example.aichat.task.day3.reasoning.model.ReasoningExperiment;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter that validates and maps requests for the ReasoningExperimentController boundary. */
@RestController
@RequestMapping("/api/reasoning-experiments")
public class ReasoningExperimentController {

    private final ReasoningExperimentService experimentService;

    public ReasoningExperimentController(ReasoningExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @PostMapping
    public ReasoningExperiment run(@Valid @RequestBody ReasoningExperimentRequest request) {
        return experimentService.run(request.profileId(), request.task(), request.strategies());
    }
}
