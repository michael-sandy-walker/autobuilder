package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** T4-CQ: the same failure again and again is noted -- and keeps being noted while it goes on. */
class GrindDetectorTest {

    private static final String STDERR = """
            [ERROR] /x/src/test/java/org/sample/QuillTest.java:[12,20] cannot find symbol
              symbol:   method getInk()
              location: variable quill of type org.sample.Quill
            """;

    private static BuildOutcome failed() {
        return new BuildOutcome(BuildResult.COMPILE_ERROR, STDERR, 1L, "mvn", "probe");
    }

    @Test
    void aFailureIsNotedAtItsThirdCycleAndAgainAtEveryDoubling() {
        GrindDetector d = new GrindDetector();
        BehaviourLogger logger = mock(BehaviourLogger.class);
        ReflectionTestUtils.setField(d, "behaviourLogger", logger);

        for (int c = 1; c <= 12; c++) d.observeBuild("cycle-" + c, failed());

        verify(logger).grindDetected(eq("cycle-3"), anyString(), eq(3), eq("cycle-1"));
        verify(logger).grindDetected(eq("cycle-6"), anyString(), eq(6), eq("cycle-1"));
        verify(logger).grindDetected(eq("cycle-12"), anyString(), eq(12), eq("cycle-1"));
        verify(logger, times(3)).grindDetected(anyString(), anyString(), anyInt(), anyString());
        assertThat(d.occurrences()).containsEntry("the build fails: Quill has no getInk()", 12);
    }

    @Test
    void oneCycleMeetingTheSameFailureTwiceCountsOnce() {
        GrindDetector d = new GrindDetector();
        BehaviourLogger logger = mock(BehaviourLogger.class);
        ReflectionTestUtils.setField(d, "behaviourLogger", logger);

        d.beginCycle("cycle-1");
        d.observe(null, "write to src/main/Quill.java refused: protected");
        d.observe(null, "write to src/main/Quill.java refused: protected");
        d.beginCycle("cycle-2");
        d.observe(null, "write to src/main/Quill.java refused: protected");

        assertThat(d.occurrences()).containsEntry("write to src/main/Quill.java refused: protected", 2);
        verify(logger, never()).grindDetected(anyString(), anyString(), anyInt(), anyString());
    }

    /**
     * T4-DY slice B: the detector is a reporter. Every occurrence reaches the stuck layer -- also a
     * second one in the same cycle, which the layer needs when its answer was delivered in between --
     * and a green build tells the layer the build signatures ended. The detector no longer writes
     * a prompt paragraph of its own.
     */
    @Test
    void everyOccurrenceIsReportedToTheStuckLayerAndAGreenBuildEndsTheGrind() {
        GrindDetector d = new GrindDetector();
        com.walpro.autobuilder.core.stuck.StuckLayer layer = mock(com.walpro.autobuilder.core.stuck.StuckLayer.class);
        ReflectionTestUtils.setField(d, "stuckLayer", layer);

        d.observeBuild("cycle-1", failed());
        d.observeBuild("cycle-1", failed());
        d.observe("cycle-2", com.walpro.autobuilder.core.stuck.StuckObservation.Source.PROTECTED_WRITE,
                "write to src/main/Quill.java refused: protected");
        d.observeBuild("cycle-3", new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn", "probe"));

        verify(layer, times(2)).report(eq(com.walpro.autobuilder.core.stuck.StuckObservation.Source.BUILD),
                eq("the build fails: Quill has no getInk()"), eq(java.util.List.of()));
        verify(layer).report(eq(com.walpro.autobuilder.core.stuck.StuckObservation.Source.PROTECTED_WRITE),
                eq("write to src/main/Quill.java refused: protected"), eq(java.util.List.of()));
        verify(layer).buildSucceeded("cycle-3");
        assertThat(d.occurrences()).as("the detector's own count stays once per cycle")
                .containsEntry("the build fails: Quill has no getInk()", 1);
    }

    /**
     * "cannot find symbol" keeps its symbol and location: without them the signature named only the
     * file, and three of the seven grinds that outlived every reaction could not be asked about.
     * The lines are the archive's own (participation-totals run 2026-09-25_20-51-35).
     */
    @Test
    void aCannotFindSymbolSignatureCarriesTheSymbolAndWhereItWasLookedFor() {
        String stderr = String.join("\n",
                "[ERROR] /x/src/main/java/com/walpro/autobuilder/core/lifecycle/ChannelRequest.java:[76,45] cannot find symbol",
                "[ERROR]   symbol:   variable subGoalId",
                "[ERROR]   location: class com.walpro.autobuilder.core.lifecycle.ChannelRequest",
                "[ERROR] /x/src/main/java/com/walpro/autobuilder/core/lifecycle/ParticipationWindows.java:[40,9] cannot find symbol",
                "[ERROR]   symbol:   class ArrayList",
                "[ERROR]   location: class com.walpro.autobuilder.core.lifecycle.ParticipationWindows");
        var signatures = GrindDetector.signaturesOf(new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 1L, "mvn", "probe"));

        org.assertj.core.api.Assertions.assertThat(signatures).containsExactly(
                "the build fails: ChannelRequest.java: cannot find symbol variable subGoalId in class ChannelRequest",
                "the build fails: ParticipationWindows.java: cannot find symbol class ArrayList in class ParticipationWindows");
    }
}
