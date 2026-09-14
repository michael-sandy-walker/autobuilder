package com.walpro.autobuilder.core.checkpoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * T4-DQ decision 4: what "the state at cycle N" IS, reduced to something two points in time can be
 * compared on.
 *
 * <p>Deliberately per-component rather than one number. A single digest answers "did anything
 * differ" and nothing else; these answer WHICH part differed, which is the difference between a
 * red light and a diagnosis. The first real field divergence made the point: a coarse {@code goals}
 * digest said the goal tree did not match and left no way to tell a changed status from a moved
 * pointer from a reworded description. Goals are per-goal now, and the current-subgoal pointer is
 * its own component. The components are the same things the checkpoint moves — the output tree,
 * the goal tree, each registered holder's captured state, and, since 2026-09-14, the carried cycle
 * context, which is the one piece of state the next tick reads that is none of the other three.
 * Adding a component to the checkpoint without adding it here is how this stopped describing the
 * state once already: the context shipped as capture and was unverified for a session.
 *
 * <p>Comparison is by structure, not by identity. Goal ids are freshly minted UUIDs, so a goal the
 * loop re-creates during a replay is a different id for the same work; comparing on ids would
 * report divergence on every replay and prove nothing. {@link StateFingerprintService} canonicalises
 * ids to their position in the goal tree before digesting.
 */
public record StateFingerprint(String tree, String pointer, Map<String, String> goals,
                               Map<String, String> holders,
                               /**
                                * Digest of the carried cycle context as it serialises — the fifth
                                * state component, added 2026-09-14 with the context itself. Null on
                                * a checkpoint written before contexts were captured, and null on a
                                * fingerprint taken without one.
                                */
                               String context) {

    public StateFingerprint {
        goals = goals == null ? Map.of() : Map.copyOf(goals);
        holders = holders == null ? Map.of() : Map.copyOf(holders);
    }

    /** A fingerprint of the four original components, for callers that hold no cycle context. */
    public StateFingerprint(String tree, String pointer, Map<String, String> goals,
                            Map<String, String> holders) {
        this(tree, pointer, goals, holders, null);
    }

    /**
     * The components that differ between this fingerprint and {@code other}, named. Empty means
     * the two states are identical in every part the checkpoint claims to move — which is the
     * round-trip property stated as a predicate rather than as an assertion of good intent.
     */
    public List<String> divergencesFrom(StateFingerprint other) {
        List<String> out = new ArrayList<>();
        if (other == null) return List.of("no fingerprint to compare against");
        if (!Objects.equals(tree, other.tree)) out.add("tree");
        if (!Objects.equals(pointer, other.pointer)) {
            out.add("pointer(now=" + pointer + " then=" + other.pointer() + ")");
        }
        for (String key : union(goals.keySet(), other.goals().keySet())) {
            if (!Objects.equals(goals.get(key), other.goals().get(key))) {
                String where = !goals.containsKey(key) ? " gone-now"
                        : !other.goals().containsKey(key) ? " new-now" : "";
                out.add("goal[" + key + "]" + where);
            }
        }
        for (String key : union(holders.keySet(), other.holders().keySet())) {
            if (!Objects.equals(holders.get(key), other.holders().get(key))) {
                out.add("holder:" + key);
            }
        }
        // Compared only when BOTH sides carry one. A null on the recorded side means the point
        // predates context fingerprinting; a null on this side means the caller took a fingerprint
        // without a context. Neither is a mismatch, and both are reported by the rewind itself,
        // which knows whether a context was expected — reporting them here as well would say the
        // same thing twice and make every pre-2026-09-14 point restore dirty for no defect.
        if (context != null && other.context() != null && !context.equals(other.context())) {
            out.add("context");
        }
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> keys = new TreeSet<>(a);
        keys.addAll(b);
        return keys;
    }

    /** True when every component matches. */
    public boolean matches(StateFingerprint other) {
        return divergencesFrom(other).isEmpty();
    }

    /** A one-line rendering for logs and behaviour events. */
    public String readout() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("tree", shorten(tree));
        all.put("pointer", pointer == null ? "-" : pointer);
        all.put("goals", String.valueOf(goals.size()));
        all.put("context", shorten(context));
        holders.forEach((k, v) -> all.put(k, shorten(v)));
        StringBuilder sb = new StringBuilder();
        all.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : " ").append(k).append('=').append(v));
        return sb.toString();
    }

    private static String shorten(String digest) {
        return digest == null ? "-" : digest.substring(0, Math.min(8, digest.length()));
    }
}
