package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

/**
 * T5-BO: the goal tree was just made durable ({@code GoalPlannerService.persist()}, the one chokepoint
 * every tree change passes through). Published so the live goal view can redraw at once: before this,
 * the view heard of a tree change only at the end of a cycle or after its own edit, so a root goal set
 * on an adopted project stayed invisible until the first cycle ended, minutes later (2026-11-08).
 *
 * @param context the cycle the change happened in, or {@code null} outside a cycle (a goal set by request)
 */
public record GoalTreePersistedEvent(CycleContextData context) {
}
