package com.walpro.autobuilder.core.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * T4-BZ-3: the typed request channel — the carrier for the request-family signal types
 * (REPLAN_REQUESTED, DIVERGENCE_REQUEST, ... plus the two expiring staleness carriers
 * MISSING_SYMBOL and PLANNING_THROTTLED). One open {@link ChannelRequest} per kind, mirroring
 * the one-entry-per-type shape of the stored signal map it replaces; re-issuing raises urgency.
 *
 * Immutable value object carried on {@link CycleContextData} so requests ride the same
 * context lineage, subgoal save/restore and harness fixtures as everything else. Consumption
 * is the addressed mechanism's read ({@link #consume}); {@link #tick} ages entries once per
 * cycle boundary and removes expired ones, returning them so T4-BO can attribute the ending.
 */
public record RequestChannel(
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(keyUsing = SignalTypeKey.Serializer.class)
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(keyUsing = SignalTypeKey.Deserializer.class)
        Map<SignalType, ChannelRequest> open) {

    private static final RequestChannel EMPTY = new RequestChannel(Map.of());

    public RequestChannel {
        open = open == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(open));
    }

    public static RequestChannel empty() {
        return EMPTY;
    }

    /** Not a serialized property — the record component {@code open} is the only state. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return open.isEmpty();
    }

    public boolean hasOpen(SignalType kind) {
        return open.containsKey(kind);
    }

    public ChannelRequest peek(SignalType kind) {
        return open.get(kind);
    }

    public double urgency(SignalType kind) {
        ChannelRequest r = open.get(kind);
        return r != null ? r.urgency() : 0.0;
    }

    /** Absent → opened; already open → urgency raised ({@link ChannelRequest#raise}). */
    public RequestChannel issue(ChannelRequest request) {
        if (request == null || request.kind() == null) return this;
        Map<SignalType, ChannelRequest> updated = new LinkedHashMap<>(open);
        updated.merge(request.kind(), request, ChannelRequest::raise);
        return new RequestChannel(updated);
    }

    /** The addressed mechanism has acted on the request — it leaves the channel. */
    public RequestChannel consume(SignalType kind) {
        if (!open.containsKey(kind)) return this;
        Map<SignalType, ChannelRequest> updated = new LinkedHashMap<>(open);
        updated.remove(kind);
        return new RequestChannel(updated);
    }

    /** Requests of the given kinds leave the channel (subgoal scope switch — T2-BK/I6). */
    public RequestChannel withoutKinds(Set<SignalType> kinds) {
        if (open.isEmpty() || kinds == null || kinds.isEmpty()) return this;
        Map<SignalType, ChannelRequest> updated = new LinkedHashMap<>(open);
        updated.keySet().removeAll(kinds);
        return updated.size() == open.size() ? this : new RequestChannel(updated);
    }

    /** The open requests of the given kinds (subgoal-scoped persistence save — T2-BK/I6). */
    public List<ChannelRequest> ofKinds(Set<SignalType> kinds) {
        if (open.isEmpty() || kinds == null || kinds.isEmpty()) return List.of();
        List<ChannelRequest> out = new ArrayList<>();
        for (ChannelRequest r : open.values()) {
            if (kinds.contains(r.kind())) out.add(r);
        }
        return out;
    }

    public record TickResult(RequestChannel channel, List<ChannelRequest> expired) {}

    /**
     * One cycle boundary: age every open request, remove the ones past their validity horizon
     * or resolved early ({@code resolvedEarly} — e.g. a MISSING_SYMBOL whose symbol now has a
     * declaration; null = no early resolution). Expired entries are returned so the lifetime
     * tracker can attribute the ending as "expired" instead of the old silent decay.
     */
    public TickResult tick(Predicate<ChannelRequest> resolvedEarly) {
        if (open.isEmpty()) return new TickResult(this, List.of());
        Map<SignalType, ChannelRequest> kept = new LinkedHashMap<>();
        List<ChannelRequest> expired = new ArrayList<>();
        for (ChannelRequest r : open.values()) {
            ChannelRequest ticked = r.tick();
            if (ticked.expired() || (resolvedEarly != null && resolvedEarly.test(ticked))) {
                expired.add(ticked);
            } else {
                kept.put(ticked.kind(), ticked);
            }
        }
        return new TickResult(new RequestChannel(kept), List.copyOf(expired));
    }
}
