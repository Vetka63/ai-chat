package com.example.aichat.agent;

import java.util.List;

public record AgentSummary(
        String id,
        String name,
        String description,
        int version,
        String defaultResponseMode,
        List<ResponseModeSummary> responseModes
) {
    static AgentSummary from(AgentProfile profile) {
        return new AgentSummary(
                profile.id(),
                profile.name(),
                profile.description(),
                profile.version(),
                profile.defaultResponseMode(),
                profile.responseModes().stream().map(ResponseModeSummary::from).toList()
        );
    }

    public record ResponseModeSummary(
            String id,
            String name,
            String description
    ) {
        static ResponseModeSummary from(AgentProfile.ResponseModeConfig mode) {
            return new ResponseModeSummary(mode.id(), mode.name(), mode.description());
        }
    }
}
