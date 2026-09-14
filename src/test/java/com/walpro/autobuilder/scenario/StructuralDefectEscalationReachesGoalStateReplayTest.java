package com.walpro.autobuilder.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.walpro.autobuilder.agent.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.heuristic.HeuristicOpinion;
import com.walpro.autobuilder.analysis.heuristic.StoreCoherenceHeuristic;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildErrorClassifierAgent;
import com.walpro.autobuilder.core.build.TestBuildToolRegistry;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;

/**
 * T2-ES end to end without the model: the frozen candidate from run
 * {@code 2026-09-13_09-44-03} goes in, and a work item naming the ways out comes out of REAL goal
 * state.
 *
 * <p>Sibling of {@link ParallelStoreEscalationFromFrozenCandidateReplayTest}, which stops at the
 * finding. This one carries it through the real {@link BlockerSubgoalInjector} and the real
 * {@link GoalPlannerService} — only the graph and filesystem edges are mocked — so the assertions
 * are about goal state rather than about which methods were called. The unit test for the injector
 * mocks the planner, and a verified call is not a work item.
 *
 * <p><b>What this closes and what it cannot.</b> The escalation has two unproven links: that the
 * work item reaches goal state correctly formed, and that the model's NEXT proposal then implements
 * one of the paths. The first is decided entirely by deterministic machinery and is settled here.
 * The second needs the model and stays open for a field run — no frozen artifact can answer it.
 */
@ExtendWith(MockitoExtension.class)
class StructuralDefectEscalationReachesGoalStateReplayTest {

    private static final String SCENARIO = "parallel-store-rejected-2026-09-13";
    private static final String ROOT = "goal-root";
    private static final String SUBGOAL = "goal-endpoint";

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
                "Evolve the Java 21 application into a REST service for a small library.",
                "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);

        // The subgoal the run was on while the seven rejections accumulated.
        GoalEntity subgoal = new GoalEntity(SUBGOAL,
                "Implement the book storage layer", "planner", GoalType.SUBGOAL, ROOT);
        subgoal.setStatus(GoalStatus.ACTIVE);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(subgoal.getId(), subgoal);

        ReflectionTestUtils.setField(goalPlanner, "goalMap", goalMap);
        ReflectionTestUtils.setField(goalPlanner, "rootGoalId", ROOT);
        ReflectionTestUtils.setField(goalPlanner, "currentSubGoalId", SUBGOAL);
    }

    /** The finding the real heuristic makes of the real frozen candidate. */
    private HeuristicOpinion findingFromFrozenTree() throws IOException {
        Path root = Path.of("src/test/resources/scenarios", SCENARIO, "tree");
        Map<String, String> tree = new HashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                tree.put(root.relativize(p).toString(), Files.readString(p));
            }
        }
        String storePath = "src/main/java/com/example/library/domain/store/BookStore.java";
        Map<String, String> previous = new HashMap<>(tree);
        previous.remove(storePath);

        CycleContextData context = CycleContextData.builder()
                .cycleName("replay")
                .previousOutput(previous)
                .projectDiff(new ProjectDiffResult(null, Map.of(storePath, tree.get(storePath)), Set.of()))
                .build();

        return new StoreCoherenceHeuristic().evaluate(context,
                new EvaluationOutcome(0.85, MutationAcceptanceState.ACCEPTED,
                        GoalProgressState.PROGRESSING, null, null));
    }

    /** The escalation the third consecutive cycle would fire. */
    private GoalEntity escalate() throws IOException {
        HeuristicOpinion finding = findingFromFrozenTree();
        injector.injectStructuralDefectRedirection(finding.name(),
                finding.defect().subject(), finding.defect().resolutionPaths(), 3);
        return goalPlanner.getGoalById(goalPlanner.getCurrentSubGoal().getId());
    }

    /**
     * The work item exists, is what the loop works next, and its acceptance criteria are the two
     * ways out — so satisfying EITHER clears it. Asserted against goal state, not against calls.
     */
    @Test
    void theEscalationBecomesAWorkItemWhoseCriteriaAreTheWaysOut() throws IOException {
        GoalEntity injected = escalate();

        assertThat(injected.getId()).isNotEqualTo(SUBGOAL);
        assertThat(injected.getStatus()).isEqualTo(GoalStatus.ACTIVE);

        Object criteria = injected.getMetadata().get("acceptanceCriteria");
        assertThat(criteria).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> banked = (List<String>) criteria;

        assertThat(banked).hasSize(2);
        assertThat(banked)
                .anySatisfy(c -> assertThat(c).startsWith("BookRepository is the only type")
                        .contains("BookStore"))
                .anySatisfy(c -> assertThat(c).startsWith("BookStore is the only type")
                        .contains("BookRepository"));
    }

    /**
     * The description is a framework-generated instruction about the loop's own state. Banking one
     * as an acceptance criterion makes it unconfirmable against the code forever once the defect is
     * fixed, which walled two runs on 2026-07-04 (T1-FF/T1-FG).
     */
    @Test
    void theInstructionTextIsNeverBankedAsACriterion() throws IOException {
        GoalEntity injected = escalate();

        assertThat(BlockerTypes.isDiagnosticInstruction(injected.getDescription())).isTrue();

        @SuppressWarnings("unchecked")
        List<String> banked = (List<String>) injected.getMetadata().get("acceptanceCriteria");
        assertThat(banked).noneMatch(BlockerTypes::isDiagnosticInstruction);
        assertThat(banked).doesNotContain(injected.getDescription());
    }

    /**
     * The defect compiles — a parallel store is invisible to javac — so a compile observation can
     * attest to nothing about it. Verified here against real goal state rather than by a never()
     * on a mock: the item must still be ACTIVE after the sweep that retires plain compile fixes.
     */
    @Test
    void aCompileObservationCannotRetireIt() throws IOException {
        GoalEntity injected = escalate();

        injector.clearActiveBuildFixSubgoals();

        assertThat(goalPlanner.getGoalById(injected.getId()).getStatus())
                .as("compiling says nothing about whether the entity is still stored twice")
                .isEqualTo(GoalStatus.ACTIVE);
    }
}
