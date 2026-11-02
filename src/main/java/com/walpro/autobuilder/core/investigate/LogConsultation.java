package com.walpro.autobuilder.core.investigate;

import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T4-EE slice B: an agent retrying failed work may ask ONE question of its run's log before it writes.
 * The offer goes into its prompt; a reply that is a {@value #MARKER} line is answered by
 * {@link LogInvestigator} probing the live logs, and the answer goes back into the same conversation.
 * The agent decides whether to ask and what -- the system does not write the question for it.
 */
@Slf4j
@Component
public class LogConsultation {

    static final String MARKER = "#LogQuestion";
    static final int MAX_QUESTION_CHARS = 500;
    static final List<String> LOGS = List.of("autobuilder.log", "behaviour.ndjson");

    private final LogInvestigator investigator;
    private final boolean enabled;
    private final String basePath;

    @Autowired(required = false)
    private BehaviourLogger behaviourLogger;

    public LogConsultation(LogInvestigator investigator,
                           // Whether an agent retrying failed work is offered one question about the run's log (T4-EE).
                           @Value("${autobuilder.log-consultation.enabled:true}") boolean enabled,
                           @Value("${file.base-path:}") String basePath) {
        this.investigator = investigator;
        this.enabled = enabled;
        this.basePath = basePath;
    }

    /** Whether the capability is on -- read by the capability manifest. */
    public boolean isLive() {
        return enabled;
    }

    /** The offer for a prompt, or null when it is off or the run's log cannot be found. */
    public String offerBlock() {
        if (!enabled || basePath == null || basePath.isBlank()
                || LOGS.stream().noneMatch(n -> Files.isRegularFile(Path.of(basePath, "logs", n)))) return null;
        return """
                --- THIS RUN'S LOG ---
                Your previous attempt was rejected. Every build, test run and rejection of this run so far is in \
                its log. Before you write, you may ask ONE question -- about what happened in this run (for \
                example, which errors the files you are about to change have already had, and how they ended) \
                or about what the project's files contain now. A model will search the log and the project's \
                files and answer.
                To ask, reply with ONLY:
                %s <your question>
                Otherwise reply with your change as usual.
                --- END THIS RUN'S LOG ---""".formatted(MARKER);
    }

    /** The question in a reply that asks one, or null when the reply is not a question. */
    static String questionIn(String reply) {
        if (reply == null) return null;
        String s = reply.strip();
        if (!s.startsWith(MARKER)) return null;
        String q = s.substring(MARKER.length()).strip();
        if (q.isEmpty()) return null;
        return q.length() <= MAX_QUESTION_CHARS ? q : q.substring(0, MAX_QUESTION_CHARS);
    }

    /**
     * The reply to send back when {@code reply} asks a question, or null when it does not. Never throws:
     * a question that could not be answered is answered as such, and the agent goes on to write.
     */
    public String answer(String reply, String role, String cycleId, Path project) {
        String question = questionIn(reply);
        if (!enabled || question == null) return null;
        long t0 = System.currentTimeMillis();
        LogInvestigator.Investigation inv;
        try {
            inv = investigator.investigate(question, logs(), project, LogInvestigator.DEFAULT_PROBES, cycleId);
        } catch (RuntimeException e) {
            log.info("T4-EE: the log question could not be investigated -- {}", e.getMessage());
            inv = new LogInvestigator.Investigation(question, List.of(), null, false);
        }
        long ms = System.currentTimeMillis() - t0;
        List<String> probes = inv.steps().stream().map(LogInvestigator.Step::probe).toList();
        log.info("T4-EE: {} asked the run's log: \"{}\" -- {} probe(s), {} in {} ms", role, question, probes.size(),
                inv.answered() ? "answered" : "no answer", ms);
        for (LogInvestigator.Step s : inv.steps()) log.info("T4-EE:   {}", s.probe());
        if (behaviourLogger != null) {
            behaviourLogger.logQuestionAsked(cycleId, role, question, probes, inv.answered(), inv.answer(), ms);
        }
        String body = inv.answered()
                ? inv.answer()
                : "The log search did not find an answer to this question.";
        return "--- ANSWER FROM THIS RUN'S LOG ---\n"
                + "Question: " + question + "\n"
                + "Answer (found by searching the log and the project's files; line numbers are theirs):\n" + body + "\n"
                + "--- END ANSWER ---\n\n"
                + "Now reply with your change, in the required format.";
    }

    /** The run's logs as they stand now, by name; a log that cannot be read is left out. */
    Map<String, List<String>> logs() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (basePath == null || basePath.isBlank()) return out;
        for (String name : LOGS) {
            Path p = Path.of(basePath, "logs", name);
            try {
                if (Files.isRegularFile(p)) {
                    out.put(name, new String(Files.readAllBytes(p), StandardCharsets.UTF_8).lines().toList());
                }
            } catch (IOException e) {
                log.info("T4-EE: {} could not be read -- {}", p, e.getMessage());
            }
        }
        return out;
    }
}
