package com.example.aichat.config;

import com.example.aichat.enums.Mode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app.chat")
public record ChatProperties(
        Mode mode,
        String baseUrl,
        String apiKey,
        String model,
        String agentProfilesPattern,
        String defaultAgentId,
        boolean logPayloads,
        List<String> allowedOrigins
) {
}
