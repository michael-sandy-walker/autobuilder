package com.walpro.autobuilder.agent.evolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.agent.shared.LLMResponseResult;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.build.PackageConsolidationService;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.evolution.EvolutionActionRegistrar;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphFacade;

@ExtendWith(MockitoExtension.class)
class EvolutionLLMAdapterTest {

    @Mock private LLMClient llmClient;
    @Mock private EvolutionGraphFacade graphFacade;
    @Mock private LLMAgentProtocolInvoker protocolInvoker;
    @Mock private EvolutionActionRegistrar evolutionActionRegistrar;
    @Mock private SymbolGroundingService groundingService;
    @Mock private PackageConsolidationService packageConsolidationService;

    private EvolutionLLMAdapter agent;

    @BeforeEach
    void setUp() {
        // Default: grounding service is a no-op pass-through so existing tests don't have to
        // care about goal-description refresh. Tests that exercise refresh can override.
        org.mockito.Mockito.lenient().when(groundingService.refreshGoalAnnotations(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Default: consolidation is a pass-through; the wiring test overrides.
        org.mockito.Mockito.lenient().when(packageConsolidationService.consolidate(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Order must match @RequiredArgsConstructor field order
        agent = new EvolutionLLMAdapter(
                llmClient,
                graphFacade,
                protocolInvoker,
                evolutionActionRegistrar,
                groundingService,
                packageConsolidationService,
                new com.walpro.autobuilder.protocol.PromptTokenBudget()
        );
    }

    @Test
    void evolve_withValidResponse_appliesOutcomeAndRegistersGraph() throws IOException {
        // given
        CycleContextData context = CycleContextData.builder().build();

        EvolutionOutcome parsedOutcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of("src/Obsolete.java"),
                "Parsed 1 changed file(s), 1 deleted file(s).",
                "#Evolution raw"
        );

        when(protocolInvoker.callWithValidation(
                any(),
                anyString(),
                anyString(),
                any(),
                any(), any()
        , any())).thenReturn(LLMResponseResult.success(parsedOutcome, "#Evolution raw"));

        // when
        CycleContextData updated = agent.evolve(context);

        // then
        assertThat(updated.evolutionOutcome())
                .as("EvolutionOutcome should be attached to the returned context")
                .isSameAs(parsedOutcome);

        // Uses the original context when registering
        verify(evolutionActionRegistrar)
                .registerEvolution(eq("StaticEvolutionAgent"), eq(context), eq(parsedOutcome));

        // Graph evolution is delegated
        verify(graphFacade).recordEvolution(eq(context), eq(parsedOutcome));
    }

    @Test
    void evolve_withInvalidLLMResponse_returnsFallbackOutcome() throws IOException {
        // given
        CycleContextData context = CycleContextData.builder().build();

        when(protocolInvoker.callWithValidation(
                any(),
                anyString(),
                anyString(),
                any(),
                any(), any()
        , any())).thenReturn(LLMResponseResult.failure("RAW_CONTENT", "protocol-violation"));

        // when
        CycleContextData updated = agent.evolve(context);

        // then
        EvolutionOutcome outcome = updated.evolutionOutcome();
        assertThat(outcome).as("Fallback EvolutionOutcome should be present").isNotNull();

        assertThat(outcome.addedOrChangedFiles())
                .as("No files should be changed on invalid output")
                .isEmpty();

        assertThat(outcome.deletedFiles())
                .as("No files should be deleted on invalid output")
                .isEmpty();

        assertThat(outcome.summary())
                .isEqualTo("Invalid response: protocol-violation");

        assertThat(outcome.rawResponse())
                .isEqualTo("RAW_CONTENT");

        // On invalid response, we *do not* expect graphFacade to be used for real evolution.
        // The current implementation does not call evolveGraph() in the failure branch.
//        verify(graphFacade)
//                .recordEvolution(context, outcome); // <-- If we decide NOT to record failures, remove this.
    }

    @Test
    void evolveDivergent_sendsPromptContainingDivergenceModeBlock() throws IOException {
        CycleContextData context = CycleContextData.builder().build();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main { /* divergent */ }")),
                List.of(),
                "divergent",
                "#divergent"
        );

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);

        when(protocolInvoker.callWithValidation(
                any(),
                anyString(),
                userPromptCaptor.capture(),
                any(),
                any(),
                any()
        , any())).thenReturn(LLMResponseResult.success(outcome, "#divergent"));

        agent.evolveDivergent(context);

        assertThat(userPromptCaptor.getValue())
                .as("evolveDivergent must include the Divergence Mode prompt block")
                .contains("Divergence Mode Triggered");
    }

    @Test
    void evolve_callsGroundingServiceToRefreshGoalAnnotations() throws IOException {
        // T1-EM: EvolutionLLMAdapter must refresh [UNDEFINED]/[NOT YET PRESENT] markers in
        // goal descriptions before rendering the prompt — same pattern as PlannerLLMAdapter
        // and AnalysisLLMAdapter.
        CycleContextData context = CycleContextData.builder().build();

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "ok",
                "#evolution raw"
        );

        when(protocolInvoker.callWithValidation(
                any(),
                anyString(),
                anyString(),
                any(),
                any(), any()
        , any())).thenReturn(LLMResponseResult.success(outcome, "#evolution raw"));

        agent.evolve(context);

        verify(groundingService).refreshGoalAnnotations(eq(context));
    }

    @Test
    void evolve_promptUsesRefreshedContext_notOriginal() throws IOException {
        // Given a context whose current subgoal description carries a stale [UNDEFINED]
        // marker, the rendered prompt must reflect the refreshed (marker-stripped) view.
        com.walpro.autobuilder.goal.GoalEntity stale = new com.walpro.autobuilder.goal.GoalEntity();
        stale.setId("g-1");
        stale.setDescription("Create MemberService [UNDEFINED]");
        stale.setStatus(com.walpro.autobuilder.goal.GoalStatus.ACTIVE);

        com.walpro.autobuilder.goal.GoalEntity refreshedGoal = new com.walpro.autobuilder.goal.GoalEntity();
        refreshedGoal.setId("g-1");
        refreshedGoal.setDescription("Create MemberService");
        refreshedGoal.setStatus(com.walpro.autobuilder.goal.GoalStatus.ACTIVE);

        CycleContextData context = CycleContextData.builder()
                .currentSubGoalId("g-1")
                .currentSubGoalDescription("Create MemberService [UNDEFINED]")
                .remainingGoals(java.util.List.of(stale))
                .build();
        CycleContextData refreshedCtx = context.toBuilder()
                .currentSubGoalDescription("Create MemberService")
                .remainingGoals(java.util.List.of(refreshedGoal))
                .build();

        when(groundingService.refreshGoalAnnotations(eq(context))).thenReturn(refreshedCtx);

        EvolutionOutcome outcome = new EvolutionOutcome(
                List.of(new FileMutation("src/Main.java", "class Main {}")),
                List.of(),
                "ok",
                "#evolution raw"
        );

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        when(protocolInvoker.callWithValidation(
                any(),
                anyString(),
                userPromptCaptor.capture(),
                any(),
                any(),
                any()
        , any())).thenReturn(LLMResponseResult.success(outcome, "#evolution raw"));

        agent.evolve(context);

        // The persisted (stale) description must not leak into the prompt.
        assertThat(userPromptCaptor.getValue())
                .as("rendered prompt must use refreshed goal descriptions, not the persisted [UNDEFINED]-marked ones")
                .doesNotContain("[UNDEFINED]");
    }

    @Test
    void evolve_runsPackageConsolidationBeforeRegisteringOutcome() throws IOException {
        // T4-BL: the consolidated outcome (not the raw parse) must be what the graph registrars
        // and the returned context see.
        CycleContextData context = CycleContextData.builder().build();

        EvolutionOutcome parsed = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/demo/model/Loan.java",
                        "package com.example.demo.model;\nclass Loan {}")),
                List.of(), "ok", "#raw");
        EvolutionOutcome consolidated = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/demo/Loan.java",
                        "package com.example.demo;\nclass Loan {}")),
                List.of(), "ok", "#raw");

        when(protocolInvoker.callWithValidation(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(LLMResponseResult.success(parsed, "#raw"));
        when(packageConsolidationService.consolidate(any(), any(), any())).thenReturn(consolidated);

        CycleContextData updated = agent.evolve(context);

        assertThat(updated.evolutionOutcome()).isSameAs(consolidated);
        verify(evolutionActionRegistrar)
                .registerEvolution(eq("StaticEvolutionAgent"), eq(context), eq(consolidated));
        verify(graphFacade).recordEvolution(eq(context), eq(consolidated));
    }

    /**
     * T6-T, ladder-rows 2026-10-14_18-10-40: twelve of 43 answers were the class exactly as it stood, one ten times over
     * 90 minutes. An answer that changes nothing is asked again, and told so.
     */
    @Test
    void anAnswerIdenticalToTheFileAsItStandsIsAskedAgain_andToldItChangesNothing() {
        String path = "src/main/java/zeta/Quill.java";
        String quill = "package zeta;\n\npublic class Quill {\n    int nib() { return 1; }\n}\n";
        com.walpro.autobuilder.core.lifecycle.CycleContextData ctx = com.walpro.autobuilder.core.lifecycle.CycleContextData.builder()
                .previousOutput(java.util.Map.of(path, quill)).build();

        String same = "```java [" + path + "]\n" + quill + "```\n";
        String changed = "```java [" + path + "]\n" + quill.replace("return 1;", "return 2;") + "```\n";

        assertThat(agent.whatItChangesNot(same, ctx, false)).contains("identical to " + path, "changes nothing", "#NoDiff");
        assertThat(agent.whatItChangesNot(changed, ctx, false)).isNull();
        assertThat(agent.whatItChangesNot("#NoDiff\nalready done", ctx, false)).as("a declared no-op is its own case").isNull();
    }

    /** A file the brake holds, rewritten again, is asked again for edits; an edit of it goes through. */
    @Test
    void aRewriteOfAFileTheBrakeHoldsIsAskedAgainForEdits() {
        String path = "src/main/java/zeta/Quill.java";
        StringBuilder sb = new StringBuilder("package zeta;\n\npublic class Quill {\n");
        for (int i = 0; i < 20; i++) sb.append("    int nib").append(i).append("() { return ").append(i).append("; }\n");
        String quill = sb.append("}\n").toString();
        RewriteBrake brake = new RewriteBrake();
        brake.tested(java.util.Map.of(path, quill), List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation(path,
                quill.replaceAll("return (\\d+);", "return -$1;"))), false);
        org.springframework.test.util.ReflectionTestUtils.setField(agent, "rewriteBrake", brake);
        com.walpro.autobuilder.core.lifecycle.CycleContextData ctx = com.walpro.autobuilder.core.lifecycle.CycleContextData.builder()
                .previousOutput(java.util.Map.of(path, quill)).build();

        String rewrite = "```java [" + path + "]\n" + quill.replaceAll("return (\\d+);", "return $1 * 2;") + "```\n";
        String edit = "```java [" + path + "]\n" + quill.replace("return 3;", "return 33;") + "```\n";

        assertThat(agent.whatItChangesNot(rewrite, ctx, false)).contains("rewrites " + path + " again", "edit blocks");
        assertThat(agent.whatItChangesNot(edit, ctx, false)).isNull();
    }

    /** T4-EE: on a retry the prompt offers one question of the run's log, and the invoker can answer it. */
    @Test
    @SuppressWarnings("unchecked")
    void aRetryOffersOneLogQuestion_aFirstAttemptDoesNot() throws IOException {
        var consultation = org.mockito.Mockito.mock(com.walpro.autobuilder.core.investigate.LogConsultation.class);
        when(consultation.offerBlock()).thenReturn("--- THIS RUN'S LOG --- offer");
        when(consultation.answer(eq("#LogQuestion q"), eq("evolution"), any(), any())).thenReturn("the answer");
        org.springframework.test.util.ReflectionTestUtils.setField(agent, "logConsultation", consultation);
        when(protocolInvoker.callWithValidation(any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(LLMResponseResult.failure(null, "none"));

        agent.evolve(CycleContextData.builder().rejectionFeedback(List.of("rejected: it did not compile")).build());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.util.function.Function<String, String>> consult =
                ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(protocolInvoker).callWithValidation(any(), anyString(), prompt.capture(), any(), any(), any(), consult.capture());
        assertThat(prompt.getValue()).contains("--- THIS RUN'S LOG --- offer");
        assertThat(consult.getValue().apply("#LogQuestion q")).isEqualTo("the answer");

        org.mockito.Mockito.clearInvocations(protocolInvoker);
        agent.evolve(CycleContextData.builder().build());
        verify(protocolInvoker).callWithValidation(any(), anyString(), prompt.capture(), any(), any(), any(), consult.capture());
        assertThat(prompt.getValue()).doesNotContain("THIS RUN'S LOG");
        assertThat(consult.getValue()).isNull();
    }
}
