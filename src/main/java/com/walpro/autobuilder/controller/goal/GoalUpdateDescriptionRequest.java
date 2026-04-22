package com.walpro.autobuilder.controller.goal;

/** T4-AM: request body for {@code PATCH /autobuilder/goals/{id}} — in-run description edit. */
public record GoalUpdateDescriptionRequest(String description) {}
