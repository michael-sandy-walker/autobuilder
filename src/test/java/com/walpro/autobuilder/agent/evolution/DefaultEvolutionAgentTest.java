//package com.walpro.autobuilder.agent.evolution;
//
//import com.walpro.autobuilder.graph.evolution.EvolutionGraphFacade;
//import com.walpro.autobuilder.agent.evolution.core.EvolutionInput;
//import com.walpro.autobuilder.agent.evolution.core.EvolutionOutcome;
//import com.walpro.autobuilder.client.LLMClient;
//import com.walpro.autobuilder.agent.shared.LLMResponseResult;
//import org.junit.jupiter.api.Test;
//
//import java.util.List;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.mockito.Mockito.*;
//
//class DefaultEvolutionAgentTest {
//
//    @Test
//    void testValidResponseTriggersOutcome() {
//        LLMClient mockClient = mock(LLMClient.class);
//        EvolutionGraphFacade mockGraph = mock(EvolutionGraphFacade.class);
//
//        DefaultEvolutionAgent agent = new DefaultEvolutionAgent(mockClient, mockGraph);
//
//        // Given
//        EvolutionInput input = new EvolutionInput(
//                "Build scaffold", "Add tests", "goal-1", "subgoal-1",
//                List.of("Some remaining goal"), List.of(),
//                "Some summary", "Some diff", "No feedback", "No recommendation"
//        );
//
//        // Simulate LLM client returning a valid code block
//        when(mockClient.call(anyString(), anyString())).thenReturn(
//                "```java\n" +
//                        "package com.example;\n" +
//                        "public class HelloWorld {}\n" +
//                        "```"
//        );
//
//        // When
//        EvolutionOutcome outcome = agent.evolve(input);
//
//        // Then
//        assertThat(outcome.addedOrChangedFiles()).hasSize(1);
//        assertThat(outcome.deletedFiles()).isEmpty();
//    }
//}
