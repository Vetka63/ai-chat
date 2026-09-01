package com.example.aichat.api;

import com.example.aichat.agent.AgentRegistry;
import com.example.aichat.agent.AgentSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/profiles")
public class AgentController {

    private final AgentRegistry agentRegistry;

    public AgentController(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    @GetMapping
    public List<AgentSummary> profiles() {
        return agentRegistry.listPublic();
    }
}
