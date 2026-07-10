package com.walpro.autobuilder.core.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4-BZ-4: the per-role participation state — one open {@link ParticipationWindow} per role,
 * mirroring the one-entry-per-type shape of the stored ROLE_* signals it replaces. Immutable
 * value object carried on {@link CycleContextData} so windows ride the same context lineage
 * and harness fixtures as everything else. {@link #tick} ages entries once per cycle boundary
 * and removes expired ones, returning them so T4-BO can attribute the death.
 */
public record ParticipationWindows(Map<String, ParticipationWindow> active) {

    private static final ParticipationWindows EMPTY = new ParticipationWindows(Map.of());

    public ParticipationWindows {
        active = active == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(active));
    }

    public static ParticipationWindows empty() {
        return EMPTY;
    }

    /** Not a serialized property — the record component {@code active} is the only state. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return active.isEmpty();
    }

    public boolean hasActive(String role) {
        return active.containsKey(role);
    }

    public ParticipationWindow get(String role) {
        return active.get(role);
    }

    public double boost(String role) {
        ParticipationWindow w = active.get(role);
        return w != null ? w.boost() : 0.0;
    }

    /** Absent → opened; already open → merged ({@link ParticipationWindow#grant}). */
    public ParticipationWindows grant(ParticipationWindow window) {
        if (window == null || window.role() == null) return this;
        Map<String, ParticipationWindow> updated = new LinkedHashMap<>(active);
        updated.merge(window.role(), window, ParticipationWindow::grant);
        return new ParticipationWindows(updated);
    }

    /** The window closes now (merge consumed its slot / analysis-unlock ends replanning). */
    public ParticipationWindows revoke(String role) {
        if (!active.containsKey(role)) return this;
        Map<String, ParticipationWindow> updated = new LinkedHashMap<>(active);
        updated.remove(role);
        return new ParticipationWindows(updated);
    }

    /** The engine's no-progress attenuation on the executed role's window. */
    public ParticipationWindows shrink(String role) {
        ParticipationWindow w = active.get(role);
        if (w == null) return this;
        Map<String, ParticipationWindow> updated = new LinkedHashMap<>(active);
        updated.put(role, w.shrink());
        return new ParticipationWindows(updated);
    }

    public record TickResult(ParticipationWindows windows, List<ParticipationWindow> expired) {}

    /**
     * One cycle boundary: age every window, remove the ones past their validity horizon or
     * shrunk below the survival threshold. Expired entries are returned so the lifetime
     * tracker can attribute the death as "expired" instead of the old silent decay.
     */
    public TickResult tick() {
        if (active.isEmpty()) return new TickResult(this, List.of());
        Map<String, ParticipationWindow> kept = new LinkedHashMap<>();
        List<ParticipationWindow> expired = new ArrayList<>();
        for (ParticipationWindow w : active.values()) {
            ParticipationWindow ticked = w.tick();
            if (ticked.expired()) {
                expired.add(ticked);
            } else {
                kept.put(ticked.role(), ticked);
            }
        }
        return new TickResult(new ParticipationWindows(kept), List.copyOf(expired));
    }
}
