package com.walpro.autobuilder.core.engine;

import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.PromptTokenBudget;
import com.walpro.autobuilder.protocol.analysis.AnalysisProtocolContract;
import com.walpro.autobuilder.protocol.evaluation.EvaluationProtocolContract;
import com.walpro.autobuilder.protocol.evolution.EvolutionProtocolContract;
import com.walpro.autobuilder.protocol.interpretation.InterpretationProtocolContract;
import com.walpro.autobuilder.protocol.planner.PlannerProtocolContract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-J Phase 3c: the prompt-diff driver.
 *
 * Renders the five signal-relevant LLM prompts from a frozen {@link CycleContextData} exactly as
 * the production adapters do — same contracts, same argument derivations — with zero LLM cost.
 * A perturbation "reaches" a prompt iff the rendered text differs; only those route to Phase 4.
 *
 * Argument derivations mirrored from the adapters:
 * - evolution: divergent = DIVERGENCE_REQUEST || STAGNATION
 *   (EvolutionAgent's decision; its fourth leg, isRegressing(ctx), is score-history-driven and
 *   held constant — fixture states carry no score history), interpretation from ctx, default headroom.
 * - planner: depthFirst=false (application.yml default).
 * - evaluation: fileContentBudgetChars=40000 (adapter @Value default; T2-FI replaced a
 *   150-line cap that was in practice a 4000-character one).
 * - interpretation: empty artifact list.
 * Held constant, documented: SymbolGroundingService.refreshGoalAnnotations (adapters refresh
 * [UNDEFINED] markers against disk before rendering — fixture states carry none), the capability
 * manifest and pass-1 addon (appended to system prompts, signal-independent).
 */
public final class AgentPromptRenderer {

    private AgentPromptRenderer() {}

    /** Separator between block label and line in multiset keys; cannot occur in prompt text. */
    private static final char SEP = '\u0001';

    public static final List<String> PROMPTS =
            List.of("planner", "evolution", "evaluation", "analysis", "interpretation");

    public static Map<String, String> renderAll(CycleContextData ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("planner", PlannerProtocolContract.getUserPrompt(ctx));
        out.put("evolution", EvolutionProtocolContract.getUserPrompt(
                ctx, ctx.evaluationInterpretation(), useDivergent(ctx),
                PromptTokenBudget.DEFAULT_PROMPT_HEADROOM_TOKENS));
        out.put("evaluation", EvaluationProtocolContract.getUserPrompt(ctx, 150));
        out.put("analysis", AnalysisProtocolContract.getUserPrompt(ctx));
        out.put("interpretation", InterpretationProtocolContract.getUserPrompt(ctx, List.of()));
        return out;
    }

    /** Mirrors EvolutionAgent's signal-driven divergence decision (score-regression leg excluded). */
    public static boolean useDivergent(CycleContextData ctx) {
        return ctx.hasOpenRequest(BasicSignals.DIVERGENCE_REQUEST)
                || ctx.hasOpenRequest(BasicSignals.STAGNATION);
    }

    /** Line-level diff summary of one prompt render, with changes attributed to their enclosing block. */
    public record PromptDelta(String prompt, int addedLines, int removedLines, Set<String> touchedBlocks) {
        public boolean unchanged() {
            return addedLines == 0 && removedLines == 0;
        }
    }

    /** Diffs two full renders; only prompts with a non-empty delta appear in the result. */
    public static Map<String, PromptDelta> diff(Map<String, String> base, Map<String, String> perturbed) {
        Map<String, PromptDelta> out = new LinkedHashMap<>();
        for (String prompt : base.keySet()) {
            PromptDelta d = diffOne(prompt, base.get(prompt), perturbed.get(prompt));
            if (!d.unchanged()) out.put(prompt, d);
        }
        return out;
    }

    private static PromptDelta diffOne(String prompt, String a, String b) {
        if (a.equals(b)) return new PromptDelta(prompt, 0, 0, Set.of());
        Map<String, Integer> linesA = lineMultisetWithBlocks(a);
        Map<String, Integer> linesB = lineMultisetWithBlocks(b);
        int removed = 0, added = 0;
        Set<String> blocks = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> e : linesA.entrySet()) {
            int diff = e.getValue() - linesB.getOrDefault(e.getKey(), 0);
            if (diff > 0) {
                removed += diff;
                blocks.add(blockOf(e.getKey()));
            }
        }
        for (Map.Entry<String, Integer> e : linesB.entrySet()) {
            int diff = e.getValue() - linesA.getOrDefault(e.getKey(), 0);
            if (diff > 0) {
                added += diff;
                blocks.add(blockOf(e.getKey()));
            }
        }
        return new PromptDelta(prompt, added, removed, blocks);
    }

    /**
     * Multiset of "block + line" entries so identical lines under different blocks stay
     * distinct and repeated lines are counted, not collapsed.
     */
    private static Map<String, Integer> lineMultisetWithBlocks(String text) {
        Map<String, Integer> out = new HashMap<>();
        String block = "(preamble)";
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (isBlockHeader(trimmed)) block = trimmed;
            out.merge(block + SEP + line, 1, Integer::sum);
        }
        return out;
    }

    private static boolean isBlockHeader(String trimmed) {
        return (trimmed.startsWith("---") && trimmed.endsWith("---") && trimmed.length() > 6)
                || trimmed.startsWith("#");
    }

    private static String blockOf(String key) {
        return key.substring(0, key.indexOf(SEP));
    }

    /** Convenience for sweeps: which prompts change when going base -> perturbed context. */
    public static List<String> reachedPrompts(CycleContextData base, CycleContextData perturbed) {
        return new ArrayList<>(diff(renderAll(base), renderAll(perturbed)).keySet());
    }
}
