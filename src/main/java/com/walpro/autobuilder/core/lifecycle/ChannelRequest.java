package com.walpro.autobuilder.core.lifecycle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * T4-BZ-3: one open request on the {@link RequestChannel} — a message from one mechanism to
 * another. Replaces the stored+decayed Signal representation for the request-family types:
 * urgency is the strength the issuer stated (no decay arithmetic mid-life), re-issuing RAISES
 * urgency (the honest form of {@code Signal.reinforce}), and lifetime is either
 * consume-on-read by the addressed mechanism or a stated expiry horizon
 * ({@code validForCycles}) equal to the decay-implied lifetime the type had before — an
 * unconsumed request dies at the same age it died under decay, but VISIBLY (T4-BO "expired"
 * instead of silent decay).
 */
public record ChannelRequest(
        SignalType kind,
        double urgency,
        String source,
        String subGoalId,
        int age,
        Integer validForCycles,
        Map<String, Object> meta
) {
    /** Q-c: the accumulation cap, 1:1 with the deleted stored-signal MAX_STRENGTH (T2-CG: 3.0 —
     *  chosen when ROLE_EVOLUTION reached 8.55 over 9 cycles and drowned out its siblings). */
    public static final double MAX_URGENCY = 3.0;

    /** The survival threshold the horizon formula reproduces (the deleted stored map's 0.05). */
    private static final double SURVIVAL_THRESHOLD = 0.05;

    public static ChannelRequest of(SignalType kind, double urgency, String source) {
        return new ChannelRequest(kind, Math.min(urgency, MAX_URGENCY), source, null, 0, null, Map.of());
    }

    public static ChannelRequest of(SignalType kind, double urgency, String source, Integer validForCycles) {
        return new ChannelRequest(kind, Math.min(urgency, MAX_URGENCY), source, null, 0, validForCycles, Map.of());
    }

    /**
     * Migration pin: the expiry horizon implied by a legacy (strength, decayPerCycle) pair —
     * the number of ticks a stored signal survived (first tick ages without decaying, each
     * later tick subtracts {@code decayPerCycle}, death below 0.05). Computing it here instead
     * of hand-copying per site keeps the 1:1 equivalence auditable. {@code decayPerCycle == 0}
     * meant "never decays" → null (open until consumed).
     */
    public static Integer horizonFromDecay(double strength, double decayPerCycle) {
        if (decayPerCycle <= 0.0) return null;
        return (int) Math.ceil((strength - SURVIVAL_THRESHOLD) / decayPerCycle - 1e-9);
    }

    public ChannelRequest withSubGoalId(String id) {
        return new ChannelRequest(kind, urgency, source, id, age, validForCycles, meta);
    }

    public ChannelRequest withMeta(String key, Object value) {
        Map<String, Object> updated = new HashMap<>(meta != null ? meta : Map.of());
        updated.put(key, value);
        return new ChannelRequest(kind, urgency, source, subGoalId, age, validForCycles, Map.copyOf(updated));
    }

    /**
     * Re-issue raises urgency — mirrors {@code Signal.reinforce} exactly (additive, capped,
     * age reset to the newest issue, sources merged) because the proposer's accumulation
     * thresholds (repetition ≥2.0, plateau ≥3.0) only ever fire via the additive raise.
     * The longest stated validity wins (null = until consumed beats any horizon).
     */
    public ChannelRequest raise(ChannelRequest other) {
        if (!kind.equals(other.kind)) return this;
        Integer validity = (validForCycles == null || other.validForCycles == null)
                ? null
                : Math.max(validForCycles, other.validForCycles);
        return new ChannelRequest(
                kind,
                Math.min(urgency + other.urgency, MAX_URGENCY),
                source,
                subGoalId != null ? subGoalId : other.subGoalId,
                Math.min(age, other.age),
                validity,
                mergeMeta(meta, other.meta, source, other.source)
        );
    }

    /** One cycle boundary passed. Age is the only thing that moves — urgency never decays. */
    public ChannelRequest tick() {
        return new ChannelRequest(kind, urgency, source, subGoalId, age + 1, validForCycles, meta);
    }

    /** Not a serialized property — derived from {@code age} and {@code validForCycles}. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean expired() {
        return validForCycles != null && age > validForCycles;
    }

    private static Map<String, Object> mergeMeta(Map<String, Object> a, Map<String, Object> b,
                                                 String sourceA, String sourceB) {
        Map<String, Object> merged = new HashMap<>(a != null ? a : Map.of());
        if (b != null && !b.isEmpty()) {
            for (Map.Entry<String, Object> e : b.entrySet()) {
                if ("sources".equals(e.getKey())) continue;
                merged.put(e.getKey(), e.getValue());
            }
        }
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        addSources(sources, a);
        addSources(sources, b);
        if (sourceA != null && !sourceA.isBlank()) sources.add(sourceA);
        if (sourceB != null && !sourceB.isBlank()) sources.add(sourceB);
        merged.put("sources", new ArrayList<>(sources));
        return Map.copyOf(merged);
    }

    private static void addSources(LinkedHashSet<String> out, Map<String, Object> meta) {
        if (meta == null) return;
        Object v = meta.get("sources");
        if (v instanceof List<?> list) {
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
        } else if (v instanceof String s && !s.isBlank()) {
            out.add(s);
        }
    }
}
