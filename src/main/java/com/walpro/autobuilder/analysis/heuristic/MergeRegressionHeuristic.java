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
 * <p>Only a strict LOSS counts, and substitution is recognised at TWO levels.
 * <ul>
 *   <li><b>Annotation level</b> — the candidate also adds annotations to that type. Swapping one for
 *       another is a redesign the loop is entitled to make. This is also what makes the check immune
 *       to the migration case the original ticket wanted a text-matching override for: moving a
 *       project from one annotation package to another rewrites imports while the simple names stay
 *       put, so nothing is lost.</li>
 *   <li><b>Member level</b> — the candidate declares member names the established version did not.
 *       Learned from the first field firing (run {@code 2026-07-30_00-48-16}): one mutation dropped
 *       three annotations from a model type <em>and hand-wrote the eight constructors, getters and
 *       setters those annotations had been generating</em>. The effect was replaced; only the
 *       mechanism changed. In the same mutation another type lost its stereotype with its member
 *       names untouched — nothing replaced anything, the bean stopped being registered, and a
 *       constructor injection of it would have failed at startup. Members are compared by NAME, so
 *       changing a signature ({@code findById} returning an optional instead of the raw type) is not
 *       a "gain" and does not excuse a lost annotation.</li>
 * </ul>
 *
 * <p><b>The recall this trades away, stated plainly:</b> a mutation that both adds a method and drops
 * a stereotype is no longer reported. That is deliberate — this is not the only defence (the boot
 * gate catches an unregistered bean, and {@code WiringFactsAnalyzer.peerAnnotationAnomalies} catches
 * the odd-one-out) and a heuristic that rejects legitimate redesigns costs more than it saves.
 *
 * <p>No goal text is read and no marker list is hardcoded
 * ([[feedback-no-ecosystem-jargon-in-framework-code]]) — whatever the type carried is the invariant,
 * whether or not this framework has ever heard of it.
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
        if (establishedByType == null || establishedByType.isEmpty()) return;
        Map<String, Set<String>> candidateByType = annotationsByType(after);
        if (candidateByType == null) return; // unparseable candidate: the compile gate owns that
        Map<String, Set<String>> establishedMembers = memberNamesByType(before);
        Map<String, Set<String>> candidateMembers = memberNamesByType(after);

        for (Map.Entry<String, Set<String>> entry : establishedByType.entrySet()) {
            String typeName = entry.getKey();
            Set<String> candidate = candidateByType.get(typeName);
            // A type absent from the candidate is a removal or a rename, not an annotation change.
            if (candidate == null) continue;
            Set<String> lost = new LinkedHashSet<>(entry.getValue());
            lost.removeAll(candidate);
            if (lost.isEmpty()) continue;
            // Anything added at the annotation level makes this a substitution rather than a loss.
            Set<String> addedAnnotations = new LinkedHashSet<>(candidate);
            addedAnnotations.removeAll(entry.getValue());
            if (!addedAnnotations.isEmpty()) continue;
            // Nor is it a loss when the type now declares members it did not declare before: an
            // annotation that was generating those members has been replaced by writing them out.
            if (declaresNewMembers(typeName, establishedMembers, candidateMembers)) continue;
            for (String annotation : lost) {
                losses.add(typeName + " loses @" + annotation + " (" + path + ")");
            }
        }
    }

    /** Whether the candidate's version of this type declares any member name the established one did not. */
    private boolean declaresNewMembers(String typeName, Map<String, Set<String>> established,
                                       Map<String, Set<String>> candidate) {
        if (established == null || candidate == null) return false;
        Set<String> after = candidate.getOrDefault(typeName, Set.of());
        Set<String> gained = new LinkedHashSet<>(after);
        gained.removeAll(established.getOrDefault(typeName, Set.of()));
        return !gained.isEmpty();
    }

    /**
     * Declared member NAMES per type — fields, methods and constructors. Names rather than
     * signatures: a changed return type is a reimplementation of the same member, not a new one, and
     * treating it as new would excuse the very annotation loss this heuristic exists to catch.
     */
    private Map<String, Set<String>> memberNamesByType(String source) {
        CompilationUnit unit = parse(source);
        if (unit == null) return Map.of();
        Map<String, Set<String>> byType = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            Set<String> names = new LinkedHashSet<>();
            type.getFields().forEach(f -> f.getVariables().forEach(v -> names.add(v.getNameAsString())));
            type.getMethods().forEach(m -> names.add(m.getNameAsString()));
            type.getConstructors().forEach(c -> names.add("<init>/" + c.getParameters().size()));
            byType.put(type.getNameAsString(), names);
        }
        return byType;
    }

    /**
     * Type-level annotations per declared type, or null when the source does not parse. An
     * unparseable side is deliberately distinguished from one with no annotations: the first is
     * "cannot tell", the second is a fact.
     */
    private Map<String, Set<String>> annotationsByType(String source) {
        CompilationUnit unit = parse(source);
        if (unit == null) return null;
        Map<String, Set<String>> byType = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            byType.put(type.getNameAsString(), type.getAnnotations().stream()
                    .map(a -> a.getNameAsString())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
        }
        return byType;
    }

    /** Parsed source, or null when it does not parse — "cannot tell", never "has nothing". */
    private CompilationUnit parse(String source) {
        if (source == null || source.isBlank()) return null;
        try {
            return new JavaParser().parse(source).getResult().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
