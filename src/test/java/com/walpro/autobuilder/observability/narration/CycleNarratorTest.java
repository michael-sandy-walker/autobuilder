package com.walpro.autobuilder.observability.narration;

import com.walpro.autobuilder.client.NarratorChannel;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CycleNarratorTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<GoalPlannerService> goals = mock(ObjectProvider.class);
    private final NarratorChannel channel = mock(NarratorChannel.class);
    private final BehaviourLogger logger = mock(BehaviourLogger.class);

    @Test
    void tellsTheRecordAndEmitsWhatTheNarratorSaid() {
        when(channel.model()).thenReturn("qwen3.8");
        when(channel.ask(anyString(), anyString(), eq("cycle-1")))
                .thenReturn(Optional.of("<think>hm</think>\"The evaluator turned the change down: eight tests broke.\""));
        CycleNarrator narrator = new CycleNarrator(channel, logger, goals);

        narrator.tell("cycle-1", "The change was rejected.");

        verify(channel).ask(eq(CycleNarrator.SYSTEM), contains("The change was rejected."), eq("cycle-1"));
        verify(logger).cycleNarrated(eq("cycle-1"), eq("The evaluator turned the change down: eight tests broke."),
                anyLong(), eq("qwen3.8"));
    }

    @Test
    void theNextTellingSeesThePreviousOneSoItDoesNotRepeatIt() {
        when(channel.ask(anyString(), anyString(), anyString())).thenReturn(Optional.of("First."), Optional.of("Second."));
        CycleNarrator narrator = new CycleNarrator(channel, logger, goals);

        narrator.tell("cycle-1", "a");
        narrator.tell("cycle-2", "b");

        verify(channel).ask(anyString(), contains("The previous telling: First."), eq("cycle-2"));
    }

    @Test
    void aFailedNarrationEmitsNothing() {
        when(channel.ask(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        new CycleNarrator(channel, logger, goals).tell("cycle-1", "a");
        verify(logger, never()).cycleNarrated(any(), any(), anyLong(), any());
    }

    @Test
    void theCycleIsHandedOverOnlyWhenItEndsAndNotOnTheEmittingThread() throws Exception {
        when(channel.ask(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            Thread.sleep(300);   // a slow model must not hold the loop's thread
            return Optional.of("Told.");
        });
        CycleNarrator narrator = new CycleNarrator(channel, logger, goals);
        narrator.accept(Map.of("event", "cycle_start", "cycleId", "cycle-9", "payload", Map.of("subGoalDescription", "S")));
        narrator.accept(Map.of("event", "mutation_rejected", "cycleId", "cycle-9", "payload", Map.of("reason", "r")));
        verify(channel, never()).ask(any(), any(), any());

        long start = System.currentTimeMillis();
        narrator.accept(Map.of("event", "cycle_end", "cycleId", "cycle-9", "payload", Map.of("resultState", "CONTINUE")));
        assertThat(System.currentTimeMillis() - start).isLessThan(200);

        verify(logger, timeout(3000)).cycleNarrated(eq("cycle-9"), eq("Told."), anyLong(), any());
        narrator.stop();
    }

    @Test
    void spokenTrimsMarkupAndCapsTheLengthAtASentence() {
        assertThat(CycleNarrator.spoken("**Merged** the `parser`.")).isEqualTo("Merged the parser.");
        String longer = "One two three. " + "word ".repeat(80);
        assertThat(CycleNarrator.spoken(longer)).isEqualTo("One two three.");
    }
}
