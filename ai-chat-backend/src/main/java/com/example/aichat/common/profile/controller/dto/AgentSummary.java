package com.example.aichat.common.profile.controller.dto;

import com.example.aichat.common.profile.model.AgentProfile;

import java.util.List;

/** Public summary DTO for Agent metadata. */
public record AgentSummary(
        String id,
        String name,
        String description,
        int version,
        String experienceType,
        String defaultResponseMode,
        List<ResponseModeSummary> responseModes
) {
    public static AgentSummary from(AgentProfile profile) {
        return new AgentSummary(
                profile.id(),
                profile.name(),
                profile.description(),
                profile.version(),
                profile.experienceType(),
                profile.defaultResponseMode(),
                profile.responseModes().stream().map(ResponseModeSummary::from).toList()
        );
    }
}
