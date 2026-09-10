package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import com.walpro.autobuilder.analysis.structure.InputFormatExample;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
@RequiredArgsConstructor
public class ExercisableCriterionQuery {

    private final CommandSurfaceQuery commandSurfaceQuery;

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
                        || CommandExerciseSynthesizer.synthesize(c, surface, unrecognised, format).isPresent())
                .toList();
    }
}
