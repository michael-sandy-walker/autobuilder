package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-CD slice 1: unbankActiveCriteria — the resume re-grounding move. Banked reads on ACTIVE
 * subgoals are dropped (loaded state is hypothesis; the loop re-derives them against the
 * restored artifact); terminal goals keep their banks (the completion edge re-verifies the
 * whole ledger before any closure).
 */
class GoalPlannerServiceResumeRegroundTest {

    private GoalPlannerService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        FileService fileService = mock(FileService.class);
        GoalGraphService goalGraphService = mock(GoalGraphService.class);
        GoalPlannerConfigProperties config = mock(GoalPlannerConfigProperties.class);

        when(config.isSyncFromGraph()).thenReturn(false);
        when(config.isMigrateToGraph()).thenReturn(false);
        doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).markCompleted(anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        Path goalFile = tempDir.resolve("GoalStack.json");
        when(fileService.resolveSecurePath("meta/GoalStack.json")).thenReturn(goalFile);
        when(fileService.existsResolved(goalFile)).thenReturn(false);

        service = new GoalPlannerService(fileService, goalGraphService, config);
        service.init();
    }

    @Test
    void unbank_clearsActiveBanks_keepsTerminalBanks() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("sub-active", "Implement the Book resource", true, "root");
        service.addSubGoal("sub-done", "Implement the Member resource", false, "root");
        service.setAcceptanceCriteria("sub-active", List.of("books endpoint answers 200"));
        service.setAcceptanceCriteria("sub-done", List.of("members endpoint answers 200"));
        service.saveCriteriaPassStatus("sub-active", Map.of(1, true));
        service.saveCriteriaPassStatus("sub-done", Map.of(1, true));
        service.transitionGoal("sub-done", GoalStatus.COMPLETED, "verified");

        int cleared = service.unbankActiveCriteria("resume re-ground");

        assertThat(cleared).isEqualTo(1);
        assertThat(service.getCriteriaPassStatus(service.getGoalById("sub-active"))).isEmpty();
        assertThat(service.getCriteriaPassStatus(service.getGoalById("sub-done")))
                .containsEntry(1, true);
    }

    @Test
    void unbank_noBankedReads_isaNoOp() {
        service.resetInitialGoal("root", "Main Goal");
        service.addSubGoal("sub-1", "Implement the Book resource", true, "root");
        service.setAcceptanceCriteria("sub-1", List.of("endpoint answers 200"));

        assertThat(service.unbankActiveCriteria("resume re-ground")).isZero();
    }
}
