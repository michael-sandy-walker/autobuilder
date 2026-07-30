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
                    && !declaresNewMembers(was, now)) {
                for (String annotation : lostAnnotations) {
                    losses.add(typeName + " loses @" + annotation + " (" + path + ")");
                }
            }

            Set<String> lostMembers = unexplainedMemberLosses(was, now);
            if (!lostMembers.isEmpty()) {
                losses.add(typeName + " no longer declares " + lostMembers + " (" + path + ")");
            }
        }
    }

    /**
     * What this heuristic needs to know about one declared type. Gathered in a single parse — the
     * earlier shape parsed each file once per aspect.
     */
    private record TypeProfile(Set<String> annotations, Set<String> members, String kind,
                               boolean declaresSupertype) {
    }

    /** Whether the candidate declares any member name the established version did not. */
    private static boolean declaresNewMembers(TypeProfile before, TypeProfile after) {
        Set<String> gained = new LinkedHashSet<>(after.members());
        gained.removeAll(before.members());
        return !gained.isEmpty();
    }

    /**
     * Member names the candidate no longer declares, when their absence cannot be explained by
     * anything other than the file having been re-emitted incompletely.
     *
     * <p>Three conditions, each earned from a field case measured over one run's 28 cycles (all
     * three pure member reductions found there, and the rule separates them 3 for 3):
     * <ul>
     *   <li><b>nothing gained — members OR annotations</b> — otherwise it is a redesign, the same
     *       strict-loss shape the annotation half uses. One mutation moved a service from
     *       constructor injection to an annotated field: it lost a constructor and gained two
     *       annotations, which is a rework of how the type is wired, not a truncation;</li>
     *   <li><b>the kind is unchanged</b> — one mutation turned a class holding five methods into an
     *       empty interface. Changing what a type IS is a redesign, whatever it costs;</li>
     *   <li><b>the type declares no supertype</b> — another mutation deleted a re-declaration of a
     *       method its supertype supplies, with a comment saying exactly that. A type that inherits
     *       declarations may legitimately drop a redundant one, and this heuristic cannot read the
     *       supertype to tell which are redundant.</li>
     * </ul>
     *
     * <p>The case this leaves standing is the one that costs the run: a class re-emitted with only
     * the current subgoal's members, deleting the rest. Measured example — a controller re-emitted
     * with its one path-variable handler, dropping the list and create handlers, both spec
     * requirements. Nothing splices these files together: {@code MutationApplier} and
     * {@code MergeAgent} write whole file content, so those two endpoints simply cease to exist.
     */
    private static Set<String> unexplainedMemberLosses(TypeProfile before, TypeProfile after) {
        if (!before.kind().equals(after.kind())) return Set.of();
        if (after.declaresSupertype() || before.declaresSupertype()) return Set.of();
        if (declaresNewMembers(before, after)) return Set.of();
        Set<String> gainedAnnotations = new LinkedHashSet<>(after.annotations());
        gainedAnnotations.removeAll(before.annotations());
        if (!gainedAnnotations.isEmpty()) return Set.of();
        Set<String> lost = new LinkedHashSet<>(before.members());
        lost.removeAll(after.members());
        return lost;
    }

    /**
     * Every declared type's annotations, member names, kind and whether it names a supertype. Null
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
            type.getFields().forEach(f -> f.getVariables().forEach(v -> members.add(v.getNameAsString())));
            type.getMethods().forEach(m -> members.add(m.getNameAsString()));
            type.getConstructors().forEach(c -> members.add("<init>/" + c.getParameters().size()));
            boolean declaresSupertype = false;
            String kind = type.getClass().getSimpleName();
            if (type instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration decl) {
                kind = decl.isInterface() ? "interface" : "class";
                declaresSupertype = !decl.getExtendedTypes().isEmpty()
                        || !decl.getImplementedTypes().isEmpty();
            }
            byType.put(type.getNameAsString(), new TypeProfile(annotations, members, kind, declaresSupertype));
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
