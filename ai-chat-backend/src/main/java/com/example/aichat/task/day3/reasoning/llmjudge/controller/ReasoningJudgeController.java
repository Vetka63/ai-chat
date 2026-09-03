package com.example.aichat.task.day3.reasoning.llmjudge.controller;

import com.example.aichat.common.llmjudge.LlmJudgeRegistry;
import com.example.aichat.task.day3.reasoning.llmjudge.service.ReasoningJudgeService;
import com.example.aichat.task.day3.reasoning.llmjudge.controller.dto.ReasoningJudgeRequest;
import com.example.aichat.task.day3.reasoning.llmjudge.model.ReasoningJudgeCandidate;
import com.example.aichat.task.day3.reasoning.llmjudge.model.ReasoningJudgeResult;
import com.example.aichat.task.day3.reasoning.llmjudge.model.ReasoningJudgeCommand;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter that validates and maps requests for the ReasoningJudgeController boundary. */
@RestController
@RequestMapping("/api/reasoning-experiments/judge")
public class ReasoningJudgeController {

    private final LlmJudgeRegistry judges;

    public ReasoningJudgeController(LlmJudgeRegistry judges) {
        this.judges = judges;
    }

    @PostMapping
    public ReasoningJudgeResult judge(@Valid @RequestBody ReasoningJudgeRequest request) {
        var judge = judges.get(
                ReasoningJudgeService.TYPE,
                ReasoningJudgeCommand.class,
                ReasoningJudgeResult.class
        );
        return judge.judge(new ReasoningJudgeCommand(
                request.profileId(),
                request.task(),
                request.candidates().stream()
                        .map(candidate -> new ReasoningJudgeCandidate(
                                candidate.strategy(),
                                candidate.answer()
                        ))
                        .toList()
        ));
    }
}
