package com.walpro.autobuilder.controller.goal;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * T4-AM: lightweight uniform response shape for write actions. {@code ok} is the outcome;
 * {@code message} is a short human-readable hint for the UI; optional fields are populated
 * by specific actions (e.g. inject returns the new goal ID).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActionResultDto(boolean ok, String message, String goalId) {
    public static ActionResultDto ok(String message) {
        return new ActionResultDto(true, message, null);
    }
    public static ActionResultDto ok(String message, String goalId) {
        return new ActionResultDto(true, message, goalId);
    }
    public static ActionResultDto fail(String message) {
        return new ActionResultDto(false, message, null);
    }
}
