package com.example.aichat.task.day2.responseformat.outputpolicy.dto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Validated structured payload for the Answer output format. */
public record AnswerPayload(String answer) {
    private static final Logger log = LoggerFactory.getLogger(AnswerPayload.class);
    private static final int MAX_ANSWER_LENGTH = 500;
    private static final String TRUNCATION_SUFFIX = "...";

    public AnswerPayload {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("answer must not be blank");
        }
        answer = answer.trim();
        if (answer.length() > MAX_ANSWER_LENGTH) {
            log.info(
                    "Truncating answer-json output: originalLength={}, maxLength={}",
                    answer.length(),
                    MAX_ANSWER_LENGTH
            );
            answer = answer.substring(
                    0,
                    MAX_ANSWER_LENGTH - TRUNCATION_SUFFIX.length()
            ).stripTrailing() + TRUNCATION_SUFFIX;
        }
    }
}
