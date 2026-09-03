package com.example.aichat.common.inputpolicy.model;

import com.example.aichat.common.enums.HistoryRole;

/** Представляет одно проверенное сообщение из истории диалога. */
public record InputHistoryMessage(HistoryRole role, String content) {
}
