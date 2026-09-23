package com.walpro.autobuilder.core.build;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * T2-FL: a criterion asserting that the artifact RUNS, as opposed to one asserting what it then
 * does.
 *
 * <p><b>The gap this closes.</b> The two existing readers answer for a request
 * ({@link BehaviouralCriterionParser}) and for an invocation and its exit status
 * ({@link CommandCriterionParser}). Neither can read a sentence that asserts only the launch, so
 * {@code ExercisableCriterionQuery.claimOf} returned empty for one and the leaf stating it was
 * declined as unreadable. Measured on run {@code 2026-09-23_18-35-30}: three of twenty-nine
 * declines read <i>no probe of any shape can read a claim out of "The application starts
 * successfully without exiting with code 1."</i> — while that run's boot gate ran twice and
 * establishes exactly that on its own clean path. The authority was consulted and its answer was
 * unreadable for want of a parse ([[project-invented-facts-have-authorities]]).
 *
 * <p><b>Why the whole sentence must be the assertion.</b> The claim this produces is recorded by
 * the gate on a clean launch, so any criterion mapped onto it is satisfied by the artifact merely
 * running. A criterion that says <i>"the application starts successfully AND serves all three
 * resource types"</i> asserts something the launch does not establish, and reading it as this
 * claim would promote a leaf on evidence for half of it — the bookkeeping shape of
 * [[project-false-completion-4]]. So the match is anchored to the entire criterion and anything
 * carrying a further clause abstains. Empty always means "no opinion", never "unmet".
 *
 * <p><b>Neutral by construction.</b> Nothing here names a protocol, a framework or a packaging
 * format ([[feedback-no-ecosystem-jargon-in-framework-code]]): a launch is the one event every
 * target shape has, and the serving and terminating paths of {@link BootGate} both record this
 * same claim when they establish it.
 */
final class BootOutcomeCriterionParser {

    private BootOutcomeCriterionParser() {
    }

    /**
     * The canonical spelling, in the {@code subject → outcome} form the other parsers describe
     * their assertions in, so one observation's claim list reads uniformly whatever produced it.
     */
    static final String CLAIM = "launch → runs";

    /** What the criterion can call the thing that runs. Deliberately no packaging noun. */
    private static final String SUBJECT =
            "(?:the\\s+)?(?:application|app|artifact|program|service|tool|executable|system)";

    /** Starting, in the spellings the field corpus uses. */
    private static final String STARTS =
            "(?:starts?(?:\\s+up)?|boots?(?:\\s+up)?|launches|runs|comes?\\s+up|starts?\\s+running)";

    /**
     * The success qualifier. A bare "the application starts" is NOT enough: it states no outcome,
     * and a criterion with no outcome is the shape the surrounding gate is supposed to abstain on.
     */
    private static final String CLEANLY =
            "(?:successfully|without\\s+(?:any\\s+)?(?:error|errors|failure|failures|crashing|failing)"
            + "|cleanly|and\\s+stays?\\s+up|without\\s+exiting"
            + "(?:\\s+with\\s+(?:a\\s+)?(?:non-?zero|nonzero)?\\s*(?:exit\\s+)?(?:code|status)?\\s*\\d*)?"
            + "|and\\s+does\\s+not\\s+exit"
            + "(?:\\s+with\\s+(?:a\\s+)?(?:non-?zero|nonzero)?\\s*(?:exit\\s+)?(?:code|status)?\\s*\\d*)?)";

    /**
     * Qualifiers come in runs: the field string this reader was built for carries two of them
     * ("starts successfully WITHOUT EXITING WITH CODE 1"), and a single-qualifier pattern read it
     * as a sentence with an unexplained tail and abstained -- which is the whole defect, one
     * level down.
     */
    private static final String CLEANLY_RUN =
            CLEANLY + "(?:\\s*,?\\s*(?:and\\s+)?" + CLEANLY + ")*";

    /**
     * Anchored end to end, so a criterion asserting anything BEYOND the launch falls through to
     * the readers that can judge the rest of it. Trailing punctuation only.
     */
    private static final Pattern WHOLE_SENTENCE = Pattern.compile(
            "(?i)^\\s*" + SUBJECT + "\\s+" + STARTS + "\\s+" + CLEANLY_RUN + "\\s*[.!]?\\s*$");

    /**
     * The claim this criterion asserts, or empty when it asserts something else or something more.
     */
    static Optional<String> parse(String criterion) {
        if (criterion == null || criterion.isBlank()) return Optional.empty();
        return WHOLE_SENTENCE.matcher(criterion).matches() ? Optional.of(CLAIM) : Optional.empty();
    }
}
