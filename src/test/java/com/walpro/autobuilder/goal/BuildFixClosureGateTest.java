package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.core.blocker.BlockerSubgoalInjector;
import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildFixClosureGateTest {

    @Mock(strictness = Mock.Strictness.LENIENT) private GoalPlannerService goalPlannerService;
    @Mock private BehaviourLogger behaviourLogger;

    private BuildFixClosureGate gate;

    @BeforeEach
    void setUp() {
        gate = new BuildFixClosureGate(goalPlannerService);
        gate.setBehaviourLogger(behaviourLogger);
    }

    @Test
    void close_compileOk_closesAllActiveBuildFix() {
        GoalEntity current = buildFixGoal("goal-1", "Fix compile error in Foo.java: ...",
                Map.of(BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_SYMBOL, "Bar",
                       BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_FILE, "src/main/java/Foo.java"));
        GoalEntity other = buildFixGoal("goal-2", "Fix compile error in Baz.java: ...",
                Map.of(BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_SYMBOL, "Qux",
                       BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_FILE, "src/main/java/Baz.java"));
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(current);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(other));

        BuildOutcome ok = new BuildOutcome(BuildResult.COMPILE_OK, "", 100L, "mvn compile", null);
        List<String> closed = gate.closeObsoleteBuildFixSubgoals(ok, "cycle-1");

        assertThat(closed).containsExactlyInAnyOrder("goal-1", "goal-2");
        verify(goalPlannerService).transitionGoal(eq("goal-1"), eq(GoalStatus.SUBSUMED),
                anyString(), eq(SubsumptionReason.COVERED));
        verify(goalPlannerService).transitionGoal(eq("goal-2"), eq(GoalStatus.SUBSUMED),
                anyString(), eq(SubsumptionReason.COVERED));
        verify(behaviourLogger).buildFixSubgoalClosed(eq("cycle-1"), eq("goal-1"),
                eq("Bar"), eq("src/main/java/Foo.java"), eq("compile_ok"));
    }

    @Test
    void close_compileError_diagnosticStillPresent_keepsSubgoal() {
        GoalEntity current = buildFixGoal("goal-live", "Fix compile error in Foo.java: cannot find symbol Bar",
                Map.of(BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_SYMBOL, "Bar",
                       BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_FILE, "src/main/java/Foo.java"));
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(current);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        BuildOutcome stillBroken = new BuildOutcome(BuildResult.COMPILE_ERROR,
                "[ERROR] /x/src/main/java/Foo.java:[10,5] cannot find symbol: class Bar",
                100L, "mvn compile", null);
        List<String> closed = gate.closeObsoleteBuildFixSubgoals(stillBroken, "cycle-2");

        assertThat(closed).isEmpty();
        verify(goalPlannerService, never()).transitionGoal(anyString(), any(), anyString(), any());
    }

    @Test
    void close_compileError_diagnosticDisappeared_closesSubgoal() {
        GoalEntity phantom = buildFixGoal("goal-phantom",
                "Fix compile error in Foo.java: cannot find symbol getBooks",
                Map.of(BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_SYMBOL, "getBooks",
                       BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_FILE, "src/main/java/Foo.java"));
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(phantom);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        // Build still failing, but with an entirely different diagnostic — neither the symbol
        // `getBooks` nor the path `Foo.java` appears anywhere in the new stderr.
        BuildOutcome differentDiagnostic = new BuildOutcome(BuildResult.COMPILE_ERROR,
                "[ERROR] /x/src/main/java/Other.java:[1,5] cannot find symbol: class Qux",
                100L, "mvn compile", null);
        List<String> closed = gate.closeObsoleteBuildFixSubgoals(differentDiagnostic, "cycle-3");

        assertThat(closed).containsExactly("goal-phantom");
        verify(goalPlannerService).transitionGoal(eq("goal-phantom"), eq(GoalStatus.SUBSUMED),
                anyString(), eq(SubsumptionReason.COVERED));
        verify(behaviourLogger).buildFixSubgoalClosed(eq("cycle-3"), eq("goal-phantom"),
                eq("getBooks"), eq("src/main/java/Foo.java"), eq("diagnostic_disappeared"));
    }

    @Test
    void close_compileError_descriptionFallback_whenMetadataAbsent() {
        // Legacy subgoal filed before T1-EX metadata stamping — description-regex extracts file.
        GoalEntity legacy = buildFixGoal("goal-legacy",
                "Fix compile error in src/main/java/Legacy.java: something",
                new HashMap<>(Map.of(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX)));
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(legacy);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        BuildOutcome unrelated = new BuildOutcome(BuildResult.COMPILE_ERROR,
                "[ERROR] /x/Unrelated.java:[1,1] cannot find symbol",
                100L, "mvn compile", null);
        List<String> closed = gate.closeObsoleteBuildFixSubgoals(unrelated, "cycle-4");

        assertThat(closed).containsExactly("goal-legacy");
    }

    @Test
    void close_nullOutcome_isNoOp() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(
                buildFixGoal("goal-x", "Fix compile error in Foo.java: ...",
                        Map.of(BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_FILE, "Foo.java")));

        List<String> closed = gate.closeObsoleteBuildFixSubgoals(null, "cycle-5");

        assertThat(closed).isEmpty();
        verify(goalPlannerService, never()).transitionGoal(anyString(), any(), anyString(), any());
    }

    @Test
    void close_compileError_noReference_keepsSubgoal_conservativeDefault() {
        // Neither metadata symbol nor file, and description gives no extractable reference.
        // Conservative behaviour: leave the subgoal alone — better to keep a phantom than close
        // a live BUILD_FIX without evidence.
        GoalEntity unrooted = buildFixGoal("goal-unrooted", "BUILD_FIX with no extractable reference",
                new HashMap<>(Map.of(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX)));
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(unrooted);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        BuildOutcome stillBroken = new BuildOutcome(BuildResult.COMPILE_ERROR,
                "[ERROR] some random failure",
                100L, "mvn compile", null);
        List<String> closed = gate.closeObsoleteBuildFixSubgoals(stillBroken, "cycle-6");

        assertThat(closed).isEmpty();
    }

    @Test
    void close_extractReference_prefersMetadataOverDescription() {
        GoalEntity g = buildFixGoal("goal-m",
                "Fix compile error in description/Path.java: symbol DescName",
                Map.of(BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_SYMBOL, "MetaName",
                       BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_FILE, "meta/Path.java"));
        BuildFixClosureGate.BuildFixReference ref = BuildFixClosureGate.extractReference(g);
        assertThat(ref.symbol()).isEqualTo("MetaName");
        assertThat(ref.file()).isEqualTo("meta/Path.java");
    }

    @Test
    void close_compileError_basenameFallback_matchesAbsoluteStderr() {
        GoalEntity g = buildFixGoal("goal-base",
                "Fix compile error in Foo.java: ...",
                Map.of(BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_FILE, "src/main/java/Foo.java",
                       BlockerSubgoalInjector.METADATA_KEY_BUILD_FIX_SYMBOL, "SomeSymbol"));
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(g);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        // stderr has an absolute path with /output/ prefix — full src/main/java/Foo.java is not a
        // substring, but `Foo.java` basename is. Gate should keep the subgoal alive via basename.
        BuildOutcome o = new BuildOutcome(BuildResult.COMPILE_ERROR,
                "[ERROR] /tmp/output/src/main/java/Foo.java:[5,3] cannot find symbol: class Other",
                100L, "mvn compile", null);

        List<String> closed = gate.closeObsoleteBuildFixSubgoals(o, "cycle-7");
        assertThat(closed).isEmpty();
    }

    private static GoalEntity buildFixGoal(String id, String description, Map<String, Object> metaSeed) {
        GoalEntity g = new GoalEntity();
        g.setId(id);
        g.setDescription(description);
        g.setStatus(GoalStatus.ACTIVE);
        Map<String, Object> meta = new HashMap<>(metaSeed);
        meta.putIfAbsent(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        g.setMetadata(meta);
        return g;
    }
}
