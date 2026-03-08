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
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.graph.evolution.EvolutionActionRegistrar;
import com.walpro.autobuilder.graph.evolution.EvolutionGraphFacade;

@ExtendWith(MockitoExtension.class)
class EvolutionLLMAdapterTest {

    @Mock private LLMClient llmClient;
    @Mock private EvolutionGraphFacade graphFacade;
    @Mock private LLMAgentProtocolInvoker protocolInvoker;
    @Mock private EvolutionActionRegistrar evolutionActionRegistrar;

    private EvolutionLLMAdapter agent;

    @BeforeEach
    void setUp() {
        // Order must match @RequiredArgsConstructor field order
        agent = new EvolutionLLMAdapter(
                llmClient,
                graphFacade,
                protocolInvoker,
                evolutionActionRegistrar
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
                any()
        )).thenReturn(LLMResponseResult.success(parsedOutcome, "#Evolution raw"));

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
                any()
        )).thenReturn(LLMResponseResult.failure("RAW_CONTENT", "protocol-violation"));

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
                any()
        )).thenReturn(LLMResponseResult.success(outcome, "#divergent"));

        agent.evolveDivergent(context);

        assertThat(userPromptCaptor.getValue())
                .as("evolveDivergent must include the Divergence Mode prompt block")
                .contains("Divergence Mode Triggered");
    }
}
