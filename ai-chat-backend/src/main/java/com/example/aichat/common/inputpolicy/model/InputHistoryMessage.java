package com.example.aichat.common.inputpolicy.model;

import com.example.aichat.common.enums.HistoryRole;

/** Immutable message model used at the InputHistory boundary. */
public record InputHistoryMessage(HistoryRole role, String content) {
}
