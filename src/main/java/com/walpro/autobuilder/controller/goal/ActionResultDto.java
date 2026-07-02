package com.walpro.autobuilder.controller.goal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * T4-AM: lightweight uniform response shape for write actions. {@code ok} is the outcome;
 * {@code message} is a short human-readable hint for the UI; optional fields are populated
 * by specific actions (e.g. inject returns the new goal ID).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionResultDto(boolean ok, String message, String goalId, Boolean busy) {
    public ActionResultDto(boolean ok, String message, String goalId) {
        this(ok, message, goalId, null);
    }
    public static ActionResultDto ok(String message) {
        return new ActionResultDto(true, message, null);
    }
    public static ActionResultDto ok(String message, String goalId) {
        return new ActionResultDto(true, message, goalId);
    }
    public static ActionResultDto fail(String message) {
        return new ActionResultDto(false, message, null);
    }
    /** T1-FD: the action was refused because a cycle holds the lock — retryable, maps to 409. */
    public static ActionResultDto busy(String message) {
        return new ActionResultDto(false, message, null, Boolean.TRUE);
    }
    public boolean isBusy() {
        return Boolean.TRUE.equals(busy);
    }
}
