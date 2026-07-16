package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.core.build.inject.FixProposal;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T2-EF: run-level accounting of recurring missing <em>types</em>, decoupled from subgoal identity.
 *
 * <p>The T2-DD same-failure streak resets on a merge or a subgoal change, so a codebase-level
 * recurring defect that persists while the planner reshuffles subgoals above it is invisible to it.
 * This tracker tallies missing types across the whole run and, when one dominates, drives a targeted
 * "create the missing type" redirection ({@code BuildBlockGate.ensureRecurringDefectRedirection}).
 *
 * <p><b>What counts as "missing" is sourced from the code's structure, not scraped from compiler
 * text</b> (the 2026-06-15 lesson). Earlier cuts keyed on {@code BuildErrorClassifier.extractToken},
 * which mined the {@code cannot find symbol} string and fired on garbage: {@code body(...)} (an
 * API-misuse: {@code .body()} on a {@code HeadersBuilder}, not a missing type) and {@code Long}
 * (a JDK type). The compiler already labels each defect by grammar — only {@code symbol: class}/
 * {@code interface} names a missing <em>type</em> — and {@link SymbolGroundingService} already knows,
 * structurally, whether a name is JDK/framework ({@code symbolAllowlisted}) or actually declared in
 * the output tree ({@code definedSymbolsInOutput}, nested types included). So a name counts here only
 * when the grammar says it's a type AND the symbol model says it is genuinely undefined. That alone
 * would have suppressed both prior mis-fires (Long is allowlisted; ErrorResponse was a defined nested
 * type), and it lets the create-type redirect target a real missing type (e.g. a missing exception).
 *
 * <p>Fed from the post-inner-loop residual outcome so a solved-but-recurring fix doesn't masquerade
 * as dominant. Dominance trigger (relative, no fixed N — see {@code feedback_relative_thresholds}):
 * strict majority of the run's missing-type observations + recurred under ≥2 distinct subgoals +
 * seen recently. State is process-local, like the T2-DY {@code FixAttemptLedger}.
 */
@Slf4j
@Component
public class RecurringDefectTracker {

    /** Only {@code symbol: class|interface NAME} names a missing TYPE (the compiler's own grammar). */
    private static final Pattern MISSING_TYPE_SYMBOL =
            Pattern.compile("symbol:\\s*(?:class|interface)\\s+([A-Za-z_][A-Za-z0-9_.]*)");

    /** Optional structural symbol model — null in pure-unit tests (then only the grammar gates). */
    @Autowired(required = false)
    private SymbolGroundingService symbolGroundingService;

    public void setSymbolGroundingService(SymbolGroundingService s) { this.symbolGroundingService = s; }

    private final Map<String, Shape> shapes = new ConcurrentHashMap<>();
    private long missingTypeTotal = 0;
    private long sequence = 0;

    /** Per-cycle cache of the defined-symbol set so the output-tree walk runs at most once per cycle. */
    private String definedCacheCycle;
    private Set<String> definedCache = Set.of();

    /**
     * Observe one probe's residual outcome. On COMPILE_ERROR, every genuinely-undefined type the
     * diagnostic names is tallied under the current subgoal. On COMPILE_OK the fixes that greened
     * the build mark their type "had a green fix" (standing knowledge that enriches the redirect hint).
     */
    public synchronized void observe(BuildOutcome outcome, List<FixProposal> appliedFixes,
                                     String subgoalId, String cycleId) {
        if (outcome == null) return;
        if (outcome.result() == BuildResult.COMPILE_OK) {
            recordGreenFixes(appliedFixes);
            return;
        }
        if (outcome.result() != BuildResult.COMPILE_ERROR) return; // TIMEOUT/SKIPPED carry no shape

        // T2-EF/T2-EN (2026-07-16 evening run): a shape whose type is now genuinely declared has
        // no live defect behind it — drop it from the tally entirely. The cumulative count never
        // unwound before, so a landed type stayed "dominant" (and passed the recency check, whose
        // sequence freezes without new observations): the tracker kept naming MemberRepository as
        // missing after the 18:26 merge had created it.
        Set<String> nowDefined = definedSymbols(cycleId);
        for (var it = shapes.entrySet().iterator(); it.hasNext(); ) {
            Shape s = it.next().getValue();
            if (nowDefined.contains(s.key.substring(s.key.indexOf('|') + 1))) {
                missingTypeTotal = Math.max(0, missingTypeTotal - s.count);
                it.remove();
                log.info("T2-EF: {} is now declared — recurrence tally dropped", s.key);
            }
        }

        for (String type : missingTypes(outcome.stderrTail(), cycleId)) {
            String key = "CANNOT_FIND_SYMBOL|" + type;
            sequence++;
            missingTypeTotal++;
            Shape shape = shapes.computeIfAbsent(key, Shape::new);
            shape.count++;
            shape.lastSeenSeq = sequence;
            if (subgoalId != null && !subgoalId.isBlank()) {
                shape.subgoalIds.add(subgoalId);
            }
        }
    }

    /**
     * The genuinely-undefined type names the diagnostic references: {@code symbol: class|interface}
     * grammar (so methods/variables/API-misuse never count) filtered through the symbol model (so
     * JDK/framework names and already-declared types — including nested ones — never count).
     */
    private Set<String> missingTypes(String stderr, String cycleId) {
        Set<String> out = new HashSet<>();
        if (stderr == null || stderr.isBlank()) return out;
        String plain = BuildErrorClassifier.ANSI_ESCAPE.matcher(stderr).replaceAll("");
        Set<String> defined = definedSymbols(cycleId);
        Matcher m = MISSING_TYPE_SYMBOL.matcher(plain);
        while (m.find()) {
            String name = m.group(1);
            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (simple.isBlank()) continue;
            if (symbolGroundingService != null
                    && (symbolGroundingService.symbolAllowlisted(simple)
                        || defined.contains(simple) || defined.contains(name))) {
                continue; // JDK/framework, or already declared (e.g. a nested type) — not missing
            }
            out.add(simple);
        }
        return out;
    }

    /** Defined-symbol set for {@code cycleId}, computed once per cycle (the walk is not cheap). */
    private Set<String> definedSymbols(String cycleId) {
        if (symbolGroundingService == null) return Set.of();
        if (cycleId != null && cycleId.equals(definedCacheCycle)) return definedCache;
        try {
            definedCache = symbolGroundingService.definedSymbolsInOutput();
        } catch (RuntimeException e) {
            log.debug("T2-EF: definedSymbolsInOutput failed: {}", e.getMessage());
            definedCache = Set.of();
        }
        definedCacheCycle = cycleId;
        return definedCache;
    }

    private void recordGreenFixes(List<FixProposal> appliedFixes) {
        if (appliedFixes == null) return;
        for (FixProposal p : appliedFixes) {
            if (p == null || p.record() == null) continue;
            String token = p.record().missingToken();
            if (token == null) continue;
            String simple = token.substring(token.lastIndexOf('.') + 1);
            Shape shape = shapes.get("CANNOT_FIND_SYMBOL|" + simple);
            if (shape != null) shape.hadGreenFix = true;
        }
    }

    /**
     * T2-EN: an injector fix for {@code missingToken} hit the run-level re-fire cap — the same
     * skeleton/import was re-derived across the cap's worth of cycles without ever landing
     * (2026-07-16: skeleton-repository × BookRepository/MemberRepository, 12 cycles, invisible
     * to the dominance tally because the alternating tokens split it). A capped injection is a
     * directly measured run-level recurrence, so it makes the shape {@link #dominantDefect()}-
     * eligible without waiting for a statistical majority.
     */
    public synchronized void noteCappedInjection(String missingToken, String subgoalId) {
        if (missingToken == null || missingToken.isBlank()) return;
        String simple = missingToken.substring(missingToken.lastIndexOf('.') + 1);
        if (simple.isBlank()) return;
        String key = "CANNOT_FIND_SYMBOL|" + simple;
        sequence++;
        missingTypeTotal++;
        Shape shape = shapes.computeIfAbsent(key, Shape::new);
        shape.count++;
        shape.lastSeenSeq = sequence;
        shape.injectionCapped = true;
        if (subgoalId != null && !subgoalId.isBlank()) {
            shape.subgoalIds.add(subgoalId);
        }
        log.warn("T2-EN: injection re-fire cap for {} recorded as forced-dominant — redirect channel takes over", simple);
    }

    /**
     * Returns the dominant recurring missing type when the relative trigger is met, else empty.
     * Strict majority of missing-type observations + spans ≥2 subgoals + seen recently — or a
     * T2-EN capped injection, a directly measured (non-statistical) run-level recurrence.
     */
    public synchronized Optional<DominantDefect> dominantDefect() {
        if (missingTypeTotal == 0 || shapes.isEmpty()) return Optional.empty();

        // T2-EN: a capped injection takes precedence over the statistical tally (most recent wins).
        Shape capped = null;
        for (Shape s : shapes.values()) {
            if (s.injectionCapped && (capped == null || s.lastSeenSeq > capped.lastSeenSeq)) capped = s;
        }
        if (capped != null) {
            double cappedShare = (double) capped.count / missingTypeTotal;
            return Optional.of(new DominantDefect(capped.key, cappedShare,
                    Math.max(1, capped.subgoalIds.size()), capped.hadGreenFix));
        }

        Shape top = null;
        for (Shape s : shapes.values()) {
            if (top == null || s.count > top.count) top = s;
        }
        if (top == null) return Optional.empty();

        double share = (double) top.count / missingTypeTotal;
        boolean majority = share > 0.5;                        // one defect > all others combined
        boolean spansSubgoals = top.subgoalIds.size() >= 2;     // genuinely cross-subgoal
        boolean recent = top.lastSeenSeq >= sequence - shapes.size();

        if (!(majority && spansSubgoals && recent)) return Optional.empty();
        return Optional.of(new DominantDefect(top.key, share, top.subgoalIds.size(), top.hadGreenFix));
    }

    /** Drops all state. For tests; production state is per-process and naturally per-run. */
    public synchronized void clear() {
        shapes.clear();
        missingTypeTotal = 0;
        sequence = 0;
        definedCacheCycle = null;
        definedCache = Set.of();
    }

    /**
     * The dominant recurring missing type. {@code missingToken} is the type's simple name — a real,
     * createable type (the grammar + symbol model guarantee it is neither a method/API-misuse nor an
     * already-defined/JDK name). {@code hadGreenFix} is true when a deterministic fix greened a probe
     * for it (the import is right; the gap is the type's existence/placement).
     */
    public record DominantDefect(String key, double share, int subgoalSpan, boolean hadGreenFix) {
        public String errorKind() {
            int i = key.indexOf('|');
            return i < 0 ? key : key.substring(0, i);
        }
        public String missingToken() {
            int i = key.indexOf('|');
            return i < 0 ? null : key.substring(i + 1);
        }
    }

    private static final class Shape {
        final String key;
        long count = 0;
        long lastSeenSeq = 0;
        boolean hadGreenFix = false;
        /** T2-EN: an injector fix for this type hit the run-level re-fire cap. */
        boolean injectionCapped = false;
        final Set<String> subgoalIds = new HashSet<>();

        Shape(String key) {
            this.key = key;
        }
    }
}
