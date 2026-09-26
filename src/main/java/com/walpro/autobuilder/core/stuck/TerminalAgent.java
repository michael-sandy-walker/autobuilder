package com.walpro.autobuilder.core.stuck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCallContext;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-DZ: the read-only terminal execution agent -- the OBSERVATION authority of the stuck layer.
 *
 * <p>Asked only when structure has said all it can about a signature and the failure came back. The
 * local model proposes the fact that would let the move succeed and at most {@value #MAX_COMMANDS}
 * allow-listed commands that would show it; the commands run in a {@link ReadOnlyCopy} of the live
 * tree with the failed change and the compiler's block beside it; their output is the answer. The
 * model proposes, the commands answer -- the delivered answer is what was observed, never the model's
 * reading of it.
 *
 * <p>Measured before wiring (2026-09-26, `scripts/terminal_agent_replay.py`): on the six archived
 * questions the structural router left open, the local model's commands answered 4 of 6, 0 writes.
 *
 * <p>Every question and its commands, outputs and verdict are recorded in the graph for the run
 * ({@link TerminalAgentRecorder}) -- session data, kept where the other agents' records are, so later
 * sessions can put the same questions to another model.
 */
@Slf4j
@Component
public class TerminalAgent {

    static final int MAX_COMMANDS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    static final String SYSTEM = "You answer questions about a Java project by choosing shell commands to run in a"
            + " READ-ONLY copy of it. You cannot change any file, and nothing you choose may run the project's code."
            + " Reply with JSON only.";

    @Autowired(required = false)
    private LLMClient llmClient;

    @Autowired(required = false)
    private TerminalAgentRecorder recorder;

    @Value("${autobuilder.stuck.terminal-agent.enabled:true}")
    private boolean enabled = true;

    /** What the agent did for one question, as recorded. */
    public record Exchange(String signature, String proposedQuestion, List<ReadOnlyCopy.Result> commands,
                           boolean answered, boolean wroteToCopy, String modelReply) {
    }

    public boolean available() {
        return enabled && llmClient != null;
    }

    /**
     * Asks the model which fact is missing and observes it. Never throws: an agent that cannot run is an
     * authority without an answer, and the ladder moves on to the operator.
     */
    public StuckDiagnosis.Asked ask(String signature, List<String> evidence, Map<String, String> liveTree,
                                    Map<String, String> candidate, String cycleId) {
        String authority = "observation: commands run in a read-only copy of the tree, chosen by the model";
        String compiler = String.join("\n", evidence.stream().filter(e -> e.startsWith("compiler="))
                .map(e -> e.substring("compiler=".length())).toList());
        if (!available()) return new StuckDiagnosis.Asked("Which fact would let this move succeed?", authority, null, false);
        try (ReadOnlyCopy copy = ReadOnlyCopy.of(liveTree, candidate, compiler)) {
            String before = copy.contentHash();
            String reply = propose(signature, compiler, listing(liveTree, candidate), cycleId);
            JsonNode parsed = parse(reply);
            String question = parsed.path("question").asText(null);
            List<ReadOnlyCopy.Result> results = new ArrayList<>();
            for (JsonNode c : parsed.path("commands")) {
                if (results.size() >= MAX_COMMANDS) break;
                List<String> argv = new ArrayList<>();
                c.forEach(a -> argv.add(a.asText()));
                results.add(copy.run(argv));
            }
            boolean wrote = !before.equals(copy.contentHash());
            StringBuilder answer = new StringBuilder();
            boolean anyOutput = false;
            for (ReadOnlyCopy.Result r : results) {
                if (r.refused() != null) continue;
                String out = r.output() == null ? "" : r.output().strip();
                if (out.isEmpty() || out.startsWith("(could not run") || r.timedOut()) continue;
                anyOutput = true;
                answer.append("\n    $ ").append(String.join(" ", r.argv())).append('\n')
                        .append(indent(out));
            }
            Exchange exchange = new Exchange(signature, question, results, anyOutput, wrote, reply);
            if (recorder != null) recorder.record(cycleId, exchange);
            if (wrote) log.error("T4-DZ: the read-only copy CHANGED while commands ran for '{}' -- answer withheld", signature);
            log.warn("T4-DZ: asked '{}' for '{}' -- {} command(s), {} refused, answered={}", question, signature, results.size(),
                    results.stream().filter(r -> r.refused() != null).count(), anyOutput && !wrote);
            if (!anyOutput || wrote) return new StuckDiagnosis.Asked(question == null ? "Which fact would let this move succeed?"
                    : question, authority, null, false);
            return new StuckDiagnosis.Asked(question, authority,
                    "Observed (commands run on the code as it stands; the question was the model's, the output is not):"
                            + (question == null ? "" : "\n    Question: " + question) + answer, false);
        } catch (Exception e) {
            log.warn("T4-DZ: the terminal agent could not answer '{}': {}", signature, e.getMessage());
            return new StuckDiagnosis.Asked("Which fact would let this move succeed?", authority, null, false);
        }
    }

    private String propose(String signature, String compiler, String files, String cycleId) {
        String user = "The build keeps failing the same way, and reading the project's declarations did not settle why.\n\n"
                + "Failure: " + signature + "\n\n"
                + "The compiler's own output (paths are as the build saw them; in the copy they are relative):\n" + compiler + "\n\n"
                + "The copy holds these files (candidate/ is the change that failed; the rest is the project as it stands):\n"
                + files + "\n\n"
                + "Which fact would let the next change succeed? Choose at most " + MAX_COMMANDS + " commands that show it.\n"
                + "Allowed programs: " + String.join(", ", new java.util.TreeSet<>(ReadOnlyCopy.ALLOWED))
                + ". No shell: no pipes, redirects or globs. Paths relative to the copy.\n"
                + "Reply as JSON: {\"question\": \"<the fact you are looking for>\", \"commands\": [[\"grep\", \"-n\", \"x\", \"path\"], ...]}";
        ResponseEntity<ChatResponse> response;
        try (var scope = LlmCallContext.scope("terminal-agent", cycleId)) {
            response = llmClient.ask(List.of(new Message("system", SYSTEM), new Message("user", user)));
        }
        return response.getBody() == null ? null : response.getBody().getFirstContent();
    }

    static JsonNode parse(String reply) {
        if (reply == null) return MAPPER.createObjectNode();
        Matcher m = JSON_OBJECT.matcher(reply);
        if (!m.find()) return MAPPER.createObjectNode();
        try {
            return MAPPER.readTree(m.group());
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String listing(Map<String, String> tree, Map<String, String> candidate) {
        List<String> out = new ArrayList<>(tree == null ? List.of() : tree.keySet());
        if (candidate != null) candidate.keySet().forEach(k -> out.add("candidate/" + k));
        out.add("COMPILER_OUTPUT.txt");
        return String.join("\n", out.stream().sorted().limit(400).toList());
    }

    private static String indent(String text) {
        return "      " + text.replace("\n", "\n      ");
    }
}
