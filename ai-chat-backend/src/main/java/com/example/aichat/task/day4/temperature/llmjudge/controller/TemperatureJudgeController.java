package com.example.aichat.task.day4.temperature.llmjudge.controller;

import com.example.aichat.common.llmjudge.LlmJudgeRegistry;
import com.example.aichat.task.day4.temperature.llmjudge.controller.dto.TemperatureJudgeRequest;
import com.example.aichat.task.day4.temperature.llmjudge.model.TemperatureJudgeCandidate;
import com.example.aichat.task.day4.temperature.llmjudge.model.TemperatureJudgeCommand;
import com.example.aichat.task.day4.temperature.llmjudge.model.TemperatureJudgeResult;
import com.example.aichat.task.day4.temperature.llmjudge.service.TemperatureJudgeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Принимает HTTP-запросы на автоматическое сравнение температурных вариантов. */
@RestController
@RequestMapping("/api/temperature-experiments/judge")
public class TemperatureJudgeController {

    private final LlmJudgeRegistry judges;

    public TemperatureJudgeController(LlmJudgeRegistry judges) {
        this.judges = judges;
    }

    @PostMapping
    public TemperatureJudgeResult judge(@Valid @RequestBody TemperatureJudgeRequest request) {
        var judge = judges.get(
                TemperatureJudgeService.TYPE,
                TemperatureJudgeCommand.class,
                TemperatureJudgeResult.class
        );
        return judge.judge(new TemperatureJudgeCommand(
                request.profileId(),
                request.task(),
                request.candidates().stream()
                        .map(candidate -> new TemperatureJudgeCandidate(candidate.variantId(), candidate.answer()))
                        .toList()
        ));
    }
}
