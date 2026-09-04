package com.example.aichat.task.day5.modelcomparison.controller;

import com.example.aichat.task.day5.modelcomparison.controller.dto.ModelComparisonRequest;
import com.example.aichat.task.day5.modelcomparison.model.ModelComparisonExperiment;
import com.example.aichat.task.day5.modelcomparison.service.ModelComparisonService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Принимает и проверяет HTTP-запросы эксперимента с версиями моделей. */
@RestController
@RequestMapping("/api/model-comparisons")
public class ModelComparisonController {

    private final ModelComparisonService comparisonService;

    public ModelComparisonController(ModelComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    @PostMapping
    public ModelComparisonExperiment run(@Valid @RequestBody ModelComparisonRequest request) {
        return comparisonService.run(request.profileId(), request.task());
    }
}
