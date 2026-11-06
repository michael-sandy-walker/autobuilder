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
 * T4-EE: answers a question about the run from the run's own logs and, when given, the project's files as they
 * are now. The model never sees either whole: it probes one step at a time -- search, read a range, count; list,
 * find, open -- sees the result, and chooses the next probe until it can answer or its probes run out. Slice D:
 * a log holds copies of files as they were when logged, so what a file contains NOW is read from the project
 * (field141 answered "the current content" with a copy two merges old).
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

    public static final int DEFAULT_PROBES = 8;
    static final int MAX_MATCHES = 25;
    static final int MAX_READ_LINES = 60;
    static final int MAX_LINE_CHARS = 300;
    static final int MAX_RESULT_CHARS = 5000;

    private static final String LOG_PROBES = """
            #Probe search <log> <regex>
              the log lines matching the regex (Java syntax, case-insensitive), with their line numbers: the \
            first %d and how many there are in all
            #Probe read <log> <first line> <last line>
              those log lines, at most %d at a time
            #Probe count <log> <regex>
              how many log lines match
            """.formatted(MAX_MATCHES, MAX_READ_LINES);

    private static final String TREE_PROBES = """
            #Probe files <regex>
              the project's file paths matching the regex, at most %d
            #Probe find <regex>
              the lines of the project's files, as they are NOW, matching the regex: path, line number and \
            text, the first %d and how many there are in all
            #Probe open <path> <first line> <last line>
              those lines of a project file as it is NOW, at most %d at a time
            """.formatted(ProjectTree.MAX_FILES_LISTED, MAX_MATCHES, MAX_READ_LINES);

    /**
     * T4-EI: the instructions when there are no logs, only a project's files -- another project's source, read to find
     * out what it can do or how it does something.
     */
    static String treeOnlySystem() {
        return "You answer a question about a software project by reading its FILES. They are not shown to you whole: " +
                "you look with probes, one probe per reply, and you see its result before you choose the next one.\n\n" +
                "Reply with exactly ONE of these, nothing else:\n\n" +
                TREE_PROBES + "\n" +
                "#Answer\n<the answer, in the form the question asks for>\n\n" +
                "Answer only from what your probes showed. If they did not show it, say so.";
    }

    /** The instructions for the sources this investigation has: the run's logs, and the project now when given. */
    static String system(boolean withTree) {
        String sources = withTree
                ? "You answer a question about a software run from two sources: the run's LOGS, which record what " +
                  "happened in order, and the PROJECT'S FILES AS THEY ARE NOW. A copy of a file inside a log is that " +
                  "file as it was when it was logged and may have changed since; for what a file contains now, read " +
                  "the project's file, and for what happened, read the logs."
                : "You answer a question about a software run by reading its logs.";
        return sources + " Neither is shown to you whole: you look with probes, one probe per reply, and you see " +
                "its result before you choose the next one.\n\n" +
                "Reply with exactly ONE of these, nothing else:\n\n" +
                LOG_PROBES + (withTree ? TREE_PROBES : "") + "\n" +
                "#Answer\n<the answer, citing the log lines or file lines it rests on>\n\n" +
                "Answer only from what your probes showed. If they did not show it, say so.";
    }

    private static final Pattern PROBE = Pattern.compile("#Probe\\s+(\\w+)\\s+(.+)", Pattern.CASE_INSENSITIVE);
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
     * @param projectRoot the project as it is now, read-only; null when the question is about the logs alone
     */
    public Investigation investigate(String question, Map<String, List<String>> logs, java.nio.file.Path projectRoot,
                                     int probes, String cycleId) {
        ProjectTree tree = projectRoot == null || !java.nio.file.Files.isDirectory(projectRoot) ? null
                : new ProjectTree(projectRoot);
        List<Message> conversation = new ArrayList<>();
        conversation.add(new Message("system", logs.isEmpty() && tree != null ? treeOnlySystem() : system(tree != null)));
        conversation.add(new Message("user", opening(question, logs, tree, probes)));
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
                    result = run(probe.group(1).toLowerCase(), probe.group(2).strip(), logs, tree);
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

    static String opening(String question, Map<String, List<String>> logs, ProjectTree tree, int probes) {
        StringBuilder sb = new StringBuilder("QUESTION:\n").append(question).append("\n");
        if (!logs.isEmpty()) sb.append("\nTHE LOGS:\n");
        logs.forEach((name, lines) -> sb.append("  ").append(name).append(" -- ").append(lines.size()).append(" lines\n"));
        if (tree != null) sb.append("\nTHE PROJECT NOW: ").append(tree.files().size()).append(" files\n");
        sb.append("\nYou have ").append(probes).append(" probes. Reply with your first #Probe.");
        return sb.toString();
    }

    /** Runs one probe; a malformed probe is answered with what was wrong, so the model can correct it. */
    static String run(String verb, String args, Map<String, List<String>> logs, ProjectTree tree) {
        switch (verb) {
            case "search", "read", "count" -> {
                String[] parts = args.split("\\s+", 2);
                List<String> lines = logs.get(parts[0]);
                if (lines == null) {
                    return "There is no log named " + parts[0] + ". The logs are: " + String.join(", ", logs.keySet()) + ".";
                }
                String rest = parts.length > 1 ? parts[1].strip() : "";
                if (verb.equals("read")) return read(lines, rest, "A read takes two line numbers: #Probe read <log> <first line> <last line>.");
                Pattern p = compile(rest);
                if (p == null) return "The regex does not compile: " + rest + ".";
                return verb.equals("count") ? count(lines, p) : search(lines, p);
            }
            case "files", "find", "open" -> {
                if (tree == null) return "There are no project files to probe here; only the logs.";
                if (verb.equals("open")) {
                    String[] parts = args.split("\\s+", 2);
                    List<String> lines = tree.lines(unquote(parts[0]));
                    if (lines == null) return "There is no readable project file " + parts[0] + ". Use #Probe files <regex> to find its path.";
                    return read(lines, parts.length > 1 ? parts[1] : "1 " + MAX_READ_LINES,
                            "An open takes a path and two line numbers: #Probe open <path> <first line> <last line>.");
                }
                Pattern p = compile(args);
                if (p == null) return "The regex does not compile: " + args + ".";
                return verb.equals("files") ? files(tree, p) : find(tree, p);
            }
            default -> {
                return "Unknown probe " + verb + ".";
            }
        }
    }

    private static Pattern compile(String regex) {
        try {
            return Pattern.compile(unquote(regex), Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private static String files(ProjectTree tree, Pattern p) {
        List<String> paths = tree.matchingPaths(p);
        if (paths.isEmpty()) return "No project file path matches.";
        List<String> shown = paths.subList(0, Math.min(paths.size(), ProjectTree.MAX_FILES_LISTED));
        return paths.size() + " matching paths" + (shown.size() < paths.size() ? ", the first " + shown.size() : "")
                + ":\n" + String.join("\n", shown) + "\n";
    }

    private static String find(ProjectTree tree, Pattern p) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        int shown = 0;
        for (var f : tree.files()) {
            String rel = f.toString().replace('\\', '/');
            List<String> lines = tree.lines(rel);
            if (lines == null) continue;
            for (int i = 0; i < lines.size(); i++) {
                if (!p.matcher(lines.get(i)).find()) continue;
                total++;
                if (shown < MAX_MATCHES && sb.length() < MAX_RESULT_CHARS) {
                    sb.append(rel).append(':').append(i + 1).append(": ").append(cut(lines.get(i).strip())).append('\n');
                    shown++;
                }
            }
        }
        if (total == 0) return "No line in the project's files matches.";
        return total + " matching lines" + (shown < total ? ", the first " + shown + ":" : ":") + "\n" + sb;
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

    private static String read(List<String> lines, String args, String usage) {
        String[] parts = args.trim().split("\\s+");
        int first;
        int last;
        try {
            first = Integer.parseInt(parts[0]);
            last = parts.length > 1 ? Integer.parseInt(parts[1]) : first + MAX_READ_LINES - 1;
        } catch (NumberFormatException e) {
            return usage;
        }
        if (lines.isEmpty()) return "It is empty.";
        first = Math.max(1, first);
        last = Math.min(lines.size(), Math.min(last, first + MAX_READ_LINES - 1));
        if (first > lines.size()) return "It has only " + lines.size() + " lines.";
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
