package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.goal.purpose.InputEntry;
import com.walpro.autobuilder.protocol.PromptTokenBudget;
import com.walpro.autobuilder.core.investigate.LogInvestigator;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.core.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T4-EI: while the loop works on a capability that is being adapted from another project, the evolution agent may ask
 * ONE question of that project's source, kept read-only beside the import. The offer is made only under the goal the
 * capability import landed as -- elsewhere there is nothing to adapt -- and the answer comes from a model probing the
 * source step by step (T4-EE's tree probes), so the source is never pasted into a prompt whole.
 *
 * <p>T4-ER: what the source answered is kept with the input ({@value #ANSWERS_FILE}) and shown to every later
 * evolution prompt of that input's work. The kept source does not change, so an answer read from it stays true; the
 * cycle that asked is not the only one that writes the code the answer is about -- a test for it, a repair of it and
 * a rewrite of it come later, under goals that are siblings of the import's goal, not its descendants.
 */
@Slf4j
@Component
public class ReferenceConsultation {

    static final String MARKER = "#ReferenceQuestion";
    static final int MAX_QUESTION_CHARS = 500;
    /** T4-ER: the answered questions of one input's kept source, oldest first, beside the source. */
    static final String ANSWERS_FILE = "/reference-answers.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GoalPlannerService goals;
    private final FileService files;
    private final CapabilityImportService imports;
    private final LogInvestigator investigator;

    public ReferenceConsultation(GoalPlannerService goals, FileService files, CapabilityImportService imports,
                                 LogInvestigator investigator) {
        this.goals = goals;
        this.files = files;
        this.imports = imports;
        this.investigator = investigator;
    }

    /** A source the current work may read: its label, where it is kept, and the capabilities adapted from it. */
    public record Reference(String inputId, String source, Path root, List<String> capabilities) {
    }

    /** The reference the current subgoal works under, or null: it must descend from a capability import's goal. */
    public Reference current() {
        try {
            GoalEntity g = goals.getCurrentSubGoal();
            Set<String> seen = new HashSet<>();
            while (g != null && seen.add(g.getId())) {
                Object input = g.getMetadata() == null ? null : g.getMetadata().get("inputId");
                if (input != null) {
                    Reference r = referenceOf(String.valueOf(input));
                    if (r != null) return r;
                }
                g = g.getParentId() == null ? null : goals.getGoalById(g.getParentId());
            }
        } catch (RuntimeException | IOException e) {
            log.info("T4-EI: no reference could be found for the current work -- {}", e.getMessage());
        }
        return null;
    }

    private Reference referenceOf(String inputId) throws IOException {
        Path root = files.getRootDir().resolve("meta/inputs/" + inputId + ProjectInputService.REFERENCE_DIR);
        if (!Files.isDirectory(root)) return null;
        JsonNode record = imports.record(inputId);
        List<String> adapted = new ArrayList<>();
        for (JsonNode c : record.path("capabilities")) {
            if (c.path("reference").asBoolean(false)) {
                adapted.add(c.path("name").asText() + " (" + c.path("mode").asText() + "): " + c.path("description").asText());
            }
        }
        return new Reference(inputId, record.path("source").asText("the source project"), root, adapted);
    }

    /**
     * T4-ER: the reference whose kept answers the current work is shown -- the one it works under, else the source kept
     * for the project's newest input. Until a newer input arrives the work in the tree is still that input's: its goal
     * closing is not its obligations met, and what follows (a refused closure's work, a failed test's repair) touches
     * the same code under another goal.
     */
    Reference answersFor() {
        Reference r = current();
        if (r != null) return r;
        try {
            List<InputEntry> kept = goals.purpose().ledger().kept();
            return kept.isEmpty() ? null : referenceOf(kept.get(kept.size() - 1).id());
        } catch (RuntimeException | IOException e) {
            log.info("T4-ER: no kept reference could be found for the current work -- {}", e.getMessage());
            return null;
        }
    }

    /** A question the kept source answered, and in which cycle. */
    record Kept(String question, String answer, String cycle) {
    }

    /** What {@code inputId}'s kept source answered so far, oldest first; empty when nothing was asked or it cannot be read. */
    List<Kept> keptAnswers(String inputId) {
        String path = "meta/inputs/" + inputId + ANSWERS_FILE;
        List<Kept> out = new ArrayList<>();
        try {
            if (!files.exists(path)) return out;
            for (JsonNode n : MAPPER.readTree(files.readFile(path))) {
                out.add(new Kept(n.path("question").asText(), n.path("answer").asText(), n.path("cycle").asText(null)));
            }
        } catch (IOException | RuntimeException e) {
            log.info("T4-ER: {} could not be read -- {}", path, e.getMessage());
        }
        return out;
    }

    /** Keeps an answer with its input; the same question asked again replaces what it was answered before. */
    private void keep(Reference r, String question, String answer, String cycleId) {
        try {
            ArrayNode all = MAPPER.createArrayNode();
            for (Kept k : keptAnswers(r.inputId())) {
                if (!k.question().equals(question)) all.add(node(k));
            }
            all.add(node(new Kept(question, answer, cycleId)));
            files.writeFile("meta/inputs/" + r.inputId() + ANSWERS_FILE,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(all) + "\n");
        } catch (IOException | RuntimeException e) {
            log.warn("T4-ER: the answer from {} could not be kept -- {}", r.source(), e.getMessage());
        }
    }

    private static ObjectNode node(Kept k) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("question", k.question());
        n.put("answer", k.answer());
        n.put("cycle", k.cycle());
        return n;
    }

    /**
     * T4-ER: what the kept source already answered, for a prompt -- newest first within {@code budgetTokens}, the
     * rest counted; null when the current work has no kept source or nothing was answered yet.
     */
    public String answersBlock(int budgetTokens) {
        Reference r = answersFor();
        if (r == null) return null;
        List<Kept> kept = keptAnswers(r.inputId());
        if (kept.isEmpty()) return null;
        List<String> shown = new ArrayList<>();
        int used = 0;
        for (int i = kept.size() - 1; i >= 0; i--) {
            String entry = "Question: " + kept.get(i).question() + "\nAnswer:\n" + kept.get(i).answer();
            int cost = PromptTokenBudget.estimateTokens(entry);
            if (used + cost > budgetTokens) break;
            used += cost;
            shown.add(0, entry);
        }
        if (shown.isEmpty()) return null;
        int omitted = kept.size() - shown.size();
        return """
                --- WHAT THE REFERENCE PROJECT %s ANSWERED EARLIER ---
                Earlier in this work %s's source was asked how it does something, and answered by a model reading its \
                files (paths and line numbers are %s's own, not this project's). That source has not changed since, so \
                the answers still hold. Code and tests in THIS project that re-implement or check what they describe \
                keep to them: do not replace it with a rule of your own.
                %s%s
                --- END WHAT THE REFERENCE PROJECT ANSWERED ---""".formatted(r.source(), r.source(), r.source(),
                String.join("\n\n", shown),
                omitted == 0 ? "" : "\n(" + omitted + " earlier answer(s) not shown for space.)");
    }

    /** The offer for a prompt, or null when the current work adapts nothing with a reference kept. */
    public String offerBlock() {
        Reference r = current();
        if (r == null) return null;
        return """
                --- THE REFERENCE PROJECT ---
                This work takes in capabilities from another project, %s, re-implemented in THIS project's own structure; \
                %s's source is kept to read while doing it, not to copy:
                %s
                Before you write, you may ask ONE question about how %s does something -- a model will read its files \
                and answer, citing them.
                To ask, reply with ONLY:
                %s <your question>
                Otherwise reply with your change as usual.
                --- END THE REFERENCE PROJECT ---""".formatted(r.source(), r.source(),
                String.join("\n", r.capabilities().stream().map(c -> "  - " + c).toList()), r.source(), MARKER);
    }

    /** The question in a reply that asks one, or null. */
    static String questionIn(String reply) {
        if (reply == null) return null;
        String s = reply.strip();
        if (!s.startsWith(MARKER)) return null;
        String q = s.substring(MARKER.length()).strip();
        if (q.isEmpty()) return null;
        return q.length() <= MAX_QUESTION_CHARS ? q : q.substring(0, MAX_QUESTION_CHARS);
    }

    /** The reply to send back when {@code reply} asks the reference a question, or null. Never throws. */
    public String answer(String reply, String cycleId) {
        String question = questionIn(reply);
        if (question == null) return null;
        Reference r = current();
        if (r == null) return null;
        LogInvestigator.Investigation inv;
        try {
            inv = investigator.investigate(question, Map.of(), r.root(), LogInvestigator.DEFAULT_PROBES, cycleId);
        } catch (RuntimeException e) {
            inv = new LogInvestigator.Investigation(question, List.of(), null, false);
        }
        log.info("T4-EI: evolution asked the reference {}: \"{}\" -- {} probe(s), {}", r.source(), question,
                inv.steps().size(), inv.answered() ? "answered" : "no answer");
        if (inv.answered()) keep(r, question, inv.answer(), cycleId);
        String body = inv.answered() ? inv.answer() : "Reading the reference project did not find an answer to this question.";
        return "--- ANSWER FROM THE REFERENCE PROJECT " + r.source() + " ---\n"
                + "Question: " + question + "\n"
                + "Answer (found by reading its files; paths and line numbers are its own, not this project's):\n" + body + "\n"
                + "--- END ANSWER ---\n\n"
                + "Now reply with your change to THIS project, in the required format.";
    }
}
