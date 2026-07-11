package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery;
import com.walpro.autobuilder.core.build.TestRunGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * T4-BT: required-vs-extra subgoal classification by criteria-traceability to the root spec —
 * the evidence-based refinement of the T2-EI D1 origin stamp (creation-time position/role).
 *
 * <p>The origin stamp mis-bins in both directions: an unrequested unit-test subgoal created at
 * top level is stamped REQUIRED and becomes a fatal root blocker (the T2-EJ field-run-1 failure:
 * the spec named no tests, yet the test subgoal's failure doomed the root); a legitimately
 * spec-named feature added late/nested is stamped EXTRA and its failure silently absorbed.
 * Traceability reads the evidence instead: what does this subgoal's text trace to in the spec?
 *
 * <p>Deliberately narrow, evidence-only rules (a wrong EXTRA flip risks absorbing required work
 * at closure — the false-completion direction — so each rule must be individually defensible):
 * <ul>
 *   <li><b>EXTRA</b> — the subgoal is test-flavored while the spec never asks for tests
 *       ({@link TestRunGate#specRequiresTests}: the existing single home for that question);</li>
 *   <li><b>REQUIRED</b> — the subgoal names an endpoint the spec declares (verb-qualified match,
 *       or a bare path equal to a declared path — reuses {@link EndpointSurfaceQuery}'s
 *       normalization so spec and subgoal parse identically);</li>
 *   <li><b>UNKNOWN</b> — no evidence either way; callers fall back to the origin stamp.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpecTraceability {

    public enum Binding { REQUIRED, EXTRA, UNKNOWN }

    /** Word-bounded test mention, mirroring {@link TestRunGate}'s generic notion of a test. */
    private static final Pattern TEST_FLAVORED = Pattern.compile("(?i)\\btests?\\b");

    /** Bare path mention (no verb needed), e.g. {@code the /loans endpoint}. */
    private static final Pattern BARE_PATH = Pattern.compile("(^|\\s)(/[A-Za-z0-9_\\-{}/.]+)");

    private final EndpointSurfaceQuery endpointSurfaceQuery;

    /**
     * Classifies the subgoal against the root spec. UNKNOWN whenever the evidence is absent or
     * ambiguous — never guesses.
     */
    public Binding classify(String specText, GoalEntity subgoal) {
        if (specText == null || specText.isBlank() || subgoal == null) return Binding.UNKNOWN;
        String text = subgoalText(subgoal);
        if (text.isBlank()) return Binding.UNKNOWN;

        if (TEST_FLAVORED.matcher(text).find() && !TestRunGate.specRequiresTests(specText)) {
            return Binding.EXTRA;
        }

        Set<EndpointSurfaceQuery.Endpoint> declared = endpointSurfaceQuery.declaredInSpec(specText);
        if (!declared.isEmpty() && mentionsDeclaredEndpoint(text, declared)) {
            return Binding.REQUIRED;
        }

        return Binding.UNKNOWN;
    }

    /**
     * True when the spec offers a traceability signal at all (it declares endpoints). Without
     * one, {@link #classify} can only answer UNKNOWN for everything, and consumers that narrow
     * behavior to positively-bound goals must fall back to their permissive default instead of
     * silently narrowing to nothing (a non-REST spec must not turn completion re-verification
     * vacuous).
     */
    public boolean hasTraceabilitySignal(String specText) {
        return specText != null && !specText.isBlank()
                && !endpointSurfaceQuery.declaredInSpec(specText).isEmpty();
    }

    private boolean mentionsDeclaredEndpoint(String text, Set<EndpointSurfaceQuery.Endpoint> declared) {
        // Verb-qualified mentions must match a declared endpoint exactly.
        for (EndpointSurfaceQuery.Endpoint mentioned : endpointSurfaceQuery.declaredInSpec(text)) {
            if (declared.contains(mentioned)) return true;
        }
        // A bare path counts when it equals a declared path (any verb) after normalization.
        var m = BARE_PATH.matcher(text);
        while (m.find()) {
            String path = EndpointSurfaceQuery.normalizePath(m.group(2));
            if (path != null && declared.stream().anyMatch(e -> e.path().equals(path))) return true;
        }
        return false;
    }

    private static String subgoalText(GoalEntity subgoal) {
        StringBuilder sb = new StringBuilder();
        if (subgoal.getDescription() != null) sb.append(subgoal.getDescription());
        Object raw = subgoal.getMetadata() != null ? subgoal.getMetadata().get("acceptanceCriteria") : null;
        if (raw instanceof List<?> l) {
            for (Object c : l) {
                if (c != null) sb.append('\n').append(c);
            }
        }
        return sb.toString();
    }
}
