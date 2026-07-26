package com.walpro.autobuilder.protocol.evolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

/**
 * T3-BW: the analyzer finding is only worth anything if it reaches the agent that can act on it.
 * The fact previously existed only for the criteria verifier, which cannot change what gets written.
 */
class PeerAnomalyReachesEvolutionPromptTest {

    /** Verbatim shape from run 2026-07-26_22-41: two registered peers, one that never registers. */
    private static Map<String, String> brokenTree() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("src/main/java/com/example/demo/model/BookController.java",
                "package com.example.demo.model;\n@RestController\n@RequestMapping(\"/books\")\n"
                        + "public class BookController { public void get() {} }\n");
        out.put("src/main/java/com/example/demo/model/MemberController.java",
                "package com.example.demo.model;\n@RestController\n@RequestMapping(\"/members\")\n"
                        + "public class MemberController { public void get() {} }\n");
        out.put("src/main/java/com/example/demo/model/LoanController.java",
                "package com.example.demo.model;\n@RequestMapping(\"/loans\")\n"
                        + "public class LoanController { public void get() {} }\n");
        return out;
    }

    private static CycleContextData ctx(Map<String, String> output) {
        return CycleContextData.builder()
                .cycleName("t3bw-evo")
                .currentGoalId("goal-root")
                .currentSubGoalId("goal-loans")
                .currentSubGoalDescription("Implement GET /loans endpoint")
                .currentSubGoalCriteria(List.of("GET /loans returns HTTP 400 for invalid input"))
                .previousOutput(output)
                .build();
    }

    @Test
    void theAnomalyAppearsInTheEvolutionPrompt() {
        String prompt = EvolutionProtocolContract.getUserPrompt(ctx(brokenTree()), null, false);

        assertThat(prompt).contains("STRUCTURAL ANOMALY");
        assertThat(prompt).contains("LoanController");
        assertThat(prompt).contains("RestController");
        // and it tells the model what to do with it rather than leaving a bare fact
        assertThat(prompt).contains("correct it rather than re-writing the code");
    }

    @Test
    void aConsistentArtifactAddsNothingToThePrompt() {
        Map<String, String> consistent = brokenTree();
        consistent.put("src/main/java/com/example/demo/model/LoanController.java",
                "package com.example.demo.model;\n@RestController\n@RequestMapping(\"/loans\")\n"
                        + "public class LoanController { public void get() {} }\n");

        assertThat(EvolutionProtocolContract.getUserPrompt(ctx(consistent), null, false))
                .doesNotContain("STRUCTURAL ANOMALY");
    }

    @Test
    void anEmptyOutputAddsNothing() {
        assertThat(EvolutionProtocolContract.getUserPrompt(ctx(Map.of()), null, false))
                .doesNotContain("STRUCTURAL ANOMALY");
    }
}
