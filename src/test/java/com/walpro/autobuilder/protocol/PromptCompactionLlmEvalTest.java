package com.walpro.autobuilder.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.agent.interpretation.model.InterpretationOutcome;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCircuitBreaker;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.ChannelRequest;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.merge.RolledBackReport;
import com.walpro.autobuilder.graph.core.ArtifactEntity;
import com.walpro.autobuilder.protocol.evolution.EvolutionProtocolContract;
import com.walpro.autobuilder.protocol.interpretation.InterpretationProtocolContract;
import com.walpro.autobuilder.protocol.interpretation.InterpretationResponseParser;

import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T2-EP behavioural gate (REAL local model, opt-in): the unit tests prove the two compacted prompt
 * surfaces render the right STRING; this proves the local model's DECISION is unchanged when it sees
 * the compacted prompt — the property the compaction is supposed to have.
 *
 * <p>The compaction removed file bodies that the consuming rules never read, so the claim is
 * decision-EQUIVALENCE: the model reaches the same conclusion with the payload present (old) and
 * absent (new). Each surface is tested as an A/B against the local model at the production
 * temperature (0.0), plus one absolute-correctness check that the compacted (shipping) prompt drives
 * the right action:
 * <ul>
 *   <li>surface 1 — a healthy history renders the same interpretation Status whether merge file
 *       bodies are present or elided, and the shipping (elided) prompt reads as NOOP;</li>
 *   <li>surface 1 — a rollback history renders the same Status either way (isolates whether merge
 *       elision, not the rollback block, drives any change);</li>
 *   <li>surface 2 — the evolution prompt built from the structural digest of the previous proposal
 *       yields a valid, code-bearing response (the digest carries enough to propose a change).</li>
 * </ul>
 *
 * <p>Opt-in by contract (needs the local model up): enable with {@code T2EP_PROMPT_COMPACTION_EVAL=1};
 * endpoint via {@code LLM_API_URL} / {@code LLM_API_MODEL} / {@code OPENAI_API_KEY} (defaults mirror
 * application.yml's local Ollama config).
 */
@Tag("prompt-wording-eval")
@EnabledIfEnvironmentVariable(named = "T2EP_PROMPT_COMPACTION_EVAL", matches = "1")
class PromptCompactionLlmEvalTest {

    private static final String BOOK_REPO_BODY =
            "package com.example.library.domain; import java.util.concurrent.ConcurrentHashMap; "
          + "public class BookRepository { private final ConcurrentHashMap<Long, Book> books = new ConcurrentHashMap<>(); "
          + "public Book save(Book b){ books.put(b.getId(), b); return b; } public Book getBook(Long id){ return books.get(id); } }";

    private static final String BOOK_BODY =
            "package com.example.library.domain; public class Book { private Long id; private String title; "
          + "private String isbn; public Long getId(){ return id; } public String getTitle(){ return title; } }";

    private static final String BOOK_CONTROLLER = """
            package com.example.library.web;

            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestBody;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class BookController {
                @PostMapping("/books")
                public Book createBook(@RequestBody Book book) {
                    return repository.save(book);
                }
            }
            """;

    // ---- Surface 1: interpretation artifact history --------------------------------

    @Test
    void surface1_mergeBodyElision_preservesHealthyDecision_andReadsAsNoop() {
        String withBodies = InterpretationProtocolContract.getUserPrompt(
                interpretationContext().build(), healthyHistory(PromptCompactionLlmEvalTest::mergeRaw));
        String elided = InterpretationProtocolContract.getUserPrompt(
                interpretationContext().build(), healthyHistory(PromptCompactionLlmEvalTest::mergeJson));

        // the two prompts differ exactly in the merge file bodies
        assertThat(withBodies).contains("ConcurrentHashMap");
        assertThat(elided).doesNotContain("ConcurrentHashMap");
        assertThat(elided).contains("<file body elided");

        InterpretationOutcome oldOutcome = interpret(withBodies);
        InterpretationOutcome newOutcome = interpret(elided);
        System.out.println("[surface1/healthy] old=" + oldOutcome.status() + " new=" + newOutcome.status()
                + " | newSummary=" + newOutcome.summary());

        assertThat(newOutcome.status())
                .as("eliding merge bodies must not change the interpretation decision on a healthy history")
                .isEqualTo(oldOutcome.status());
        assertThat(newOutcome.status())
                .as("a clearly healthy history reads as NOOP")
                .isEqualTo("NOOP");
    }

    @Test
    void surface1_mergeBodyElision_preservesRollbackDecision_onRealisticStagnatingHistory() {
        // A real rollback cycle's artifact history carries the stagnation/rejection that led to the
        // rolled-back build — NOT a unanimously healthy history. Corpus check (31/31 real rollback
        // interpretation prompts) shows the model answers APPLIED and names the rollback in that
        // realistic setting; this test reproduces it and additionally proves the merge-body elision
        // does not change that decision.
        RolledBackReport rollback = new RolledBackReport(
                "cycle-rb-1",
                List.of("src/main/java/com/example/library/web/BookController.java"),
                "cannot find symbol: BookRepository",
                BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL);
        String withBodies = InterpretationProtocolContract.getUserPrompt(
                interpretationContext().lastRollback(rollback).build(), stagnatingHistory(PromptCompactionLlmEvalTest::mergeRaw));
        String elided = InterpretationProtocolContract.getUserPrompt(
                interpretationContext().lastRollback(rollback).build(), stagnatingHistory(PromptCompactionLlmEvalTest::mergeJson));

        assertThat(elided).contains("<file body elided");
        assertThat(elided).contains("ROLLBACK STATE");

        InterpretationOutcome oldOutcome = interpret(withBodies);
        InterpretationOutcome newOutcome = interpret(elided);
        System.out.println("[surface1/rollback] old=" + oldOutcome.status() + " new=" + newOutcome.status()
                + " | newSummary=" + newOutcome.summary());

        assertThat(newOutcome.status())
                .as("eliding merge bodies must not change the rollback decision")
                .isEqualTo(oldOutcome.status());
        assertThat(newOutcome.status())
                .as("on a realistic rollback history the model must apply ROLLBACK_AWARENESS (Status APPLIED)")
                .isEqualTo("APPLIED");
        assertThat(newOutcome.summary().toLowerCase())
                .as("and name the rollback")
                .contains("roll");
    }

    // ---- Surface 2: evolution last-raw-response digest -----------------------------

    @Test
    void surface2_previousProposalDigest_yieldsCodeBearingProposal() {
        EvolutionOutcome prior = new EvolutionOutcome(
                List.of(new FileMutation("src/main/java/com/example/library/web/BookController.java", BOOK_CONTROLLER)),
                List.of(), "added controller",
                "```java [src/main/java/com/example/library/web/BookController.java]\n" + BOOK_CONTROLLER + "\n```");

        CycleContextData ctx = CycleContextData.builder()
                .cycleName("t2ep-evo")
                .currentGoalId("goal-root")
                .currentGoalDescription("Library REST API. POST /books creates a book and returns 201.")
                .currentSubGoalId("goal-books")
                .currentSubGoalDescription("Implement POST /books returning 201 with the created book")
                .currentSubGoalCriteria(List.of("POST /books returns HTTP 201 with the created book body"))
                .lastEvolutionOutcome(prior)
                .build()
                .issueRequest(ChannelRequest.of(BasicSignals.REPETITION_DETECTED, 1.0, "test",
                        ChannelRequest.horizonFromDecay(1.0, 0.05)));

        String user = EvolutionProtocolContract.getUserPrompt(ctx, null, false);

        // the digest shipped, not the raw echo
        assertThat(user).contains("Structure of your previous evolution proposal");
        assertThat(user).contains("createBook");
        assertThat(user).doesNotContain("import org.springframework.web.bind.annotation.RestController");

        String raw = rawResponse(EvolutionProtocolContract.getSystemPrompt(), user);
        System.out.println("[surface2/repetition] valid=" + EvolutionProtocolContract.isValidResponse(raw)
                + " first=" + firstLine(raw));

        assertThat(EvolutionProtocolContract.isValidResponse(raw))
                .as("the model must produce a valid evolution response from the structural digest")
                .isTrue();
        assertThat(raw)
                .as("given repetition, the model must propose an actual change (a file code block)")
                .containsPattern("```[^\\n`]*\\[[^\\]\\n]+\\]");
    }

    // ---- helpers -------------------------------------------------------------------

    private static CycleContextData.CycleContextDataBuilder interpretationContext() {
        return CycleContextData.builder()
                .cycleName("cycle-current")
                .currentGoalId("goal-root")
                .currentSubGoalId("goal-books")
                .goalProgress(com.walpro.autobuilder.model.GoalProgressState.PROGRESSING)
                .mutationState(com.walpro.autobuilder.model.MutationAcceptanceState.ACCEPTED)
                .cycleResultState(com.walpro.autobuilder.core.lifecycle.CycleResultState.CONTINUE);
    }

    /**
     * A clearly healthy history: rising scores, accepted mutations, prior NOOP interpretations,
     * plus two merge_payload artifacts. {@code mergeRepr} chooses how the merged file appears —
     * {@link #mergeRaw} keeps the full body (the pre-compaction shape), {@link #mergeJson} wraps it
     * in the {@code mergedContent} JSON the shipping prompt elides — so the two histories differ
     * exactly in whether the interpreter sees file bodies.
     */
    private static List<ArtifactEntity> healthyHistory(Function<String, ArtifactEntity> mergeRepr) {
        return List.of(
                artifact("interpretation_summary",
                        "No notable patterns detected; cycle history shows healthy progress with a clear evolution direction and accepted mutations."),
                artifact("interpretation_summary", "No notable patterns detected; steady progress across cycles."),
                artifact("interpretation_summary", "Healthy; each subgoal is advancing with accepted mutations."),
                artifact("evaluation_payload",
                        "{ \"goalProgress\" : \"PROGRESSING\", \"mutationState\" : \"ACCEPTED\", \"scoreNow\" : 0.82, \"cycleId\" : \"cycle-3\", \"subGoalId\" : \"goal-books\", \"message\" : \"Book model implemented with required fields.\" }"),
                artifact("evaluation_payload",
                        "{ \"goalProgress\" : \"PROGRESSING\", \"mutationState\" : \"ACCEPTED\", \"scoreNow\" : 0.71, \"cycleId\" : \"cycle-2\", \"subGoalId\" : \"goal-books\", \"message\" : \"Repository added.\" }"),
                mergeRepr.apply(BOOK_BODY),
                mergeRepr.apply(BOOK_REPO_BODY),
                artifact("deliberation_summary",
                        "mode: UNDIRECTED\nconfidence: 0.55\nrationale: No strong reason to deviate from the default execution flow."));
    }

    /**
     * A realistic pre-rollback history: declining scores, REJECTED/STAGNATING evaluations, prior
     * interpretations already flagging stagnation, and a merge attempt — the shape a real rollback
     * cycle actually carries (contrast {@link #healthyHistory}). {@code mergeRepr} controls whether
     * the merged file appears with its full body or the elided descriptor.
     */
    private static List<ArtifactEntity> stagnatingHistory(Function<String, ArtifactEntity> mergeRepr) {
        return List.of(
                artifact("interpretation_summary",
                        "Stagnation emerging: the same subgoal has been retried across cycles with declining scores."),
                artifact("interpretation_summary",
                        "Evolution is targeting the same files repeatedly with diminishing returns."),
                artifact("evaluation_payload",
                        "{ \"goalProgress\" : \"STAGNATING\", \"mutationState\" : \"REJECTED\", \"scoreNow\" : 0.40, \"cycleId\" : \"cycle-rb-1\", \"subGoalId\" : \"goal-books\", \"message\" : \"No functional change; build broke on a missing symbol.\" }"),
                artifact("evaluation_payload",
                        "{ \"goalProgress\" : \"STAGNATING\", \"mutationState\" : \"REJECTED\", \"scoreNow\" : 0.44, \"cycleId\" : \"cycle-2\", \"subGoalId\" : \"goal-books\", \"message\" : \"Repeated structure; no meaningful change.\" }"),
                mergeRepr.apply(BOOK_CONTROLLER),
                artifact("deliberation_summary",
                        "mode: REPLAN\nconfidence: 0.50\nrationale: Rollback observed; the current subgoal is stuck."));
    }

    /** Pre-compaction shape: the merged file body appears in full in the artifact content. */
    private static ArtifactEntity mergeRaw(String body) {
        return artifact("merge_payload",
                "{ \"cycleId\" : \"cycle-3\", \"subGoalId\" : \"goal-books\", \"merged\" : true }\n" + body);
    }

    /** Shipping shape: the body lives in a mergedContent field, which getUserPrompt elides. */
    private static ArtifactEntity mergeJson(String body) {
        return artifact("merge_payload",
                "{ \"cycleId\" : \"cycle-3\", \"subGoalId\" : \"goal-books\", \"merged\" : true, \"mergedContent\" : \""
                        + escape(body) + "\" }");
    }

    private static ArtifactEntity artifact(String type, String content) {
        return new ArtifactEntity("art-" + Math.abs(content.hashCode()), type, null, content, null);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private InterpretationOutcome interpret(String userPrompt) {
        return new InterpretationResponseParser().parse(
                rawResponse(InterpretationProtocolContract.getSystemPrompt(), userPrompt));
    }

    private String rawResponse(String system, String user) {
        ResponseEntity<ChatResponse> r = realClient().ask(List.of(
                new Message("system", system), new Message("user", user)));
        String c = r.getBody() != null ? r.getBody().getFirstContent() : null;
        return c == null ? "" : c;
    }

    private static String firstLine(String s) {
        String t = s.strip();
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, Math.min(nl, 120));
    }

    private static LLMClient realClient() {
        LLMClient client = new LLMClient(new LlmCircuitBreaker());
        ReflectionTestUtils.setField(client, "apiUrl",
                env("LLM_API_URL", "http://localhost:11434/v1/chat/completions"));
        ReflectionTestUtils.setField(client, "apiKey", env("OPENAI_API_KEY", "local-only-no-key"));
        ReflectionTestUtils.setField(client, "model", env("LLM_API_MODEL", "qwen3-coder:30b"));
        ReflectionTestUtils.setField(client, "reasoningEffort", env("LLM_REASONING_EFFORT", ""));
        ReflectionTestUtils.setField(client, "readTimeoutMs", 180_000);
        ReflectionTestUtils.setField(client, "retryMaxAttempts", 1);
        ReflectionTestUtils.setField(client, "retryDelayMs", 1_000L);
        ReflectionTestUtils.invokeMethod(client, "init");
        return client;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : fallback;
    }
}
