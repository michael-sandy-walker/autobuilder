package com.walpro.autobuilder.protocol.evolution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.mutation.BlockerExtractor;
import com.walpro.autobuilder.core.mutation.BlockerExtractor.ClassifiedDiagnostic;
import com.walpro.autobuilder.core.mutation.CompileAuthorErrors;

/**
 * T2-DT: formatter for the {@code --- PREVIOUS COMPILE ERRORS ---} evolution-prompt block.
 * Surfaces structured author-error diagnostics (file/line/column/message + static hint) from
 * the previous cycle's rolled-back compile probe so the LLM stops re-proposing the same JVM-
 * semantic mistake on the next attempt.
 *
 * <p>Suppression rules — block returns {@code null} when any of these hold:
 * <ul>
 *   <li>{@code lastAuthorErrors} is null or has no diagnostics;</li>
 *   <li>{@code lastAuthorErrors.subgoalId} differs from {@code currentSubGoalId} — author-errors
 *       are tied to the attempt's intent; a subgoal shift resets the steering;</li>
 *   <li>{@code STEERING_SUPPRESS} signal is present — meta-agent veto.</li>
 * </ul>
 *
 * <p>Layered above (not in place of) the raw stderr surface rendered by
 * {@link com.walpro.autobuilder.protocol.build.BuildResultPromptBlock}: that block remains the
 * ground truth for UNKNOWN + DEFERRABLE shapes. This block adds structured classification +
 * hint text on top of AUTHOR_ERROR shapes only.
 */
public final class CompileDiagnosticPromptBlock {

    /** T2-DV: relative-cap bounds. Effective cap is {@code min(MAX_ENTRIES, max(MIN_ENTRIES, distinctShapes/2))}. */
    static final int MAX_ENTRIES = 8;
    static final int MIN_ENTRIES = 3;

    /**
     * Static hint map keyed on diagnostic-message substring. Severity rank within the map
     * doubles as severity rank for {@link #MAX_ENTRIES} truncation — earlier keys outrank
     * later ones. New entries should be inserted in severity order.
     */
    private static final Map<String, String> HINTS = new LinkedHashMap<>();
    static {
        HINTS.put("cannot be referenced from a static context",
                "class is instance-scoped — inject it via constructor and call on the instance.");
        HINTS.put("incompatible types",
                "declared return type and `return` expression generic parameters must match.");
        HINTS.put("cannot be applied to",
                "argument types or arity do not match the declared signature — re-check the call site.");
        HINTS.put("is not abstract and does not override",
                "implement the missing method from the parent interface/class or remove the `@Override`.");
        HINTS.put("unreachable statement",
                "a prior return/throw makes this statement unreachable — restructure the control flow.");
        HINTS.put("might not have been initialized",
                "all branches reaching this read must assign the variable, or initialise it at declaration.");
    }

    private CompileDiagnosticPromptBlock() {}

    /**
     * Result of evaluating whether the block applies this cycle. Returned by {@link #evaluate}
     * so callers (EvolutionAgent) can emit signals/events without re-deriving the conditions.
     */
    public record RenderResult(
            String blockText,
            int errorCount,
            int rendered,
            int hintsMatched,
            boolean suppressed,
            String suppressionReason
    ) {
        public boolean fires() { return blockText != null && !suppressed; }
    }

    /** Convenience for callers that only need the text. */
    public static String render(CycleContextData ctx) {
        RenderResult r = evaluate(ctx);
        return r.blockText;
    }

    public static RenderResult evaluate(CycleContextData ctx) {
        if (ctx == null) return new RenderResult(null, 0, 0, 0, false, "no-context");

        CompileAuthorErrors errs = ctx.lastAuthorErrors();
        if (errs == null || errs.isEmpty()) {
            return new RenderResult(null, 0, 0, 0, false, "no-author-errors");
        }

        if (!Objects.equals(ctx.currentSubGoalId(), errs.subgoalId())) {
            return new RenderResult(null, errs.diagnostics().size(), 0, 0, false, "subgoal-shift");
        }

        if (ctx.hasSignal(BasicSignals.STEERING_SUPPRESS)) {
            return new RenderResult(null, errs.diagnostics().size(), 0, 0, true, "meta-agent");
        }

        // T2-DV: group by normalised shape (T4-BF's key) so identical-shape errors collapse to one
        // entry with a [×N] suffix instead of consuming N slots. Insertion order preserves the
        // "first occurrence wins" representative selection, which combined with the stable sort
        // below keeps the rendered output deterministic.
        LinkedHashMap<String, List<ClassifiedDiagnostic>> byShape = new LinkedHashMap<>();
        for (ClassifiedDiagnostic d : errs.diagnostics()) {
            String key = BlockerExtractor.normaliseShape(d == null || d.message() == null ? "" : d.message());
            byShape.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
        }

        // Stable-sort shape groups by their representative's severity rank (lowest rank ==
        // highest severity). Unknown shapes go to the end.
        List<Map.Entry<String, List<ClassifiedDiagnostic>>> sortedGroups = new ArrayList<>(byShape.entrySet());
        sortedGroups.sort(Comparator.comparingInt(e -> severityRank(e.getValue().get(0))));

        int total = errs.diagnostics().size();
        int distinctShapes = sortedGroups.size();
        int cap = Math.min(MAX_ENTRIES, Math.max(MIN_ENTRIES, distinctShapes / 2));
        int renderCount = Math.min(distinctShapes, cap);
        int hintsMatched = 0;

        StringBuilder sb = new StringBuilder("--- PREVIOUS COMPILE ERRORS (your last attempt was rolled back) ---\n");
        sb.append("Your previous mutation for this subgoal failed the compile probe with the following ")
          .append("errors that re-staging cannot resolve. The next attempt must address them directly:\n\n");

        for (int i = 0; i < renderCount; i++) {
            List<ClassifiedDiagnostic> group = sortedGroups.get(i).getValue();
            ClassifiedDiagnostic rep = group.get(0);
            int count = group.size();
            sb.append("- ").append(formatLocation(rep)).append("  ").append(rep.message());
            if (count > 1) sb.append(" [×").append(count).append("]");
            sb.append("\n");
            String hint = hintFor(rep);
            if (hint != null) {
                sb.append("  Hint: ").append(hint).append("\n");
                hintsMatched++;
            }
            sb.append("\n");
        }

        if (distinctShapes > renderCount) {
            int omittedShapes = distinctShapes - renderCount;
            sb.append("... and ").append(omittedShapes).append(" more distinct error shape")
              .append(omittedShapes == 1 ? "" : "s")
              .append(" omitted; fix the above first.\n\n");
        }

        sb.append("--- END PREVIOUS COMPILE ERRORS ---");

        return new RenderResult(sb.toString(), total, renderCount, hintsMatched, false, null);
    }

    private static String formatLocation(ClassifiedDiagnostic d) {
        if (d.file() == null || d.file().isBlank()) return "(unknown location)";
        if (d.line() <= 0) return d.file();
        return d.file() + ":" + d.line();
    }

    private static int severityRank(ClassifiedDiagnostic d) {
        if (d == null || d.message() == null) return Integer.MAX_VALUE;
        String lower = d.message().toLowerCase(Locale.ROOT);
        int rank = 0;
        for (String key : HINTS.keySet()) {
            if (lower.contains(key)) return rank;
            rank++;
        }
        return Integer.MAX_VALUE;
    }

    private static String hintFor(ClassifiedDiagnostic d) {
        if (d == null || d.message() == null) return null;
        String lower = d.message().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : HINTS.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return null;
    }
}
