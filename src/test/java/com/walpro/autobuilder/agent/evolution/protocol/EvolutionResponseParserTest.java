package com.walpro.autobuilder.agent.evolution.protocol;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.protocol.EvolutionResponseParser;

import static org.assertj.core.api.Assertions.assertThat;

public class EvolutionResponseParserTest {

    @Test
    void shouldParseValidResponse() {
        String response = """
            ```java [src/test/java/com/example/agent/OpenAIAgentTest.java]
            package com.example.agent;
            
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            
            class OpenAIAgentTest {
            
                @Test
                void testInteractWithLLM() {
                    OpenAIAgent agent = new OpenAIAgent();
                    String input = "Hello, LLM!";
                    String response = agent.interactWithLLM(input);
                    assertNotNull(response);
                    assertFalse(response.isEmpty());
                }
            }
            ```
            #Delete [src/main/java/com/obsolete/OldFile.java]
            """;

        EvolutionResponseParser parser = new EvolutionResponseParser();
        EvolutionOutcome outcome = parser.parse(response);

        assertThat(outcome.addedOrChangedFiles())
                .anyMatch(f -> f.path().endsWith("OpenAIAgentTest.java"));

        assertThat(outcome.deletedFiles())
                .contains("src/main/java/com/obsolete/OldFile.java");
    }
}
