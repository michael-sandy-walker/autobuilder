package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import com.walpro.autobuilder.analysis.structure.InputFormatExample;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * T4-DK: which of a work item's acceptance criteria some probe can actually exercise against the
 * running artifact — asked once, for every target shape, instead of once per shape.
 *
 * <p><b>The defect this exists to close.</b> {@link BehaviouralClosureVerifier} selected its
 * probe list with {@link BehaviouralCriterionParser#exercisable}, which reads a verb-and-path
 * mention and therefore only ever answers for an artifact that serves HTTP. On a target with no
 * network surface the list came back empty at every closure, the verifier abstained, and the only
 * evidence left at the completion edge was a model reading the source. The capability to exercise
 * such a target was already built and already wired into {@link BootGate}
 * ({@link CommandCriterionParser}, {@link CommandExerciseSynthesizer}) — it was simply never
 * reachable from the closure edge ([[project-capability-outruns-routing]]).
 *
 * <p><b>The run that shows what it cost.</b> {@code 2026-08-30_00-06-00} on the delimited-text
 * command-line target. At 00:15:12 the source-reading verifier answered {@code ✗} for four
 * criteria and the gate refused closure, correctly. At 00:16:16 — 64 seconds and one merge later
 * — the same reading answered {@code ✓} for all seventeen and the root completed. The artifact
 * fails the two yardstick checks covering the requirement the seed states twice. Throughout,
 * {@code T4-CU} logged <i>"none of its 4 criterion(s) can be executed"</i>: the executable check
 * had no opinion because it could not form one, not because the criteria were unexercisable.
 *
 * <p><b>Why this is a selection and not a verdict.</b> It answers only whether a probe can be
 * BUILT, cheaply and in memory, so the expensive steps stay behind a decision that costs nothing
 * — the property T1-FW added to the HTTP path and the reason that path asks {@code exercisable}
 * rather than {@code parse}. Whether the artifact then satisfies the criterion is the gate's
 * question, decided by running it.
 */
@Component
public class ExercisableCriterionQuery {

    private final CommandSurfaceQuery commandSurfaceQuery;

    /**
     * The launch spelling the boot gate runs the artifact with, read from the same property, so a
     * criterion naming an invocation is recognised here exactly as it is there. Config data, not
     * an ecosystem literal ([[feedback-no-ecosystem-jargon-in-framework-code]]).
     */
    private final String commandTemplate;

    public ExercisableCriterionQuery(
            CommandSurfaceQuery commandSurfaceQuery,
            @org.springframework.beans.factory.annotation.Value(
                    "${autobuilder.build.boot-gate.command:java -jar {artifact}}") String commandTemplate) {
        this.commandSurfaceQuery = commandSurfaceQuery;
        this.commandTemplate = commandTemplate;
    }

    /**
     * The criteria worth booting the artifact for.
     *
     * <p>{@code specText} is what makes the command-line half answerable: a criterion naming a
     * declared operation ("count &lt;file&gt; prints … as a bare integer") is exercisable only
     * against the surface and worked example the specification itself states. The HTTP half needs
     * no specification and is unaffected by an absent one, so a caller with no spec still gets
     * exactly the selection it got before.
     *
     * @param criteria the work item's criteria; null entries are ignored
     * @param specText the specification as stated, or null/blank when the caller has none
     */
    public List<String> exercisable(List<String> criteria, String specText) {
        if (criteria == null || criteria.isEmpty()) return List.of();
        Map<String, CommandSurfaceQuery.Command> surface =
                specText == null || specText.isBlank() ? Map.of() : commandSurfaceQuery.declaredInSpec(specText);
        Optional<String> unrecognised =
                specText == null || specText.isBlank() ? Optional.empty() : commandSurfaceQuery.unrecognisedWord(specText);
        Optional<InputFormatExample.Example> format =
                specText == null || specText.isBlank() ? Optional.empty() : InputFormatExample.readFrom(specText);
        return criteria.stream()
                .filter(c -> c != null && !c.isBlank())
                .filter(c -> BehaviouralCriterionParser.exercisable(c).isPresent()
                        || CommandExerciseSynthesizer.synthesize(c, surface, unrecognised, format, specText).isPresent())
                .toList();
    }

    /**
     * The criteria addressed to the target's SURFACE at all, exercisable or not — a superset of
     * {@link #exercisable}.
     *
     * <p><b>Why this lives here and not in the reader that wants it.</b> `CriteriaExecutabilityReadout`
     * answered it with its own {@code \b(GET|POST|PUT|PATCH|DELETE)\s+/} regex: HTTP vocabulary in
     * an observability class, and a second derivation of a question this class already answers one
     * way. Two homes for one question are free to disagree, and the readout's own javadoc records
     * the near-miss — swapping its predicate naively would have produced a NEGATIVE shortfall.
     *
     * <p>The superset relation is the invariant to preserve: anything exercisable addresses the
     * surface by construction, so the decomposition {@code addressingSurface - executable} can
     * never go negative. Addressing WITHOUT being exercisable is the interesting middle — a REST
     * criterion naming a path variable the probe cannot ground, or two endpoints at once.
     */
    public List<String> addressingSurface(List<String> criteria, String specText) {
        if (criteria == null || criteria.isEmpty()) return List.of();
        java.util.Set<String> canProbe = new java.util.HashSet<>(exercisable(criteria, specText));
        Map<String, CommandSurfaceQuery.Command> surface =
                specText == null || specText.isBlank() ? Map.of() : commandSurfaceQuery.declaredInSpec(specText);
        return criteria.stream()
                .filter(c -> c != null && !c.isBlank())
                .filter(c -> canProbe.contains(c)
                        || BehaviouralCriterionParser.mentionsSurface(c)
                        || namesADeclaredCommand(c, surface))
                .toList();
    }

    /** A criterion naming an operation the specification declares — the command half's equivalent. */
    private static boolean namesADeclaredCommand(String criterion,
                                                 Map<String, CommandSurfaceQuery.Command> surface) {
        return surface.keySet().stream().anyMatch(w -> Pattern.compile(
                        "(?i)(?:^|[^a-z0-9_-])" + Pattern.quote(w) + "(?:[^a-z0-9_-]|$)")
                .matcher(criterion).find());
    }

    /**
     * T2-FL: what this criterion ASSERTS, in the canonical spelling the boot gate records when it
     * observes an assertion holding — or empty when no probe of any shape can read one out.
     *
     * <p><b>Why a claim and not the criterion's own words.</b> The evidence a work item needs is
     * "was this requirement observed to hold?", and the requirement reaches the probe in the
     * specification's wording while the item states it in its author's. Matching prose answers no
     * to a question whose answer is yes: run {@code 2026-09-22_15-59-33} ended at its deadline
     * with four leaves stating endpoints the artifact served and the sweep had probed. The
     * assertion is the identity both spellings share.
     *
     * <p><b>Why it lives here.</b> This class already exists to ask one question of every target
     * shape rather than once per shape ({@code T4-DK}), and it already holds both readers. A
     * caller gets an answer for an artifact that serves and for one that terminates without
     * knowing which it is holding — which is the whole of the non-service support: nothing above
     * this line names a protocol.
     *
     * <p>Abstains on exactly the terms its readers do. A criterion that does not parse into one
     * assertion has no claim, and empty always means "no opinion", never "unmet".
     */
    public Optional<String> claimOf(String criterion, String specText) {
        if (criterion == null || criterion.isBlank()) return Optional.empty();

        Optional<BehaviouralCriterionParser.Assertion> served =
                BehaviouralCriterionParser.exercisable(criterion);
        if (served.isPresent()) return Optional.of(served.get().describe());

        List<String> launchTokens = CommandCriterionParser.launchTokensOf(commandTemplate);
        if (!launchTokens.isEmpty()) {
            Optional<CommandCriterionParser.CommandAssertion> invoked =
                    CommandCriterionParser.parse(criterion, launchTokens);
            if (invoked.isPresent()) return Optional.of(invoked.get().describe());
        }

        if (specText == null || specText.isBlank()) return Optional.empty();
        Optional<CommandExerciseSynthesizer.Exercise> exercised = CommandExerciseSynthesizer.synthesize(
                criterion,
                commandSurfaceQuery.declaredInSpec(specText),
                commandSurfaceQuery.unrecognisedWord(specText),
                InputFormatExample.readFrom(specText),
                specText);
        return exercised.map(CommandExerciseSynthesizer.Exercise::describe);
    }
}
