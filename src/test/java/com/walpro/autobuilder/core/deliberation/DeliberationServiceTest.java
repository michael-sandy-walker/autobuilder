package com.walpro.autobuilder.core.deliberation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.walpro.autobuilder.analysis.exhaustion.ExhaustionReport;
import com.walpro.autobuilder.analysis.exhaustion.GoalExhaustionDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.deliberation.DeliberationActionRegistrar;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class DeliberationServiceTest {

    @Mock
    GoalExhaustionDetector goalExhaustionDetector;
    @Mock DeliberationProposer proposer;
    @Mock DeliberationValidator validator;
    @Mock DeliberationActionRegistrar registrar;

    @Mock CycleContextData context;

    @Test
    void deliberate_proposes_validates_and_records() {
        DeliberationResult proposed = new DeliberationResult(DeliberationMode.REPLAN, 0.9, "r", java.util.List.of("c"), List.of(), java.util.Map.of(), List.of());
        DeliberationResult validated = new DeliberationResult(DeliberationMode.REPLAN, 1.0, "r2", java.util.List.of(), List.of(), java.util.Map.of(), List.of());
        DeliberationDecision validatedDecision = new DeliberationDecision(context, validated);

        when(proposer.propose(context)).thenReturn(proposed);
        when(validator.validate(proposed)).thenReturn(validated);
        when (goalExhaustionDetector.detect(context)).thenReturn(ExhaustionReport.none("test"));

        DeliberationService svc = new DeliberationService(goalExhaustionDetector, proposer, validator, registrar);
        DeliberationDecision out = svc.deliberate(context);

        assertThat(out).isEqualTo(validatedDecision);
        verify(proposer).propose(context);
        verify(validator).validate(proposed);
        verify(registrar).registerDeliberation("deliberation", context, validated);
    }
}
