package com.walpro.autobuilder.core.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record ParticipationWindows(Map<String, ParticipationWindow> active) {

    public static ParticipationWindows empty() {
        return EMPTY;
    }

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

    public ParticipationWindows grant(ParticipationWindow window) {
        if (window == null || window.role() == null) return this;
        Map<String, ParticipationWindow> updated = new LinkedHashMap<>(active);
        updated.merge(window.role(), window, ParticipationWindow::grant);
        return new ParticipationWindows(updated);
    }

    public ParticipationWindows revoke(String role) {
        if (!active.containsKey(role)) return this;
        Map<String, ParticipationWindow> updated = new LinkedHashMap<>(active);
        updated.remove(role);
        return new ParticipationWindows(updated);
    }

    public ParticipationWindows shrink(String role) {
        ParticipationWindow w = active.get(role);
        if (w == null) return this;
        Map<String, ParticipationWindow> updated = new LinkedHashMap<>(active);
        updated.put(role, w.shrink());
        return new ParticipationWindows(updated);
    }

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

    public double totalBoost() {
        double sum = 0.0;
        for (ParticipationWindow w : active.values()) {
            if (!w.expired()) {
                sum += w.boost();
            }
        }
        return sum;
    }

    public record TickResult(ParticipationWindows windows, List<String> expired) {}

    private static final ParticipationWindows EMPTY = new ParticipationWindows(Map.of());

    public ParticipationWindows {
        active = active == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(active));
    }
}