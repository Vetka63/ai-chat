package com.example.aichat.common.profile.controller;

import com.example.aichat.common.profile.controller.dto.AgentSummary;
import com.example.aichat.common.profile.registry.AgentRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HTTP adapter that validates and maps requests for the AgentController boundary. */
@RestController
@RequestMapping("/api/profiles")
public class AgentController {

    private final AgentRegistry agentRegistry;

    public AgentController(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @GetMapping
    public List<AgentSummary> profiles() {
        return agentRegistry.all().stream()
                .map(AgentSummary::from)
                .toList();
    }
}
