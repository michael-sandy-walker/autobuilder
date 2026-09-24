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

    @Test
    void thePromptNamesOnlyWhatIsStillGrinding() {
        GrindDetector d = new GrindDetector();
        for (int c = 1; c <= 3; c++) d.observeBuild("cycle-" + c, failed());
        assertThat(d.promptBlock()).contains("FAILED REPEATEDLY").contains("Quill has no getInk()").contains("3 times");

        d.observe("cycle-4", "the build fails: Ink.java: something else");
        assertThat(d.promptBlock()).as("the grind did not recur in the latest failing cycle").isNull();
    }

    @Test
    void aSuccessfulBuildEndsTheGrindTheNextPromptNames() {
        GrindDetector d = new GrindDetector();
        for (int c = 1; c <= 3; c++) d.observeBuild("cycle-" + c, failed());
        assertThat(d.promptBlock()).isNotNull();

        d.observeBuild("cycle-4", new BuildOutcome(BuildResult.COMPILE_OK, "", 1L, "mvn", "probe"));

        assertThat(d.promptBlock()).as("the grind ended with a green build").isNull();
    }
}
