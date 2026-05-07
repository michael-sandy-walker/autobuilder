package com.walpro.autobuilder.core.blocker;

import com.walpro.autobuilder.core.build.BlockerTypes;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.GoalStatus;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlockerSubgoalInjectorTest {

    @Mock
    private GoalPlannerService goalPlannerService;

    private BlockerSubgoalInjector injector;

    @BeforeEach
    void setUp() {
        injector = new BlockerSubgoalInjector(goalPlannerService);
    }

    // ── injectBuildFixSubgoalIfAbsent ──

    @Test
    void inject_createsSubgoalWithSetAsCurrent_andWritesMetadata() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        BuildOutcome outcome = buildOutcome("[ERROR] /data/firestarter/pom.xml not found");
        injector.injectBuildFixSubgoalIfAbsent(outcome);

        ArgumentCaptor<String> idCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> descCaptor = ArgumentCaptor.forClass(String.class);
        verify(goalPlannerService).addSubGoal(idCaptor.capture(), descCaptor.capture(), eq(true));

        assertThat(idCaptor.getValue()).startsWith("goal-");
        assertThat(descCaptor.getValue()).startsWith("Fix compile error:").contains("pom.xml not found");

        assertThat(injected.getMetadata())
                .containsEntry(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX);
        verify(goalPlannerService).saveGoalMetadata(injected);
        verify(goalPlannerService).setAcceptanceCriteria(eq(idCaptor.getValue()), anyList());
    }

    @Test
    void inject_idempotent_skipsWhenActiveBuildFixAlreadyOnCurrentGoal() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(activeBuildFixGoal());

        injector.injectBuildFixSubgoalIfAbsent(buildOutcome("[ERROR] still broken"));

        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
        verify(goalPlannerService, never()).setAcceptanceCriteria(anyString(), anyList());
    }

    @Test
    void inject_idempotent_skipsWhenActiveBuildFixInRemainingGoals() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(activeBuildFixGoal()));

        injector.injectBuildFixSubgoalIfAbsent(buildOutcome("[ERROR] still broken"));

        verify(goalPlannerService, never()).addSubGoal(anyString(), anyString(), anyBoolean());
    }

    @Test
    void inject_recordsBuildErrorKindInMetadata() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        GoalEntity injected = goalEntityWithMeta(new HashMap<>());
        when(goalPlannerService.getGoalById(anyString())).thenReturn(injected);

        String missingPomStderr = "[ERROR] The goal you specified requires a project to execute but there is no POM in this directory (/data/firestarter).";
        injector.injectBuildFixSubgoalIfAbsent(buildOutcome(missingPomStderr));

        assertThat(injected.getMetadata())
                .containsKey(BlockerSubgoalInjector.METADATA_KEY_BUILD_ERROR_KIND);
    }

    // ── hasActiveBuildFixSubgoal ──

    @Test
    void hasActive_returnsTrueWhenCurrentGoalIsBuildFix() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(activeBuildFixGoal());

        assertThat(injector.hasActiveBuildFixSubgoal()).isTrue();
    }

    @Test
    void hasActive_returnsTrueWhenBuildFixInRemainingGoals() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(activeBuildFixGoal()));

        assertThat(injector.hasActiveBuildFixSubgoal()).isTrue();
    }

    @Test
    void hasActive_returnsFalseWhenNoBuildFixGoalsExist() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        assertThat(injector.hasActiveBuildFixSubgoal()).isFalse();
    }

    @Test
    void hasActive_ignoresGoalsWithWrongMetadataValue() {
        GoalEntity g = goalEntityWithMeta(Map.of(BlockerTypes.METADATA_KEY, "OTHER_TYPE"));
        g.setStatus(GoalStatus.ACTIVE);
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(g);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        assertThat(injector.hasActiveBuildFixSubgoal()).isFalse();
    }

    // ── clearActiveBuildFixSubgoals ──

    @Test
    void clear_marksCurrentBuildFixGoalAsGivenUp() {
        GoalEntity current = activeBuildFixGoal();
        current.setId("goal-123");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(current);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        injector.clearActiveBuildFixSubgoals();

        verify(goalPlannerService).markGoalGivenUp(eq("goal-123"), anyString());
    }

    @Test
    void clear_marksRemainingBuildFixGoalsAsGivenUp() {
        GoalEntity remaining = activeBuildFixGoal();
        remaining.setId("goal-remaining");
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of(remaining));

        injector.clearActiveBuildFixSubgoals();

        verify(goalPlannerService).markGoalGivenUp(eq("goal-remaining"), anyString());
    }

    @Test
    void clear_noOpWhenNoBuildFixGoalsExist() {
        when(goalPlannerService.getCurrentSubGoal()).thenReturn(null);
        when(goalPlannerService.getRemainingGoals()).thenReturn(List.of());

        injector.clearActiveBuildFixSubgoals();

        verify(goalPlannerService, never()).markGoalGivenUp(anyString(), anyString());
    }

    // ── summariseStderr ──

    @Test
    void summarise_returnsPlaceholderForNullInput() {
        assertThat(BlockerSubgoalInjector.summariseStderr(null)).isEqualTo("(no stderr captured)");
    }

    @Test
    void summarise_extractsFirstInformativeErrorLine() {
        String stderr = """
                [INFO] Scanning for projects...
                [ERROR]
                [ERROR] The goal requires a POM in this directory.
                [ERROR] -> [Help 1]
                """;
        assertThat(BlockerSubgoalInjector.summariseStderr(stderr))
                .isEqualTo("The goal requires a POM in this directory.");
    }

    @Test
    void summarise_skipsHelpContinuationLines() {
        String stderr = "[ERROR] -> [Help 1]\n[ERROR] real error here";
        assertThat(BlockerSubgoalInjector.summariseStderr(stderr)).isEqualTo("real error here");
    }

    @Test
    void summarise_truncatesLongLines() {
        String longLine = "[ERROR] " + "x".repeat(300);
        String result = BlockerSubgoalInjector.summariseStderr(longLine);
        assertThat(result).hasSizeLessThanOrEqualTo(201); // 200 chars + ellipsis char
        assertThat(result).endsWith("…");
    }

    // ── helpers ──

    private static BuildOutcome buildOutcome(String stderr) {
        return new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 100L, "mvn compile", null);
    }

    private static GoalEntity activeBuildFixGoal() {
        GoalEntity g = goalEntityWithMeta(new HashMap<>(Map.of(BlockerTypes.METADATA_KEY, BlockerTypes.BUILD_FIX)));
        g.setStatus(GoalStatus.ACTIVE);
        return g;
    }

    private static GoalEntity goalEntityWithMeta(Map<String, Object> meta) {
        GoalEntity g = new GoalEntity();
        g.setMetadata(meta);
        return g;
    }
}
