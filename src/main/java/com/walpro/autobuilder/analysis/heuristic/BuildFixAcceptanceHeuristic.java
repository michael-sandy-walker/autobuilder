package com.walpro.autobuilder.analysis.heuristic;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.FileRole;
import com.walpro.autobuilder.core.build.FileRoleClassifier;
import lombok.RequiredArgsConstructor;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.Signal;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.walpro.autobuilder.core.build.ContainerMismatchExtractor;

/**
 * Overrides evaluation to ACCEPTED (CRITICAL confidence) when the build state shows
 * COMPILE_ERROR AND the mutation touches a file the build error already names — either
 * a build-config file (pom.xml, build.gradle, settings.gradle) or a Java source file
 * cited in the stderr's file-coordinates (e.g. {@code Foo.java:[12,5]}).
 *
 * <p>The LLM evaluator cannot run the build, so it sees the stale {@code --- BUILD RESULT ---}
 * block from the previous cycle and concludes the build still fails — even though the proposed
 * mutation is the fix. For Java source fixes (T2-DQ) the evaluator additionally rejects the
 * candidate as "off-subgoal" because the touched file is lexically outside the current
 * subgoal's target scope, even when the compile error structurally requires that exact
 * cross-file change. Both paths produce a false REJECTED that blocks merge and prevents the
 * post-merge {@link com.walpro.autobuilder.core.build.BuildExecutor} from verifying the fix.
 *
 * <p>Gating on {@code buildOutcome.result() == COMPILE_ERROR} (rather than BUILD_FIX subgoal
 * metadata) makes this heuristic resilient to subgoal restructuring by the planner: it fires
 * whenever the build is concretely failing and the mutation targets a file the compiler
 * itself flagged, regardless of how the goal tree is currently shaped.
 *
 * <p>Preconditions (both must hold):
 * <ol>
 *   <li>The most recent carried {@link BuildOutcome} has result {@link BuildResult#COMPILE_ERROR}.</li>
 *   <li>The mutation touches at least one build-config file <em>or</em> a Java source file
 *   named in the build stderr's file-coordinate prefixes.</li>
 * </ol>
 *
 * <p>If the fix doesn't resolve the error, the post-merge build gate ({@code BuildResultHeuristic})
 * will catch it next cycle and inject a new BUILD_FIX subgoal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildFixAcceptanceHeuristic implements EvaluationHeuristic {

    private static final String NAME = "BuildFixAcceptanceHeuristic";

    private final FileRoleClassifier fileRoleClassifier;

    /**
     * T2-CV: abstain threshold on shared {@code consecutiveBuildFixAttempts} state. Crossing
     * either this OR {@code REPETITION_DETECTED >= 1.0} routes the cycle to the LLM/heuristic
     * path so {@link com.walpro.autobuilder.core.build.BuildFixEscalator} can emit
     * REPLAN_REQUESTED / ROLE_ANALYSIS escalation signals.
     */
    private static final int ABSTAIN_ATTEMPT_THRESHOLD = 3;
    private static final double ABSTAIN_REPETITION_THRESHOLD = 1.0;

    // T2-DQ: Maven-style file-coordinate prefix in compile stderr — e.g.
    // "/abs/path/Foo.java:[12,5]" or "Foo.java:[12,5]". Group 1 is the file path.
    // Mirrors the pattern in BlockerSubgoalInjector.FILE_COORD_PREFIX.
    private static final Pattern FILE_COORD_PREFIX = Pattern.compile(
            "(\\S+\\.java):\\[\\d+,\\d+\\]"
    );

    @Override
    public HeuristicOpinion evaluate(CycleContextData input, EvaluationOutcome original) {
        if (input == null) return HeuristicOpinion.none(NAME);

        BuildOutcome build = input.buildOutcome();
        if (build == null || build.result() != BuildResult.COMPILE_ERROR) return HeuristicOpinion.none(NAME);

        EvolutionOutcome evolution = input.evolutionOutcome();
        if (evolution == null) return HeuristicOpinion.none(NAME);

        String matchKind;
        if (touchesBuildConfig(evolution)) {
            matchKind = "build-config";
        } else if (touchesContainerMismatchProducer(evolution, build.stderrTail())) {
            // T2-FE: a wrapping disagreement is reported at the CONSUMER — javac names the file
            // that used the value, never the method that produced it. So T2-DQ's stderr-named
            // carve-out cannot cover the producer, and a proposal that fixes the return type is
            // both off-subgoal and outside every existing exemption. Field-observed 2026-08-02:
            // the steering block asked for exactly this repair, the work item named only the
            // consumer, and SUBGOAL_SCOPE_MISMATCH fired twice.
            matchKind = "container-mismatch producer";
        } else if (touchesStderrNamedFile(evolution, build.stderrTail())) {
            // T2-DQ: candidate touches a Java file the compiler itself named in its diagnostic.
            // The LLM evaluator may have rejected it as "off-subgoal", but the build error
            // structurally requires that exact file to change.
            matchKind = "stderr-named source";
        } else {
            return HeuristicOpinion.none(NAME);
        }

        // T2-CV: abstain when shared signal/graph state indicates we're stuck. Two stuck signals,
        // either is sufficient: (1) consecutiveBuildFixAttempts crossing its threshold (existing
        // T1-DQ counter, lives on context — not a private heuristic counter); (2) the active
        // REPETITION_DETECTED signal at full strength meaning evolution keeps fingerprint-replaying.
        int attempts = input.consecutiveBuildFixAttempts();
        double repetitionStrength = input.requestUrgency(BasicSignals.REPETITION_DETECTED);
        if (attempts >= ABSTAIN_ATTEMPT_THRESHOLD || repetitionStrength >= ABSTAIN_REPETITION_THRESHOLD) {
            log.warn("{}: stuck on build-fix retries (attempts={}, repetition={}, match={}) — abstaining; " +
                            "BuildFixEscalator (T2-CV) will emit REPLAN_REQUESTED / ROLE_ANALYSIS",
                    NAME, attempts, repetitionStrength, matchKind);
            return HeuristicOpinion.none(NAME);
        }

        log.info("{}: build COMPILE_ERROR + {} mutation (attempt {}) — overriding to ACCEPTED; " +
                "post-merge build gate (T4-AN-1) is the authoritative result", NAME, matchKind, attempts + 1);

        return HeuristicOpinion.of(
                NAME,
                1.0,
                MutationAcceptanceState.ACCEPTED,
                GoalProgressState.PROGRESSING,
                matchKind + " mutation while COMPILE_ERROR is active — LLM cannot evaluate pre-merge; " +
                        "deferring to post-merge BuildExecutor"
        );
    }

    private boolean touchesBuildConfig(EvolutionOutcome evolution) {
        List<com.walpro.autobuilder.agent.evolution.model.FileMutation> files =
                evolution.addedOrChangedFiles();
        if (files == null) return false;
        return files.stream().anyMatch(f -> f != null && f.path() != null
                && fileRoleClassifier.rolesOf(f.path()).contains(FileRole.BUILD_DESCRIPTOR));
    }

    /**
     * T2-DQ: returns true when the mutation touches a file whose basename matches a Java
     * file named in any {@code <path>.java:[line,col]} prefix of the build stderr. Basename
     * comparison keeps the check robust against directory-prefix differences between the
     * compiler's absolute paths and our relative {@code output/...} mutation paths.
     */
    /**
     * T2-FE: does the candidate edit a plausible PRODUCER for a wrapping disagreement the compiler
     * reported? Narrow by construction — it requires
     * {@link com.walpro.autobuilder.core.build.ContainerMismatchExtractor} to have found a
     * {@code X cannot be converted to Container<X>} diagnostic, and then admits only a file that
     * DECLARES a method returning that same type. Declaration, not mention: the consumer file
     * names the type constantly and is already covered by the stderr-named rule.
     *
     * <p>This makes the correct repair permissible. It does not instruct anyone to make it — the
     * evolution block already does that, and the two were pulling in opposite directions.
     */
    private static boolean touchesContainerMismatchProducer(EvolutionOutcome evolution,
                                                            String stderrTail) {
        if (stderrTail == null || stderrTail.isBlank()) return false;
        List<com.walpro.autobuilder.agent.evolution.model.FileMutation> files =
                evolution.addedOrChangedFiles();
        if (files == null || files.isEmpty()) return false;

        List<ContainerMismatchExtractor.Entry> mismatches =
                ContainerMismatchExtractor.extract(stderrTail);
        if (mismatches.isEmpty()) return false;

        for (ContainerMismatchExtractor.Entry mismatch : mismatches) {
            Pattern declaresProducer = Pattern.compile(
                    "(?m)^[^/*\\n]*\\b(?:" + Pattern.quote(mismatch.containerType())
                            + "\\s*<\\s*)?" + Pattern.quote(mismatch.plainType())
                            + "\\s*>?\\s+\\w+\\s*\\(");
            for (com.walpro.autobuilder.agent.evolution.model.FileMutation file : files) {
                if (file == null || file.content() == null) continue;
                if (declaresProducer.matcher(file.content()).find()) return true;
            }
        }
        return false;
    }

    private static boolean touchesStderrNamedFile(EvolutionOutcome evolution, String stderrTail) {
        if (stderrTail == null || stderrTail.isBlank()) return false;
        List<com.walpro.autobuilder.agent.evolution.model.FileMutation> files =
                evolution.addedOrChangedFiles();
        if (files == null || files.isEmpty()) return false;

        Set<String> stderrFileNames = new LinkedHashSet<>();
        Matcher m = FILE_COORD_PREFIX.matcher(stderrTail);
        while (m.find()) {
            String path = m.group(1);
            int slash = path.lastIndexOf('/');
            stderrFileNames.add(slash >= 0 ? path.substring(slash + 1) : path);
        }
        if (stderrFileNames.isEmpty()) return false;

        return files.stream().anyMatch(f -> {
            if (f == null || f.path() == null) return false;
            String name = f.path().contains("/")
                    ? f.path().substring(f.path().lastIndexOf('/') + 1)
                    : f.path();
            return stderrFileNames.contains(name);
        });
    }
}
