package com.example.aichat.task.day4.temperature.controller;

import com.example.aichat.task.day4.temperature.controller.dto.TemperatureExperimentRequest;
import com.example.aichat.task.day4.temperature.model.TemperatureExperiment;
import com.example.aichat.task.day4.temperature.service.TemperatureExperimentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Принимает и проверяет HTTP-запросы температурного эксперимента Дня 4. */
@RestController
@RequestMapping("/api/temperature-experiments")
public class TemperatureExperimentController {

    private final TemperatureExperimentService experimentService;

    public TemperatureExperimentController(TemperatureExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    @PostMapping
    public TemperatureExperiment run(@Valid @RequestBody TemperatureExperimentRequest request) {
        return experimentService.run(request.profileId(), request.task());
    }
}
