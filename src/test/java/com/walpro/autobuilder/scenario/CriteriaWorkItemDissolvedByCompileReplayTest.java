package com.walpro.autobuilder.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildErrorClassifierAgent;
import com.walpro.autobuilder.core.build.TestBuildToolRegistry;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.graph.goal.GoalGraphService;

/**
 * Replays run {@code 2026-08-30_00-06-00} (clitarget), which closed {@code GOAL_FULFILLED} at
 * 17/19 with the two multi-line-quoted-field checks failing — a requirement the seed states
 * twice, and one the loop had correctly identified as unmet 48 seconds earlier.
 *
 * <p>The archived chain, timestamps from {@code autobuilder.log}:
 * <ol>
 *   <li>00:15:12 — the completion edge refuses closure and injects a work item carrying the four
 *   unconfirmed criteria verbatim ("A file containing a quoted field with an embedded newline is
 *   parsed as a single logical row", …). Correct: the artifact's row reader really was wrong.</li>
 *   <li>00:16:00 — a merge compiles, and the COMPILE_OK cleanup retires that work item as
 *   SUBSUMED. It was tagged {@code [BUILD_FIX]} because the injection templates share a
 *   description prefix, and the cleanup read the tag as "build health, owned by a gate".</li>
 *   <li>00:16:17 — with nothing ACTIVE left, the ledger finds every subgoal accounted for
 *   (7 subsumed) and the root completes.</li>
 * </ol>
 *
 * <p>An observation may only retire the work items raised by the check that made it: compiling
 * says nothing about whether an acceptance criterion is met. The item is now stamped with its
 * originating gate and survives the compile observation, so the root cannot close while the
 * requirement it names is outstanding.
 */
@ExtendWith(MockitoExtension.class)
class CriteriaWorkItemDissolvedByCompileReplayTest {

    private static final String ROOT = "goal-bc97260b";
    private static final String CRITERIA_ITEM = "goal-ba65c7b0";

    /** The four criteria the completion edge listed as unconfirmed at 00:15:12. */
    private static final List<String> UNCONFIRMED = List.of(
            "A file containing a quoted field with an embedded newline is parsed as a single logical row",
            "The reader correctly tracks the physical line number where each logical row starts",
            "check <file> prints \"line <n>: expected <h> fields, found <f>\" for each malformed row,"
                    + " where <n> is the physical line number of the start of that logical row",
            "count <file> prints the number of logical data rows (excluding header) as a bare integer to stdout");

    @Mock private FileService fileService;
    @Mock private GoalGraphService goalGraphService;
    @Mock private GoalPlannerConfigProperties goalPlannerConfig;
    @Mock private BuildErrorClassifierAgent buildErrorClassifierAgent;

    private GoalPlannerService goalPlanner;
    private BlockerSubgoalInjector injector;

    @BeforeEach
    void setUp() throws Exception {
        lenient().doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());

        goalPlanner = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        injector = new BlockerSubgoalInjector(goalPlanner, buildErrorClassifierAgent, fileService,
                TestBuildToolRegistry.classifier());

        GoalEntity root = new GoalEntity(ROOT,
                "Evolve the Java 21 application into a COMMAND-LINE tool that validates and"
                        + " summarises delimited text files. A field MAY be wrapped in double quotes,"
                        + " and a quoted field may contain commas and newlines.",
                "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);

        // The parser subgoal that completed on a contradictory frame ("splits a physical line"),
        // which is why the criteria above were still unmet when it was marked COMPLETED.
        GoalEntity parser = new GoalEntity("goal-de6eab7c",
                "Implement the shared CSV field parser that splits a physical line into fields",
                "planner", GoalType.SUBGOAL, ROOT);
        parser.setStatus(GoalStatus.COMPLETED);

        GoalEntity criteriaItem = new GoalEntity(CRITERIA_ITEM,
                "[BUILD_FIX] Implement the unconfirmed acceptance criteria against the current code"
                        + " — 4 acceptance criterion(s) not confirmed in the current code",
                "completion-edge", GoalType.SUBGOAL, ROOT);
        criteriaItem.setStatus(GoalStatus.ACTIVE);
        criteriaItem.getMetadata().put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        criteriaItem.getMetadata().put(BlockerTypes.ORIGINATING_GATE_KEY, BlockerTypes.GATE_CRITERIA);
        criteriaItem.getMetadata().put("acceptanceCriteria", UNCONFIRMED);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(parser.getId(), parser);
        goalMap.put(criteriaItem.getId(), criteriaItem);

        ReflectionTestUtils.setField(goalPlanner, "goalMap", goalMap);
        ReflectionTestUtils.setField(goalPlanner, "rootGoalId", ROOT);
        ReflectionTestUtils.setField(goalPlanner, "currentSubGoalId", CRITERIA_ITEM);
    }

    @Test
    void compileOkDoesNotRetireTheWorkItemRaisedByTheCriteriaGate() {
        injector.clearActiveBuildFixSubgoals();

        assertThat(goalPlanner.getGoalById(CRITERIA_ITEM).getStatus())
                .as("the requirements it names are still unmet — compiling does not answer them")
                .isEqualTo(GoalStatus.ACTIVE);
    }

    @Test
    void theRootStaysOpenWhileTheCriteriaWorkItemIsOutstanding() {
        injector.clearActiveBuildFixSubgoals();

        goalPlanner.autoCompleteRootIfSatisfied();

        assertThat(goalPlanner.getGoalById(ROOT).getStatus())
                .as("closing here is the 17/19 false completion the run produced")
                .isEqualTo(GoalStatus.ACTIVE);
    }

    /**
     * The plain compile fix — no originating gate — is what a COMPILE_OK observation can speak
     * for, and it is still retired. Narrowing the cleanup must not switch it off.
     */
    @Test
    void compileOkStillRetiresAPlainCompileFix() {
        GoalEntity compileFix = new GoalEntity("goal-compile-fix",
                "Fix compile error: cannot find symbol MultiLineRowReader",
                "injector", GoalType.SUBGOAL, ROOT);
        compileFix.setStatus(GoalStatus.ACTIVE);
        compileFix.getMetadata().put(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        @SuppressWarnings("unchecked")
        Map<String, GoalEntity> goalMap =
                (Map<String, GoalEntity>) ReflectionTestUtils.getField(goalPlanner, "goalMap");
        goalMap.put(compileFix.getId(), compileFix);
        ReflectionTestUtils.setField(goalPlanner, "currentSubGoalId", compileFix.getId());

        injector.clearActiveBuildFixSubgoals();

        assertThat(goalPlanner.getGoalById("goal-compile-fix").getStatus())
                .isEqualTo(GoalStatus.SUBSUMED);
    }
}
