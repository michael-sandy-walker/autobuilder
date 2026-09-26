package com.walpro.autobuilder.core.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ParticipationWindows(Map<String, ParticipationWindow> active) {

    public ParticipationWindows {
        active = Collections.unmodifiableMap(new LinkedHashMap<>(active));
    }

    public static ParticipationWindows empty() {
        return new ParticipationWindows(Map.of());
    }

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
        return w == null ? 0.0 : w.boost();
    }

    public ParticipationWindows grant(ParticipationWindow window) {
        Map<String, ParticipationWindow> next = new LinkedHashMap<>(active);
        next.put(window.role(), window);
        return new ParticipationWindows(next);
    }

    public ParticipationWindows revoke(String role) {
        Map<String, ParticipationWindow> next = new LinkedHashMap<>(active);
        next.remove(role);
        return new ParticipationWindows(next);
    }

    public ParticipationWindows shrink(String role) {
        ParticipationWindow w = active.get(role);
        if (w == null) {
            return this;
        }
        Map<String, ParticipationWindow> next = new LinkedHashMap<>(active);
        next.put(role, w.shrink());
        return new ParticipationWindows(next);
    }

    public TickResult tick() {
        Map<String, ParticipationWindow> next = new LinkedHashMap<>();
        for (Map.Entry<String, ParticipationWindow> e : active.entrySet()) {
            ParticipationWindow ticked = e.getValue().tick();
            if (!ticked.expired()) {
                next.put(e.getKey(), ticked);
            }
        }
        return new TickResult(new ParticipationWindows(next));
    }

    public List<String> roles() {
        return List.copyOf(active.keySet());
    }

    public Optional<ParticipationWindow> strongest() {
        return active.values().stream()
                .max((a, b) -> {
                    int cmp = Double.compare(b.boost(), a.boost());
                    if (cmp != 0) {
                        return cmp;
                    }
                    return Integer.compare(
                            active.indexOf(a),
                            active.indexOf(b));
                });
    }

    private int indexOf(ParticipationWindow w) {
        int i = 0;
        for (ParticipationWindow v : active.values()) {
            if (v == w) {
                return i;
            }
            i++;
        }
        return -1;
    }

    public record TickResult(ParticipationWindows windows) {
    }
}