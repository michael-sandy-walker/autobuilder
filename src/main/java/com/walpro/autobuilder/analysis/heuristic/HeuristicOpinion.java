package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

import java.util.List;

public record HeuristicOpinion(
        String name,
        double confidence,
        ConfidenceLevel level,
        MutationAcceptanceState mutationState,
        GoalProgressState goalProgress,
        String rationale,
        Double suggestedScore,
        boolean defectFinding,
        StructuralDefect defect
) {
    /**
     * T2-ES: what the finding is ABOUT, stated by the heuristic that found it, separately from
     * the prose it renders for the prompt.
     *
     * <p>Why it cannot be recovered from the rationale: run {@code 2026-09-13_09-44-03} rejected
     * seven consecutive mutations for one parallel store, and the rationale carried declaration
     * LINE NUMBERS — {@code BookStore:14}, then {@code :11}, then {@code :13} — because the model
     * rewrote the file each cycle and kept the defect. Three distinct strings, one defect. A
     * content fingerprint of the prose therefore cannot express "the same thing is wrong again",
     * which is the precondition for escalating instead of re-asking.
     *
     * @param subject         the structural thing the finding is about, derived from the run's own
     *                        code (an entity simple name, a type name) — never framework or
     *                        target-domain vocabulary written into this codebase.
     * @param resolutionPaths the mutually sufficient ways out, each phrased so that satisfying ANY
     *                        one of them clears the defect. Stated here because only the finding
     *                        heuristic knows the shape of its own defect; every consumer renders
     *                        them without interpreting them.
     */
    public record StructuralDefect(String subject, List<String> resolutionPaths) {
        public StructuralDefect {
            resolutionPaths = resolutionPaths == null ? List.of() : List.copyOf(resolutionPaths);
        }
    }

    /**
     * T2-ES: the key a repeat is counted against. A heuristic that reports a structural defect is
     * identified by WHAT it found, so two cycles agree only when the same subject is still wrong;
     * one without a subject falls back to its own name, which is the pre-T2-ES behaviour and the
     * right one for findings about the mutation's statistics rather than its structure.
     */
    public String identityKey() {
        return defect != null && defect.subject() != null && !defect.subject().isBlank()
                ? name + "|" + defect.subject()
                : name;
    }

    public static HeuristicOpinion of(
            String name,
            double confidence,
            MutationAcceptanceState state,
            GoalProgressState progress,
            String rationale
    ) {
        return new HeuristicOpinion(
                name,
                confidence,
                ConfidenceLevel.fromScore(confidence),
                state,
                progress,
                rationale,
                null,
                false,
                null
        );
    }

    public static HeuristicOpinion ofWithScore(
            String name,
            double confidence,
            MutationAcceptanceState state,
            GoalProgressState progress,
            String rationale,
            double suggestedScore
    ) {
        return new HeuristicOpinion(
                name,
                confidence,
                ConfidenceLevel.fromScore(confidence),
                state,
                progress,
                rationale,
                suggestedScore,
                false,
                null
        );
    }

    /**
     * An opinion grounded in a deterministic structural defect of the mutation itself (parallel
     * store, manufactured dependency, ...) — as opposed to score statistics or procedural
     * deferrals. Defect findings take precedence in the negotiator's CRITICAL tiebreak: run 14
     * (2026-07-04) showed the deterministic score echo crossing the CRITICAL threshold on
     * confidence alone (1.0) and outranking a correct defect rejection (0.9) twice, merging
     * runtime-fatal mutations.
     */
    public static HeuristicOpinion ofDefectWithScore(
            String name,
            double confidence,
            MutationAcceptanceState state,
            GoalProgressState progress,
            String rationale,
            double suggestedScore
    ) {
        return ofDefectWithScore(name, confidence, state, progress, rationale, suggestedScore, null);
    }

    /**
     * T2-ES overload: the same defect finding, carrying what it is about and the ways out of it.
     * A finding reported this way can be counted as a REPEAT across cycles in which the model
     * rewrote the offending file, and escalated to a work item that names the alternatives —
     * rather than re-asking with a larger number in the warning text.
     */
    public static HeuristicOpinion ofDefectWithScore(
            String name,
            double confidence,
            MutationAcceptanceState state,
            GoalProgressState progress,
            String rationale,
            double suggestedScore,
            StructuralDefect defect
    ) {
        return new HeuristicOpinion(
                name,
                confidence,
                ConfidenceLevel.fromScore(confidence),
                state,
                progress,
                rationale,
                suggestedScore,
                true,
                defect
        );
    }

    public static HeuristicOpinion none(String name) {
        return new HeuristicOpinion(
                name,
                0.0,
                ConfidenceLevel.NONE,
                MutationAcceptanceState.NOT_EVALUATED,
                GoalProgressState.NOT_EVALUATED,
                "No concerns identified.",
                null,
                false,
                null
        );
    }
}
