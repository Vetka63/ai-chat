package com.example.aichat.common.outputpolicy.model;

/** Immutable application result produced by the OutputPolicy workflow. */
public record OutputPolicyResult(String reply, Object structuredReply) {
}
