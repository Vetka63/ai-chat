package com.example.aichat.api;

import com.example.aichat.experiment.judge.ReasoningJudgeCandidate;
import com.example.aichat.experiment.judge.ReasoningJudgeResult;
import com.example.aichat.experiment.judge.ReasoningJudgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reasoning-experiments/judge")
public class ReasoningJudgeController {

    private final ReasoningJudgeService judgeService;

    public ReasoningJudgeController(ReasoningJudgeService judgeService) {
        this.judgeService = judgeService;
    }

    @PostMapping
    public ReasoningJudgeResult judge(@Valid @RequestBody ReasoningJudgeRequest request) {
        return judgeService.judge(
                request.profileId(),
                request.task(),
                request.candidates().stream()
                        .map(candidate -> new ReasoningJudgeCandidate(
                                candidate.strategy(),
                                candidate.answer()
                        ))
                        .toList()
        );
    }

    public record ReasoningJudgeRequest(
            @NotBlank(message = "Task must not be blank")
            @Size(max = 10000, message = "Task must not exceed 10000 characters")
            String task,

            @NotBlank(message = "Profile id must not be blank")
            @Pattern(
                    regexp = "^[a-z][a-z0-9-]{1,63}$",
                    message = "Profile id has an invalid format"
            )
            String profileId,

            @Valid
            @Size(min = 2, max = 4, message = "From two to four candidate answers are required")
            List<JudgeCandidateRequest> candidates
    ) {
    }

    public record JudgeCandidateRequest(
            @NotBlank(message = "Candidate strategy must not be blank")
            @Pattern(
                    regexp = "^(direct|step-by-step|meta-prompt|expert-panel)$",
                    message = "Candidate strategy has an invalid format"
            )
            String strategy,

            @NotBlank(message = "Candidate answer must not be blank")
            @Size(max = 20000, message = "Candidate answer must not exceed 20000 characters")
            String answer
    ) {
    }
}
