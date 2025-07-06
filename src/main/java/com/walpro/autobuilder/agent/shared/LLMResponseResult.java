package com.walpro.autobuilder.agent.shared;

public record LLMResponseResult<T>(
        boolean success,
        T outcome,
        String rawContent,
        String failureReason
) {
    public static <T> LLMResponseResult<T> success(T outcome, String rawContent) {
        return new LLMResponseResult<>(true, outcome, rawContent, null);
    }

    public static <T> LLMResponseResult<T> failure(String rawContent, String failureReason) {
        return new LLMResponseResult<>(false, null, rawContent, failureReason);
    }
}
