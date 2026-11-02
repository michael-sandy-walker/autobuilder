package com.walpro.autobuilder.core.investigate;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCallContext;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * T4-EE: answers a question about the run from the run's own logs. The model never sees a log whole: it
 * probes one step at a time -- search, read a range, count -- sees the result, and chooses the next probe
 * until it can answer or its probes run out.
 *
 * <p>Nothing here knows what the logs look like. The probes are generic over lines of text; finding the
 * way in (which markers delimit a cycle, how a failure is printed) is the model's work, so a log that
 * changes its format changes nothing here.
 *
 * <p>Read-only. Every probe and its result are kept beside the answer, so a reader can check the answer
 * against what was actually seen.
 */
@Slf4j
@Component
public class LogInvestigator {

    static final int DEFAULT_PROBES = 8;
    static final int MAX_MATCHES = 25;
    static final int MAX_READ_LINES = 60;
    static final int MAX_LINE_CHARS = 300;
    static final int MAX_RESULT_CHARS = 5000;

    static final String SYSTEM = """
            You answer a question about a software run by reading its logs. The logs are too long to show you \
            whole, so you look into them with probes: one probe per reply, and you see its result before you \
            choose the next one.

            Reply with exactly ONE of these, nothing else:

            #Probe search <log> <regex>
              the lines matching the regex (Java syntax, case-insensitive), with their line numbers: the first \
            %d and how many there are in all
            #Probe read <log> <first line> <last line>
              those lines, at most %d at a time
            #Probe count <log> <regex>
              how many lines match

            #Answer
            <the answer, citing the log lines it rests on>

            Answer only from what your probes showed. If they did not show it, say so."""
            .formatted(MAX_MATCHES, MAX_READ_LINES);

    private static final Pattern PROBE = Pattern.compile(
            "#Probe\\s+(search|read|count)\\s+(\\S+)\\s+(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSWER = Pattern.compile("#Answer\\s*(.*)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final LLMClient llmClient;

    @Autowired
    public LogInvestigator(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /** One probe the model asked for and what it got back. */
    public record Step(String probe, String result) {
    }

    /** The question, every step taken, and the answer; {@code answered} is false when no answer came back. */
    public record Investigation(String question, List<Step> steps, String answer, boolean answered) {
    }

    /**
     * @param logs each log's name and its lines, oldest first; a name is what the model uses in a probe
     */
    public Investigation investigate(String question, Map<String, List<String>> logs, int probes, String cycleId) {
        List<Message> conversation = new ArrayList<>();
        conversation.add(new Message("system", SYSTEM));
        conversation.add(new Message("user", opening(question, logs, probes)));
        List<Step> steps = new ArrayList<>();
        try (var scope = LlmCallContext.scope("log-investigator", cycleId)) {
            for (int left = probes; left > 0; left--) {
                String reply = ask(conversation);
                if (reply == null) return new Investigation(question, steps, null, false);
                conversation.add(new Message("assistant", reply));
                Matcher answer = ANSWER.matcher(reply);
                Matcher probe = PROBE.matcher(reply);
                if (answer.find() && !probe.find()) {
                    return new Investigation(question, steps, answer.group(1).strip(), true);
                }
                probe.reset();
                String result;
                String asked;
                if (probe.find()) {
                    asked = probe.group(0).strip();
                    result = run(probe.group(1).toLowerCase(), probe.group(2), probe.group(3).strip(), logs);
                } else {
                    asked = "(no probe)";
                    result = "That was neither a #Probe line nor an #Answer. Reply with exactly one of them.";
                }
                steps.add(new Step(asked, result));
                conversation.add(new Message("user", result + "\n\n(" + (left - 1) + " probes left)"));
            }
            conversation.add(new Message("user", "No probes left. Reply with #Answer from what your probes showed."));
            String last = ask(conversation);
            Matcher answer = last == null ? null : ANSWER.matcher(last);
            if (answer != null && answer.find()) {
                return new Investigation(question, steps, answer.group(1).strip(), true);
            }
            return new Investigation(question, steps, null, false);
        }
    }

    private String ask(List<Message> conversation) {
        try {
            ResponseEntity<ChatResponse> response = llmClient.ask(List.copyOf(conversation));
            return response == null || response.getBody() == null ? null : response.getBody().getFirstContent();
        } catch (RuntimeException e) {
            log.info("T4-EE: log investigation stopped, the model did not answer -- {}", e.getMessage());
            return null;
        }
    }

    static String opening(String question, Map<String, List<String>> logs, int probes) {
        StringBuilder sb = new StringBuilder("QUESTION:\n").append(question).append("\n\nTHE LOGS:\n");
        logs.forEach((name, lines) -> sb.append("  ").append(name).append(" -- ").append(lines.size()).append(" lines\n"));
        sb.append("\nYou have ").append(probes).append(" probes. Reply with your first #Probe.");
        return sb.toString();
    }

    /** Runs one probe; a malformed probe is answered with what was wrong, so the model can correct it. */
    static String run(String verb, String logName, String args, Map<String, List<String>> logs) {
        List<String> lines = logs.get(logName);
        if (lines == null) return "There is no log named " + logName + ". The logs are: " + String.join(", ", logs.keySet()) + ".";
        return switch (verb) {
            case "read" -> read(lines, args);
            case "search", "count" -> {
                Pattern p;
                try {
                    p = Pattern.compile(unquote(args), Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException e) {
                    yield "The regex does not compile: " + e.getDescription() + ".";
                }
                yield verb.equals("count") ? count(lines, p) : search(lines, p);
            }
            default -> "Unknown probe " + verb + ".";
        };
    }

    private static String search(List<String> lines, Pattern p) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!p.matcher(lines.get(i)).find()) continue;
            total++;
            if (total <= MAX_MATCHES && sb.length() < MAX_RESULT_CHARS) {
                sb.append(i + 1).append(": ").append(cut(lines.get(i))).append('\n');
            }
        }
        if (total == 0) return "No line matches.";
        int shown = (int) sb.chars().filter(c -> c == '\n').count();
        return total + " matching lines" + (shown < total ? ", the first " + shown + ":" : ":") + "\n" + sb;
    }

    private static String count(List<String> lines, Pattern p) {
        long n = lines.stream().filter(l -> p.matcher(l).find()).count();
        return n + " matching lines.";
    }

    private static String read(List<String> lines, String args) {
        String[] parts = args.trim().split("\\s+");
        int first;
        int last;
        try {
            first = Integer.parseInt(parts[0]);
            last = parts.length > 1 ? Integer.parseInt(parts[1]) : first + MAX_READ_LINES - 1;
        } catch (NumberFormatException e) {
            return "A read takes two line numbers: #Probe read <log> <first line> <last line>.";
        }
        if (lines.isEmpty()) return "The log is empty.";
        first = Math.max(1, first);
        last = Math.min(lines.size(), Math.min(last, first + MAX_READ_LINES - 1));
        if (first > lines.size()) return "The log has only " + lines.size() + " lines.";
        StringBuilder sb = new StringBuilder();
        for (int i = first; i <= last && sb.length() < MAX_RESULT_CHARS; i++) {
            sb.append(i).append(": ").append(cut(lines.get(i - 1))).append('\n');
        }
        return sb.toString();
    }

    private static String cut(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + " ...";
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'")
                || s.startsWith("/") && s.endsWith("/"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
