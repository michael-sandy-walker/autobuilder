package com.walpro.autobuilder.core.checkpoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.RecurringDefectTracker;
import com.walpro.autobuilder.core.build.inject.FixAttemptLedger;
import com.walpro.autobuilder.core.build.inject.FixProposal;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.lifecycle.RootConvergenceTracker;
import com.walpro.autobuilder.core.mutation.RejectedProposalLedger;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalPlannerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * T4-DQ blocker 2: the trackers gate behaviour, so what matters is not that they serialise but
 * that the GATE reads the same after a round trip. Each of these asserts the decision, not the
 * field — a capture that preserved a count but lost the flag it feeds would pass a field test and
 * still change what the loop is allowed to do.
 */
class DecisionBearingHolderRoundTripTest {

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("the recurring-defect redirect survives a round trip; the per-cycle memo does not")
    void recurringDefectTrackerRoundTrip() {
        RecurringDefectTracker before = new RecurringDefectTracker();
        BuildOutcome broken = outcome("error: cannot find symbol\n  symbol: class MemberRepository\n");
        before.observe(broken, java.util.List.of(), "subgoal-a", "cycle-1");
        before.observe(broken, java.util.List.of(), "subgoal-b", "cycle-2");
        var redirectBefore = before.dominantDefect();
        assertThat(redirectBefore).as("the tracker has something to say before the round trip").isPresent();

        RecurringDefectTracker after = new RecurringDefectTracker();
        after.restoreState(node(before), mapper);

        assertThat(after.dominantDefect()).isEqualTo(redirectBefore);
        assertThat(ReflectionTestUtils.getField(after, "definedCacheCycle"))
                .as("the within-cycle memo of the output-tree walk is not carried across a rewind")
                .isNull();
    }

    @Test
    @DisplayName("a blacklisted fix proposal is still blacklisted after a round trip")
    void fixAttemptLedgerRoundTrip() {
        FileService files = new FileService(tmp.toString(), mapper);
        FixAttemptLedger before = new FixAttemptLedger(files);
        FixProposal proposal = new FixProposal(
                java.util.List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation("src/Widget.java", "class Widget {}")),
                new InjectorFixRecord("symbol-import:widget",
                        com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL,
                        "Widget", java.util.List.of("src/Widget.java"), "create Widget"));
        before.recordAttempt(proposal, BuildResult.COMPILE_ERROR, tmp.toString(), "cycle-1");
        before.recordAttempt(proposal, BuildResult.COMPILE_ERROR, tmp.toString(), "cycle-2");
        assertThat(before.shouldSkip(proposal, tmp.toString(), "cycle-3")).isTrue();

        FixAttemptLedger after = new FixAttemptLedger(files);
        assertThat(after.shouldSkip(proposal, tmp.toString(), "cycle-3")).isFalse();
        after.restoreState(node(before), mapper);

        assertThat(after.shouldSkip(proposal, tmp.toString(), "cycle-3")).isTrue();
    }

    @Test
    @DisplayName("the convergence brake keeps its stall count, and does not get its clock turned back")
    void rootConvergenceTrackerRoundTrip() {
        RootConvergenceTracker before = new RootConvergenceTracker(mock(GoalPlannerService.class));
        ReflectionTestUtils.setField(before, "cyclesSinceProgress", 7);
        ReflectionTestUtils.setField(before, "lastProgressCount", 3);
        ReflectionTestUtils.setField(before, "extensionsGranted", 1);

        RootConvergenceTracker after = new RootConvergenceTracker(mock(GoalPlannerService.class));
        ReflectionTestUtils.setField(after, "firstCycleAt", 12345L);
        after.restoreState(node(before), mapper);

        assertThat(ReflectionTestUtils.getField(after, "cyclesSinceProgress")).isEqualTo(7);
        assertThat(ReflectionTestUtils.getField(after, "lastProgressCount")).isEqualTo(3);
        assertThat(ReflectionTestUtils.getField(after, "extensionsGranted")).isEqualTo(1);
        assertThat(ReflectionTestUtils.getField(after, "firstCycleAt"))
                .as("rewound time is still spent — the wall clock is not part of the capture")
                .isEqualTo(12345L);
    }

    @Test
    @DisplayName("a standing rejection still refuses its repeat after a round trip")
    void rejectedProposalLedgerRoundTrip() {
        RejectedProposalLedger before = new RejectedProposalLedger();
        before.recordRejection("add a Widget controller", "tree-1", "no such type", "subgoal-a");

        RejectedProposalLedger after = new RejectedProposalLedger();
        assertThat(after.standingRejection("add a Widget controller", "tree-1")).isEmpty();
        after.restoreState(node(before), mapper);

        assertThat(after.standingRejection("add a Widget controller", "tree-1"))
                .as("the verdict and the tree it was reached against come back together")
                .isPresent();
    }

    @Test
    @DisplayName("a holder with no entry in the checkpoint is reset, not left as it is")
    void absentEntryResetsRatherThanKeeps() {
        RejectedProposalLedger holder = new RejectedProposalLedger();
        holder.recordRejection("something", "tree-1", "because", "subgoal-a");

        holder.restoreState(null, mapper);

        assertThat(holder.standingRejection("something", "tree-1"))
                .as("a rewind past the point a holder first recorded anything must un-record it")
                .isEmpty();
    }

    private JsonNode node(CheckpointableState holder) {
        return mapper.valueToTree(holder.captureState());
    }

    private static BuildOutcome outcome(String stderr) {
        return new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 0L, "javac", "cycle-1");
    }
}
