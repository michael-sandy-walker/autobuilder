package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.JsonNode;
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
 */
@Slf4j
@Component
public class ReferenceConsultation {

    static final String MARKER = "#ReferenceQuestion";
    static final int MAX_QUESTION_CHARS = 500;

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
        String body = inv.answered() ? inv.answer() : "Reading the reference project did not find an answer to this question.";
        return "--- ANSWER FROM THE REFERENCE PROJECT " + r.source() + " ---\n"
                + "Question: " + question + "\n"
                + "Answer (found by reading its files; paths and line numbers are its own, not this project's):\n" + body + "\n"
                + "--- END ANSWER ---\n\n"
                + "Now reply with your change to THIS project, in the required format.";
    }
}
