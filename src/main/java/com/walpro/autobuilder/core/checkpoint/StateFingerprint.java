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
 * red light and a diagnosis. The components are the same three the checkpoint moves — the output
 * tree, the goal tree, and each registered holder's captured state.
 *
 * <p>Comparison is by structure, not by identity. Goal ids are freshly minted UUIDs, so a goal the
 * loop re-creates during a replay is a different id for the same work; comparing on ids would
 * report divergence on every replay and prove nothing. {@link StateFingerprintService} canonicalises
 * ids to their position in the goal tree before digesting.
 */
public record StateFingerprint(String tree, String goals, Map<String, String> holders) {

    public StateFingerprint {
        holders = holders == null ? Map.of() : Map.copyOf(holders);
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
        if (!Objects.equals(goals, other.goals)) out.add("goals");
        Set<String> keys = new TreeSet<>(holders.keySet());
        keys.addAll(other.holders().keySet());
        for (String key : keys) {
            if (!Objects.equals(holders.get(key), other.holders().get(key))) {
                out.add("holder:" + key);
            }
        }
        return out;
    }

    /** True when every component matches. */
    public boolean matches(StateFingerprint other) {
        return divergencesFrom(other).isEmpty();
    }

    /** A one-line rendering for logs and behaviour events. */
    public String readout() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("tree", shorten(tree));
        all.put("goals", shorten(goals));
        holders.forEach((k, v) -> all.put(k, shorten(v)));
        StringBuilder sb = new StringBuilder();
        all.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : " ").append(k).append('=').append(v));
        return sb.toString();
    }

    private static String shorten(String digest) {
        return digest == null ? "-" : digest.substring(0, Math.min(8, digest.length()));
    }
}
