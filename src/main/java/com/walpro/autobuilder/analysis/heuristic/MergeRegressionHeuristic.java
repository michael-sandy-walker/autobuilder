package com.walpro.autobuilder.analysis.heuristic;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
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
import java.util.Optional;
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

    /**
     * T2-CI, third arm and the one that asks the right question: has a requirement lost its
     * implementation?
     *
     * <p>The member-loss arm counted declarations, which is a proxy for harm and a poor one — over
     * its first field run it fired twice on rewrites that broke nothing and once blocked a real
     * repair. Coverage asks instead whether an endpoint the SPECIFICATION names was being served
     * before this mutation and would not be after it. On the four field shapes measured, that
     * separates all four: the truncated controller that dropped {@code GET /books} and
     * {@code POST /books} is flagged, while constructor-to-field injection, a model type dropping
     * explicit constructors, and a deleted private helper are all silent.
     *
     * <p>It is also indifferent to where the code lives, which member counting could never be: a
     * handler MOVED to another class keeps the endpoint served, so nothing is reported.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery endpointSurfaceQuery;

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
        losses.addAll(endpointCoverageLosses(input, established, diff.changedFiles()));
        if (losses.isEmpty()) return HeuristicOpinion.none(NAME);

        String rationale = "This mutation takes something off a type that already had it, and puts "
                + "nothing in its place: " + String.join("; ", losses)
                + ". Nothing splices these files together — the content you emit REPLACES the whole "
                + "file — so a lost annotation stops the type being registered or checked, and a "
                + "member you did not re-emit ceases to exist. Either way the file still compiles, so "
                + "no build gate will notice. The previous content was itself an accepted merge, so "
                + "this reverts work the loop already banked. Emit the COMPLETE file: keep everything "
                + "that was there and make the intended change alongside it.";
        log.info("T2-CI: rejecting a mutation that reverts established structure: {}", losses);
        return HeuristicOpinion.ofDefectWithScore(NAME, CONFIDENCE,
                MutationAcceptanceState.REJECTED, GoalProgressState.STAGNATING, rationale, SUGGESTED_SCORE);
    }

    /**
     * Spec-named endpoints the established output serves and the post-mutation output would not.
     *
     * <p>The comparison is over the WHOLE post-merge view (established files overridden by this
     * mutation's), not the changed files alone — an endpoint is a property of the artifact, not of a
     * file. Empty whenever the question cannot be answered honestly: no specification, no endpoint
     * query, or {@code isBuilt} abstaining on either side.
     */
    private List<String> endpointCoverageLosses(CycleContextData input,
                                                Map<String, String> established,
                                                Map<String, String> changed) {
        String spec = input.currentGoalDescription();
        if (endpointSurfaceQuery == null || spec == null || spec.isBlank()) return List.of();

        Map<String, String> afterMerge = new LinkedHashMap<>(established);
        afterMerge.putAll(changed);

        List<String> lost = new ArrayList<>();
        for (var endpoint : endpointSurfaceQuery.declaredInSpec(spec)) {
            Optional<Boolean> was = endpointSurfaceQuery.isBuilt(endpoint, established);
            Optional<Boolean> now = endpointSurfaceQuery.isBuilt(endpoint, afterMerge);
            if (was.isPresent() && was.get() && now.isPresent() && !now.get()) {
                lost.add(endpoint + " is no longer served (the specification names it and the current"
                        + " output serves it)");
            }
        }
        if (!lost.isEmpty()) {
            log.info("T2-CI: mutation removes coverage of spec-named endpoint(s): {}", lost);
        }
        return lost;
    }

    public void setEndpointSurfaceQuery(
            com.walpro.autobuilder.analysis.structure.EndpointSurfaceQuery endpointSurfaceQuery) {
        this.endpointSurfaceQuery = endpointSurfaceQuery;
    }

    /** Per type declared in both versions, what the candidate drops without replacing it. */
    private void collectLosses(String path, String before, String after, List<String> losses) {
        Map<String, TypeProfile> established = profileByType(before);
        Map<String, TypeProfile> candidate = profileByType(after);
        if (established == null || candidate == null) return; // unparseable: the compile gate owns it

        for (Map.Entry<String, TypeProfile> entry : established.entrySet()) {
            String typeName = entry.getKey();
            TypeProfile was = entry.getValue();
            TypeProfile now = candidate.get(typeName);
            // A type absent from the candidate is a removal or a rename, not a change to this type.
            if (now == null) continue;

            Set<String> lostAnnotations = new LinkedHashSet<>(was.annotations());
            lostAnnotations.removeAll(now.annotations());
            Set<String> gainedAnnotations = new LinkedHashSet<>(now.annotations());
            gainedAnnotations.removeAll(was.annotations());
            if (!lostAnnotations.isEmpty() && gainedAnnotations.isEmpty()
                    && !anythingGained(was, now)) {
                for (String annotation : lostAnnotations) {
                    losses.add(typeName + " loses @" + annotation + " (" + path + ")");
                }
            }
        }
    }

    /**
     * What this heuristic needs to know about one declared type. Gathered in a single parse — the
     * earlier shape parsed each file once per aspect.
     */
    private record TypeProfile(Set<String> annotations, Set<String> members,
                               Map<String, Set<String>> memberAnnotations) {
    }

    /** Whether the candidate declares any member name the established version did not. */
    private static boolean declaresNewMembers(TypeProfile before, TypeProfile after) {
        Set<String> gained = new LinkedHashSet<>(after.members());
        gained.removeAll(before.members());
        return !gained.isEmpty();
    }

    /**
     * Whether the candidate added anything at all: a member, a type-level annotation, or an
     * annotation on one of its members. Any addition means the type is being reworked rather than
     * truncated, which is the one distinction this heuristic rests on.
     *
     * <p>The member-annotation arm was earned twice over. A service and then a controller each moved
     * from constructor injection to an annotated field — the constructor disappears, no member name
     * is gained, and the type's own annotations are untouched, so the two coarser tests both saw a
     * pure loss. What actually changed is that the FIELD gained the annotation that now supplies it.
     * Reading only type-level annotations makes a routine rewiring look like a deletion.
     */
    private static boolean anythingGained(TypeProfile before, TypeProfile after) {
        if (declaresNewMembers(before, after)) return true;
        Set<String> gainedTypeAnnotations = new LinkedHashSet<>(after.annotations());
        gainedTypeAnnotations.removeAll(before.annotations());
        if (!gainedTypeAnnotations.isEmpty()) return true;
        for (Map.Entry<String, Set<String>> entry : after.memberAnnotations().entrySet()) {
            Set<String> gained = new LinkedHashSet<>(entry.getValue());
            gained.removeAll(before.memberAnnotations().getOrDefault(entry.getKey(), Set.of()));
            if (!gained.isEmpty()) return true;
        }
        return false;
    }

    /**
     * Every declared type's annotations and member names (with each member's annotations). Null
     * when the source does not parse — "cannot tell", never "has nothing".
     *
     * <p>Members are NAMES, not signatures: a changed return type is the same member reimplemented,
     * and treating it as new would let any rewrite launder a loss.
     */
    private Map<String, TypeProfile> profileByType(String source) {
        CompilationUnit unit = parse(source);
        if (unit == null) return null;
        Map<String, TypeProfile> byType = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            Set<String> annotations = type.getAnnotations().stream()
                    .map(a -> a.getNameAsString())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Set<String> members = new LinkedHashSet<>();
            Map<String, Set<String>> memberAnnotations = new LinkedHashMap<>();
            type.getFields().forEach(f -> f.getVariables().forEach(v -> {
                members.add(v.getNameAsString());
                memberAnnotations.put(v.getNameAsString(), annotationNames(f.getAnnotations()));
            }));
            type.getMethods().forEach(m -> {
                members.add(m.getNameAsString());
                memberAnnotations.put(m.getNameAsString(), annotationNames(m.getAnnotations()));
            });
            type.getConstructors().forEach(c -> {
                String key = "<init>/" + c.getParameters().size();
                members.add(key);
                memberAnnotations.put(key, annotationNames(c.getAnnotations()));
            });
            byType.put(type.getNameAsString(),
                    new TypeProfile(annotations, members, memberAnnotations));
        }
        return byType;
    }

    private static Set<String> annotationNames(
            com.github.javaparser.ast.NodeList<? extends AnnotationExpr> annotations) {
        return annotations.stream().map(AnnotationExpr::getNameAsString)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
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
