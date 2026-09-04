package com.example.aichat.task.day5.modelcomparison.llmjudge.controller;

import com.example.aichat.common.llmjudge.LlmJudgeRegistry;
import com.example.aichat.task.day5.modelcomparison.llmjudge.controller.dto.ModelComparisonJudgeRequest;
import com.example.aichat.task.day5.modelcomparison.llmjudge.model.ModelComparisonJudgeCandidate;
import com.example.aichat.task.day5.modelcomparison.llmjudge.model.ModelComparisonJudgeCommand;
import com.example.aichat.task.day5.modelcomparison.llmjudge.model.ModelComparisonJudgeResult;
import com.example.aichat.task.day5.modelcomparison.llmjudge.service.ModelComparisonJudgeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Принимает запросы на независимую LLM-оценку ответов трёх моделей. */
@RestController
@RequestMapping("/api/model-comparisons/judge")
public class ModelComparisonJudgeController {

    private final LlmJudgeRegistry judges;

    public ModelComparisonJudgeController(LlmJudgeRegistry judges) {
        this.judges = judges;
    }

    @PostMapping
    public ModelComparisonJudgeResult judge(@Valid @RequestBody ModelComparisonJudgeRequest request) {
        var judge = judges.get(
                ModelComparisonJudgeService.TYPE,
                ModelComparisonJudgeCommand.class,
                ModelComparisonJudgeResult.class
        );
        return judge.judge(new ModelComparisonJudgeCommand(
                request.profileId(),
                request.task(),
                request.candidates().stream()
                        .map(candidate -> new ModelComparisonJudgeCandidate(
                                candidate.modelId(), candidate.answer()
                        ))
                        .toList()
        ));
    }
}
