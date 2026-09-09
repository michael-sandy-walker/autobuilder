package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3-CC: what the loop AUTHORED beyond the specification is reported, never asserted.
 *
 * <p><b>The half-wiring this closes.</b> {@code isAuthoredCriterion} was consulted at four
 * closure-side sites and none on the verification side, so an authored criterion could not block
 * closure and was still probed against the artifact. A correct application that answers the
 * SPECIFIED behaviour was then recorded as contradicting an obligation nobody stated. Measured on
 * the graph: of 97 distinct criteria naming a pattern input, 27 assert an error status the seed
 * never states for that endpoint.
 *
 * <p><b>Why the vocabulary here is invented.</b> The rule must ask only "did the specification say
 * this, or did we", never what the claim is about. These fixtures use a domain that does not
 * exist, so a rule that had quietly learned this project's HTTP vocabulary would fail them.
 */
@ExtendWith(MockitoExtension.class)
class GoalPlannerServiceElaborationTierTest {

    @Mock private FileService fileService;
    @Mock private GoalGraphService goalGraphService;
    @Mock private GoalPlannerConfigProperties goalPlannerConfig;
    @Mock private ClosureClaimVerifier verifier;

    private GoalPlannerService serviceWith(GoalEntity g) {
        GoalPlannerService service = new GoalPlannerService(fileService, goalGraphService, goalPlannerConfig);
        ReflectionTestUtils.setField(service, "closureClaimVerifier", verifier);
        Map<String, GoalEntity> goalMap = new LinkedHashMap<>();
        goalMap.put(g.getId(), g);
        ReflectionTestUtils.setField(service, "goalMap", goalMap);
        return service;
    }

    private static GoalEntity goalWith(List<String> criteria, Map<String, String> binding) {
        GoalEntity g = new GoalEntity("W", "warp coil alignment", "planner", GoalType.SUBGOAL, "G");
        g.setStatus(GoalStatus.COMPLETED);
        g.getMetadata().put("acceptanceCriteria", criteria);
        if (binding != null) g.getMetadata().put("criteriaBinding", binding);
        return g;
    }

    @Test
    @DisplayName("an authored criterion is never handed to the verifier; the specified one still is")
    void elaborationIsNotAssertedAgainstTheArtifact() {
        GoalEntity g = goalWith(
                List.of("the coil reports ALIGNED after calibration",      // 1: specification
                        "the coil reports FAULTED for an unknown emitter"), // 2: the loop's own idea
                Map.of("2", "AUTHORED"));
        when(verifier.refute(anyString(), any())).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(serviceWith(g), "refuteClosureClaim", "W");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(verifier).refute(anyString(), captor.capture());
        assertThat(captor.getValue())
                .as("only what the specification required may be asserted against the artifact")
                .containsExactly("the coil reports ALIGNED after calibration");
    }

    @Test
    @DisplayName("a claim of only authored criteria buys no package build and no boot")
    void anAllElaborationClaimIsNotVerifiedAtAll() {
        GoalEntity g = goalWith(
                List.of("the coil reports FAULTED for an unknown emitter",
                        "the emitter index is validated before alignment"),
                Map.of("1", "AUTHORED", "2", "AUTHORED"));

        Optional<String> refutation =
                ReflectionTestUtils.invokeMethod(serviceWith(g), "refuteClosureClaim", "W");

        assertThat(refutation).isEmpty();
        // The expensive half of the check is what this saves: nothing the specification asked for
        // is left to test, so no artifact is packaged and none is booted.
        verify(verifier, never()).refute(anyString(), any());
    }

    @Test
    @DisplayName("with no binding stamp every criterion is specified, which is the safe default")
    void anUnstampedGoalIsTreatedAsFullySpecified() {
        GoalEntity g = goalWith(
                List.of("the coil reports ALIGNED after calibration",
                        "the coil holds alignment across a restart"),
                null);
        when(verifier.refute(anyString(), any())).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(serviceWith(g), "refuteClosureClaim", "W");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(verifier).refute(anyString(), captor.capture());
        assertThat(captor.getValue())
                .as("absent evidence that we authored it, the specification is presumed to own it")
                .hasSize(2);
    }
}
