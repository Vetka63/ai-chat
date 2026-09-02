package com.example.aichat.api;

import com.example.aichat.experiment.ReasoningExperimentService;
import com.example.aichat.experiment.ReasoningExperimentService.ReasoningExperiment;
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

    public record ReasoningExperimentRequest(
            @NotBlank(message = "Task must not be blank")
            @Size(max = 10000, message = "Task must not exceed 10000 characters")
            String task,

            @Pattern(
                    regexp = "^[a-z][a-z0-9-]{1,63}$",
                    message = "Profile id has an invalid format"
            )
            String profileId,

            @Size(max = 4, message = "No more than four reasoning strategies are allowed")
            List<
                    @Pattern(
                            regexp = "^(direct|step-by-step|meta-prompt|expert-panel)$",
                            message = "Reasoning strategy has an invalid format"
                    ) String
                    > strategies
    ) {
        public ReasoningExperimentRequest {
            strategies = strategies == null ? List.of() : List.copyOf(strategies);
        }
    }
}
