package com.walpro.autobuilder.analysis.heuristic;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T2-CI: rejects a mutation that takes an annotation off a type which already had it.
 *
 * <p><b>The harm, from the field.</b> Run {@code 2026-07-27_01-01-01} merged one file six times in
 * three minutes, alternating: with {@code @RestController}, without, with, without, with, without.
 * Every one of those merges was ACCEPTED — one at 0.85 — and none was a rollback. The artifact
 * oscillated between registered and inert three times and nothing observed that it had gone
 * backwards; it cost roughly eight merges of rework. This ticket was once closed as subsumed by the
 * compile probe, and that was wrong in an instructive way: the probe catches a revert that breaks
 * COMPILATION, and this file compiled perfectly. It simply stopped being a bean.
 *
 * <p><b>Why the comparison is annotation NAMES and a strict subset.</b> Each evaluation is
 * criterion-local, so nothing else in the system knows what previous merges established — but the
 * current file content IS that record, because it only got there by being accepted. So the
 * established state needs no new store: it is {@code previousOutput}, and the candidate is the
 * diff's version of the same path.
 *
 * <p>Only a strict LOSS counts. If the candidate also adds annotations to that type it is a
 * substitution — a redesign the loop is entitled to make — and this abstains. That also makes the
 * check immune to the migration case the original ticket wanted a text-matching override for: moving
 * a project from one annotation package to another rewrites imports while the simple names stay put,
 * so nothing is lost and nothing is rejected. No goal text is read, and no marker list is
 * hardcoded ([[feedback-no-ecosystem-jargon-in-framework-code]]) — whatever the type carried is the
 * invariant, whether or not this framework has ever heard of it.
 *
 * <p><b>Abstains</b> on a new file, a deleted type, an unparseable version of either side, and on
 * any project with no previously accepted content. Empty always means "no opinion", never approval.
 */
@Slf4j
@Component
public class MergeRegressionHeuristic implements EvaluationHeuristic {

    private static final String NAME = "MergeRegressionHeuristic";

    /** High: the loss is read off two concrete texts, not inferred. */
    private static final double CONFIDENCE = 0.9;

    /** A regression is not a partial success — the score has to say so, or the average absorbs it. */
    private static final double SUGGESTED_SCORE = 0.1;

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome outcome) {
        if (input == null || outcome == null) return HeuristicOpinion.none(NAME);

        ProjectDiffResult diff = input.projectDiff();
        if (diff == null || diff.changedFiles() == null || diff.changedFiles().isEmpty()) {
            return HeuristicOpinion.none(NAME);
        }
        Map<String, String> established = input.previousOutput();
        if (established == null || established.isEmpty()) return HeuristicOpinion.none(NAME);

        List<String> losses = new ArrayList<>();
        for (Map.Entry<String, String> changed : diff.changedFiles().entrySet()) {
            String path = changed.getKey();
            if (path == null || !path.endsWith(".java")) continue;
            String before = established.get(path);
            if (before == null || before.isBlank()) continue; // a new file establishes, it cannot revert
            collectLosses(path, before, changed.getValue(), losses);
        }
        if (losses.isEmpty()) return HeuristicOpinion.none(NAME);

        String rationale = "This mutation removes an annotation from a type that already carried it, "
                + "and adds nothing in its place: " + String.join("; ", losses)
                + ". Whatever the annotation does — registering the type, activating a check, mapping a "
                + "route — the type stops doing it, and the file still compiles, so no build gate will "
                + "notice. The previous content was itself an accepted merge, so this reverts work the "
                + "loop already banked. Keep the annotation and make the intended change alongside it.";
        log.info("T2-CI: rejecting a mutation that reverts established annotations: {}", losses);
        return HeuristicOpinion.ofDefectWithScore(NAME, CONFIDENCE,
                MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, rationale, SUGGESTED_SCORE);
    }

    /** Per type declared in both versions, the annotations the candidate drops without replacement. */
    private void collectLosses(String path, String before, String after, List<String> losses) {
        Map<String, Set<String>> establishedByType = annotationsByType(before);
        if (establishedByType.isEmpty()) return;
        Map<String, Set<String>> candidateByType = annotationsByType(after);
        if (candidateByType == null) return; // unparseable candidate: the compile gate owns that

        for (Map.Entry<String, Set<String>> entry : establishedByType.entrySet()) {
            Set<String> candidate = candidateByType.get(entry.getKey());
            // A type absent from the candidate is a removal or a rename, not an annotation change.
            if (candidate == null) continue;
            Set<String> lost = new LinkedHashSet<>(entry.getValue());
            lost.removeAll(candidate);
            if (lost.isEmpty()) continue;
            // Anything added makes this a substitution rather than a loss.
            Set<String> added = new LinkedHashSet<>(candidate);
            added.removeAll(entry.getValue());
            if (!added.isEmpty()) continue;
            for (String annotation : lost) {
                losses.add(entry.getKey() + " loses @" + annotation + " (" + path + ")");
            }
        }
    }

    /**
     * Type-level annotations per declared type, or null when the source does not parse. An
     * unparseable side is deliberately distinguished from one with no annotations: the first is
     * "cannot tell", the second is a fact.
     */
    private Map<String, Set<String>> annotationsByType(String source) {
        if (source == null || source.isBlank()) return null;
        CompilationUnit unit;
        try {
            unit = new JavaParser().parse(source).getResult().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
        if (unit == null) return null;
        Map<String, Set<String>> byType = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            byType.put(type.getNameAsString(), type.getAnnotations().stream()
                    .map(a -> a.getNameAsString())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }
        return byType;
    }
}
