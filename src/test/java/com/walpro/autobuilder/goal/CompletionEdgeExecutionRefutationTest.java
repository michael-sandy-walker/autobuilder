package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService;
import com.walpro.autobuilder.analysis.structure.CommandSurfaceQuery;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.ExercisableCriterionQuery;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T4-DT: the specification's own obligations are run against the artifact before a model is asked
 * to read the source for them.
 *
 * <p>Seed requirements used to reach {@code verifyCompletion} alone. Run
 * {@code 2026-09-10_21-46-44} closed {@code GOAL_FULFILLED} at 15/19 with that model calling <i>"a
 * file containing {@code a,"b,c","d""e"} is parsed into one row with three fields"</i> satisfied;
 * running the artifact reports two. The oracle for that claim — the parse the seed writes out
 * itself — was in the specification the whole time and never reached anything that could execute.
 *
 * <p>These tests drive the REAL completion edge, so they pin the ordering as well as the outcome:
 * where the probe speaks it outranks the reading and the model is not asked at all; where it
 * abstains, the path is exactly what it was.
 */
@ExtendWith(MockitoExtension.class)
class CompletionEdgeExecutionRefutationTest {

    @Mock private FileService fileService;
    @Mock private GoalGraphService goalGraphService;
    @Mock private GoalPlannerConfigProperties goalPlannerConfig;

    /** A specification stating obligations a probe can be built for, worked example included. */
    private static final String SEED = """
            Evolve the Java 21 application into a COMMAND-LINE tool that summarises delimited files.

            FIELD SYNTAX. Fields are separated by commas. A field MAY be wrapped in double quotes.
            So the line  a,"b,c","d""e"  has THREE fields: a / b,c / d"e.

            count <file> — print the number of data rows to stdout as a bare integer.
            column <file> <name> — print every value of the named column, one per line.
            Exit 3 when the command word is not one of the two above, printing
            "unknown command: <word>" to stderr.
            """;

    private GoalPlannerService serviceWith(CriteriaVerificationService verifier,
                                           ClosureClaimVerifier closureVerifier) throws IOException {
        lenient().doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        service.setCriteriaVerificationService(verifier);
        service.setClosureClaimVerifier(closureVerifier);
        service.setExercisableCriterionQuery(new ExercisableCriterionQuery(new CommandSurfaceQuery(), "java -jar {artifact}"));
        service.setCurrentCycleContext(CycleContextData.builder()
                .cycleName("execution-refutation")
                .outputPath("output")
                .previousOutput(Map.of("src/main/java/com/example/Main.java", "class Main {}"))
                .build());

        GoalEntity root = new GoalEntity("G", SEED, "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity done = new GoalEntity("D", "Implement the reader", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(GoalStatus.SUBSUMED);
        done.getMetadata().put("acceptanceCriteria", List.of("The reader class exists"));

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(done.getId(), done);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");
        return service;
    }

    @Test
    void anArtifactThatContradictsTheSpecificationBlocksTheRootAndTheModelIsNeverAsked() throws IOException {
        CriteriaVerificationService verifier = mock(CriteriaVerificationService.class);
        ClosureClaimVerifier refuting = (goalId, criteria, specText) ->
                Optional.of(new ClaimRefutation(
                        "\"column <file> <name> — print every value of the named column\" — "
                        + "column <well_formed_file> <present-name>, but it printed \"b,c,de\" "
                        + "where the specification states \"b,c\"", criteria));

        GoalPlannerService service = serviceWith(verifier, refuting);
        service.autoCompleteRootIfSatisfied();

        assertThat(service.getGoalById("G").getStatus())
                .as("an executed contradiction must not complete the root")
                .isEqualTo(GoalStatus.ACTIVE);
        // Where the probe ran the tool, asking a model to read the source adds nothing and can only
        // disagree with an observation. It is not asked.
        verify(verifier, never()).verifyCompletion(any(), any(), any());
    }

    @Test
    void theWorkItemCarriesTheGateStampAndADoneWhenTheProbeCanRecheck() throws IOException {
        CriteriaVerificationService verifier = mock(CriteriaVerificationService.class);
        ClosureClaimVerifier refuting = (goalId, criteria, specText) ->
                Optional.of(new ClaimRefutation(
                        "the running artifact printed \"b,c,de\" where the specification states \"b,c\"",
                        criteria));

        GoalPlannerService service = serviceWith(verifier, refuting);
        service.autoCompleteRootIfSatisfied();

        GoalEntity item = service.getAllGoals().stream()
                .filter(g -> g.getStatus() == GoalStatus.ACTIVE && g.getDescription() != null
                        && g.getDescription().contains("contradicts the specification"))
                .findFirst().orElseThrow();

        // Without the stamp a COMPILE_OK observation retires this item, which is how run
        // 2026-08-30_00-06-00 completed with the requirement still unmet.
        assertThat(item.getMetadata().get(BlockerTypes.ORIGINATING_GATE_KEY))
                .isEqualTo(BlockerTypes.GATE_CRITERIA);

        // The done-when is the EXERCISABLE subset. An obligation no probe can judge would sit here
        // unverifiable and hold the item open for the run's life, which is walling.
        Object doneWhen = item.getMetadata().get("acceptanceCriteria");
        assertThat(doneWhen).isInstanceOf(List.class);
        assertThat((List<?>) doneWhen).isNotEmpty();
    }

    @Test
    void whenTheProbeAbstainsTheModelIsAskedExactlyAsBefore() throws IOException {
        // The common case, and the one that must not change: nothing packageable, nothing
        // exercisable, no verifier wired. Absence of evidence is not a refutation.
        CriteriaVerificationService verifier = mock(CriteriaVerificationService.class);
        when(verifier.verifyCompletion(any(), any(), any()))
                .thenReturn(CriteriaVerificationService.CompletionVerification.blocked(
                        "1 acceptance criterion(s) not confirmed in the current code: The reader class exists",
                        List.of("The reader class exists")));
        ClosureClaimVerifier abstaining = (goalId, criteria, specText) -> Optional.empty();

        GoalPlannerService service = serviceWith(verifier, abstaining);
        service.autoCompleteRootIfSatisfied();

        verify(verifier).verifyCompletion(any(), any(), any());
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }

    /**
     * T4-CQ: no banked criterion is not a reason to skip the specification. The first adopted
     * project's subgoal criteria were all loop-authored, the gate was skipped whole, and the root
     * closed with one of seven required tests written (run 2026-09-24_12-09-57).
     */
    @Test
    void withNoBankedCriterionTheSpecificationsOwnObligationsAreStillChecked() throws IOException {
        CriteriaVerificationService verifier = mock(CriteriaVerificationService.class);
        when(verifier.verifyCompletion(any(), any(), any()))
                .thenReturn(CriteriaVerificationService.CompletionVerification.blocked(
                        "1 acceptance criterion(s) not confirmed in the current code: count <file>",
                        List.of("count <file> prints the number of data rows")));
        ClosureClaimVerifier abstaining = (goalId, criteria, specText) -> Optional.empty();

        GoalPlannerService service = serviceWith(verifier, abstaining);
        service.getGoalById("D").getMetadata().remove("acceptanceCriteria");
        service.autoCompleteRootIfSatisfied();

        verify(verifier).verifyCompletion(any(), any(), any());
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.ACTIVE);
    }
}
