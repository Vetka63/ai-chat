package com.example.aichat.common.profile.controller.dto;

import com.example.aichat.common.profile.model.ResponseModeConfig;

/** Public summary DTO for ResponseMode metadata. */
public record ResponseModeSummary(String id, String name, String description) {
    public static ResponseModeSummary from(ResponseModeConfig mode) {
        return new ResponseModeSummary(mode.id(), mode.name(), mode.description());
    }
}
