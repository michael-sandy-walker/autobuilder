package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import com.walpro.autobuilder.testsupport.HollowVsRealFixtures;
import com.walpro.autobuilder.testsupport.OutputFixtureBuilder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T5-AF layer 1 (gate plumbing — deterministic, CI-safe): drives the REAL completion edge
 * ({@code GoalPlannerService.autoCompleteRootIfSatisfied}) over a known {@code output/} artifact
 * staged by {@link OutputFixtureBuilder}, with a <em>canned</em> verifier verdict standing in for
 * the (nondeterministic) LLM. Asserts the T4-BP-3 mechanism on demand instead of waiting for a bad
 * run: a hollow artifact + a ✗ verdict must block COMPLETED and inject the unconfirmed criteria as
 * a work item; a real artifact + a ✓ verdict must complete. The verifier's <em>precision</em> on
 * real artifacts is the separate, opt-in layer-2 eval
 * ({@code CompletionVerifierPrecisionEvalTest}); here we prove only the wiring around it.
 */
@ExtendWith(MockitoExtension.class)
class CompletionEdgeFaultInjectionTest {

    @Mock private FileService fileService;
    @Mock private GoalGraphService goalGraphService;
    @Mock private GoalPlannerConfigProperties goalPlannerConfig;

    private GoalPlannerService serviceWith(CriteriaVerificationService verifier, Map<String, String> output,
                                           GoalStatus subgoalStatus, List<String> criteria) throws IOException {
        doNothing().when(fileService).writeFile(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).setState(anyString(), any());
        lenient().doNothing().when(goalGraphService).setParent(anyString(), anyString());
        lenient().doNothing().when(goalGraphService).save(any(GoalEntity.class));

        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        service.setCriteriaVerificationService(verifier);
        // The completion edge reads the artifact from the live cycle context — feed the fixture map
        // through exactly the path production uses (currentCycleContext.previousOutput()).
        service.setCurrentCycleContext(CycleContextData.builder()
                .cycleName("fault-injection")
                .outputPath("output")
                .previousOutput(output)
                .build());

        GoalEntity root = new GoalEntity("G", "root", "system", GoalType.GOAL, null);
        root.setStatus(GoalStatus.ACTIVE);
        GoalEntity done = new GoalEntity("D", "Implement checkout", "planner", GoalType.SUBGOAL, "G");
        done.setStatus(subgoalStatus);
        done.getMetadata().put("acceptanceCriteria", criteria);

        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(root.getId(), root);
        goalMap.put(done.getId(), done);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        ReflectionTestUtils.setField(service, "rootGoalId", "G");
        return service;
    }

    @Test
    void hollowArtifact_unconfirmedVerdict_blocksCompletion_andInjectsCriteria() throws IOException {
        HollowVsRealFixtures.LabeledCase c = HollowVsRealFixtures.computedPricing();
        Map<String, String> hollow = OutputFixtureBuilder.builder().files(c.hollow()).asMap();

        CriteriaVerificationService verifier = mock(CriteriaVerificationService.class);
        when(verifier.verifyCompletion(any(), any(), any()))
                .thenReturn(CriteriaVerificationService.CompletionVerification.blocked(
                        "1 acceptance criterion(s) not confirmed in the current code: " + c.criterion()));

        GoalPlannerService service = serviceWith(verifier, hollow, GoalStatus.SUBSUMED, List.of(c.criterion()));
        service.autoCompleteRootIfSatisfied();

        // Mechanism: completion refused, the unconfirmed criterion injected as a work item.
        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.ACTIVE);
        assertThat(service.getAllGoals())
                .anyMatch(g -> g.getStatus() == GoalStatus.ACTIVE
                        && g.getDescription() != null
                        && g.getDescription().contains("not confirmed in the current code"));

        // Plumbing: the verifier was driven with the staged artifact (not null), via previousOutput.
        ArgumentCaptor<Map<String, String>> outputArg = ArgumentCaptor.forClass(Map.class);
        verify(verifier).verifyCompletion(eq("root"), any(), outputArg.capture());
        assertThat(outputArg.getValue()).isEqualTo(hollow);
    }

    @Test
    void realArtifact_confirmedVerdict_allowsCompletion() throws IOException {
        HollowVsRealFixtures.LabeledCase c = HollowVsRealFixtures.computedPricing();
        Map<String, String> real = OutputFixtureBuilder.builder().files(c.real()).asMap();

        CriteriaVerificationService verifier = mock(CriteriaVerificationService.class);
        when(verifier.verifyCompletion(any(), any(), any()))
                .thenReturn(CriteriaVerificationService.CompletionVerification.allClear());

        GoalPlannerService service = serviceWith(verifier, real, GoalStatus.SUBSUMED, List.of(c.criterion()));
        service.autoCompleteRootIfSatisfied();

        assertThat(service.getGoalById("G").getStatus()).isEqualTo(GoalStatus.COMPLETED);
        verify(verifier).verifyCompletion(eq("root"), any(), eq(real));
    }
}
