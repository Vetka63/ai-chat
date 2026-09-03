package com.example.aichat.common.outputpolicy.model;

/** Содержит отображаемый и, при наличии, структурированный результат output-policy. */
public record OutputPolicyResult(String reply, Object structuredReply) {
}
