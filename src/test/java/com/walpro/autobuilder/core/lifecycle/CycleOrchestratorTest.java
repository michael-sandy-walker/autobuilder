package com.walpro.autobuilder.core.lifecycle;

import com.walpro.autobuilder.model.GoalProgressState;
import com.walpro.autobuilder.model.MutationAcceptanceState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CycleOrchestratorTest {

    private final CycleOrchestrator orchestrator = new CycleOrchestrator();

    @Test
    void acceptedAndProgressing_resultsInSuccessful() {
        CycleContextData context = mock(CycleContextData.class);
        when(context.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(context.goalProgress()).thenReturn(GoalProgressState.PROGRESSING);

        CycleResultState result = orchestrator.determineNextState(context);

        assertThat(result).isEqualTo(CycleResultState.SUCCESSFUL);
    }

    @Test
    void acceptedButNotProgressing_resultsInStagnated() {
        CycleContextData context = mock(CycleContextData.class);
        when(context.mutationState()).thenReturn(MutationAcceptanceState.ACCEPTED);
        when(context.goalProgress()).thenReturn(GoalProgressState.STAGNATING);

        CycleResultState result = orchestrator.determineNextState(context);

        assertThat(result).isEqualTo(CycleResultState.STAGNATED);
    }

    @Test
    void rejected_resultsInRejected() {
        CycleContextData context = mock(CycleContextData.class);
        when(context.mutationState()).thenReturn(MutationAcceptanceState.REJECTED);
        when(context.goalProgress()).thenReturn(GoalProgressState.PROGRESSING); // doesn’t matter

        CycleResultState result = orchestrator.determineNextState(context);

        assertThat(result).isEqualTo(CycleResultState.REJECTED);
    }

    @Test
    void otherMutationStates_fallBackToPlanning() {
        CycleContextData context = mock(CycleContextData.class);
        // anything that is neither ACCEPTED nor REJECTED ends in FALLBACK_PLANNING
        when(context.mutationState()).thenReturn(null);
        when(context.goalProgress()).thenReturn(null);

        CycleResultState result = orchestrator.determineNextState(context);

        assertThat(result).isEqualTo(CycleResultState.FALLBACK_PLANNING);
    }
}
