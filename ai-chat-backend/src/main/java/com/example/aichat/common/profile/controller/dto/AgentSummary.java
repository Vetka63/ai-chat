package com.example.aichat.common.profile.controller.dto;

import com.example.aichat.common.profile.model.AgentProfile;

import java.util.List;

/** Содержит публичные метаданные профиля без системного промпта и секретов. */
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
