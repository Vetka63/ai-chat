package com.example.aichat.common.profile.controller.dto;

import com.example.aichat.common.profile.model.ResponseModeConfig;

/** Содержит публичное описание доступного режима ответа. */
public record ResponseModeSummary(String id, String name, String description) {
    public static ResponseModeSummary from(ResponseModeConfig mode) {
        return new ResponseModeSummary(mode.id(), mode.name(), mode.description());
    }
}
