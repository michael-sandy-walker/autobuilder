package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T4-EC stage 3 in the application: what two projects share, structure first ({@link ProjectMatcher}), a model for the
 * pairs structure leaves open, each verdict with who gave it and a proposed decision -- unify, adapt one, or keep both.
 *
 * <p>Nothing is changed in either project: the result is a list for the operator to confirm or correct (stage 4), and
 * for an integration goal to work from. Structure decides only an {@link ProjectMatcher.Level#EXACT} pair (unify); a
 * pair it leaves open goes to the model with both sides shown whole; an answer that does not parse leaves the pair
 * unanswered -- never a guess.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectMatchService {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final String SYSTEM_PROMPT = "You compare two independently written software projects. For a pair of"
            + " things, one from each, you say whether they denote the same thing, and what a merge of the projects"
            + " should do with them. Answer only in the format asked for.";

    private static final Pattern VERDICT = Pattern.compile("(?m)^\\s*VERDICT:\\s*(SAME|RELATED|DIFFERENT)\\b");
    private static final Pattern ACTION = Pattern.compile("(?m)^\\s*ACTION:\\s*(UNIFY|ADAPT_ONE|KEEP_BOTH)\\b");
    private static final Pattern REASON = Pattern.compile("(?m)^\\s*REASON:\\s*(.+)$");
    private static final Pattern NUMBERED = Pattern.compile("(?m)^\\s*(\\d+)\\.\\s*(SAME|RELATED|DIFFERENT)\\s+(UNIFY|ADAPT_ONE|KEEP_BOTH)\\b(.*)$");

    private final LLMClient llmClient;

    /** A pair's decision: what it is, what to do with it, who said so, and why. */
    public record Decision(ProjectMatcher.Kind kind, String a, String b, ProjectMatcher.Level level, double overlap,
                           String verdict, String action, String source, String reason) {
    }

    public List<Decision> match(ProjectMerge.Parent first, ProjectMerge.Parent second, Path outDir) throws IOException {
        Map<String, String> mainA = main(first), mainB = main(second);
        List<ProjectMatcher.Match> found = ProjectMatcher.match(mainA, mainB, criteria(first), criteria(second));
        Map<String, String> sourceOfA = typeSources(mainA), sourceOfB = typeSources(mainB);
        List<Decision> out = new ArrayList<>();
        List<ProjectMatcher.Match> criteriaToAsk = new ArrayList<>();
        for (ProjectMatcher.Match m : found) {
            if (m.level() == ProjectMatcher.Level.EXACT) {
                out.add(new Decision(m.kind(), m.a(), m.b(), m.level(), m.overlap(), "SAME", "UNIFY", "structure", m.why()));
            } else if (m.kind() == ProjectMatcher.Kind.CRITERION) {
                criteriaToAsk.add(m);
            } else {
                out.add(askPair(m, first.label(), second.label(), sourceOfA, sourceOfB));
            }
        }
        out.addAll(askCriteria(criteriaToAsk, first.label(), second.label()));
        write(outDir, first, second, out);
        log.warn("T4-EC: matched {} and {} -- {} pair(s): {} by structure, {} by a model, {} unanswered", first.label(),
                second.label(), out.size(), out.stream().filter(d -> d.source().equals("structure")).count(),
                out.stream().filter(d -> d.source().equals("model")).count(),
                out.stream().filter(d -> d.verdict() == null).count());
        return out;
    }

    private Decision askPair(ProjectMatcher.Match m, String labelA, String labelB, Map<String, String> srcA, Map<String, String> srcB) {
        String ownerA = m.a().contains(".") ? m.a().substring(0, m.a().indexOf('.')) : m.a();
        String ownerB = m.b().contains(".") ? m.b().substring(0, m.b().indexOf('.')) : m.b();
        String prompt = "Project " + labelA + " and project " + labelB + " were written independently and are to be merged"
                + " into one.\n\n" + (m.kind() == ProjectMatcher.Kind.TYPE ? "A type of each" : "An operation of each")
                + ": " + m.a() + " (" + labelA + ") and " + m.b() + " (" + labelB + "). What structure found: " + m.why()
                + ".\n\n=== " + labelA + ": " + ownerA + " ===\n" + srcA.getOrDefault(ownerA, "(source not found)")
                + "\n=== " + labelB + ": " + ownerB + " ===\n" + srcB.getOrDefault(ownerB, "(source not found)")
                + "\n\nDo they denote the same thing? SAME: one concept, written twice. RELATED: the same concept in"
                + " different shapes, or two things that must work together. DIFFERENT: one name, or one shape, for two"
                + " things.\nWhat should the merged project do? UNIFY: keep one of them for both. ADAPT_ONE: keep both,"
                + " with a conversion from one to the other where they meet. KEEP_BOTH: they are separate things.\n\n"
                + "Answer with exactly three lines:\nVERDICT: SAME|RELATED|DIFFERENT\nACTION: UNIFY|ADAPT_ONE|KEEP_BOTH\n"
                + "REASON: <one sentence>";
        String answer = ask(prompt);
        String verdict = group(VERDICT, answer), action = group(ACTION, answer), reason = group(REASON, answer);
        return new Decision(m.kind(), m.a(), m.b(), m.level(), m.overlap(), verdict, action,
                verdict == null || action == null ? "none" : "model", reason == null ? m.why() : reason);
    }

    private List<Decision> askCriteria(List<ProjectMatcher.Match> pairs, String labelA, String labelB) {
        List<Decision> out = new ArrayList<>();
        if (pairs.isEmpty()) return out;
        StringBuilder p = new StringBuilder("Project " + labelA + " and project " + labelB + " were written independently and"
                + " are to be merged into one. Each pair below is an acceptance criterion of " + labelA + " and one of "
                + labelB + ". For each: SAME (they require the same thing), RELATED (they require the same thing of"
                + " different shapes), DIFFERENT; and what the merged project should do: UNIFY (one criterion for both),"
                + " ADAPT_ONE, or KEEP_BOTH.\n\n");
        for (int i = 0; i < pairs.size(); i++) {
            p.append(i + 1).append(". ").append(labelA).append(": ").append(pairs.get(i).a()).append("\n   ")
                    .append(labelB).append(": ").append(pairs.get(i).b()).append("\n");
        }
        p.append("\nAnswer one line per pair: <number>. <SAME|RELATED|DIFFERENT> <UNIFY|ADAPT_ONE|KEEP_BOTH> <reason>");
        String answer = ask(p.toString());
        Map<Integer, String[]> byNumber = new LinkedHashMap<>();
        if (answer != null) {
            Matcher m = NUMBERED.matcher(answer);
            while (m.find()) byNumber.putIfAbsent(Integer.parseInt(m.group(1)), new String[]{m.group(2), m.group(3), m.group(4).strip()});
        }
        for (int i = 0; i < pairs.size(); i++) {
            ProjectMatcher.Match m = pairs.get(i);
            String[] v = byNumber.get(i + 1);
            out.add(new Decision(m.kind(), m.a(), m.b(), m.level(), m.overlap(), v == null ? null : v[0], v == null ? null : v[1],
                    v == null ? "none" : "model", v == null || v[2].isEmpty() ? m.why() : v[2]));
        }
        return out;
    }

    private String ask(String prompt) {
        try {
            ResponseEntity<ChatResponse> r;
            try (var purpose = com.walpro.autobuilder.client.LlmCallContext.scopeIfUnscoped("project-match")) {
                r = llmClient.ask(List.of(new Message("system", SYSTEM_PROMPT), new Message("user", prompt)));
            }
            String text = r.getBody() != null ? r.getBody().getFirstContent() : null;
            return text == null || text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("T4-EC: a match call failed ({}) -- the pair stays unanswered", e.getMessage());
            return null;
        }
    }

    private static String group(Pattern p, String text) {
        if (text == null) return null;
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).strip() : null;
    }

    /** A parent's library sources: every src/main of every module of its tree. */
    static Map<String, String> main(ProjectMerge.Parent p) throws IOException {
        Path out = p.dir().resolve("output");
        Map<String, String> all = ProjectMerge.files(out);
        Map<String, String> main = new LinkedHashMap<>();
        all.forEach((path, text) -> {
            if (path.endsWith(".java") && (path.startsWith("src/main/") || path.contains("/src/main/"))) main.put(path, text);
        });
        return main;
    }

    /** A parent's criteria: every node's, and its non-rule obligations, each text once. */
    static List<String> criteria(ProjectMerge.Parent p) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        JsonNode stack = MAPPER.readTree(p.dir().resolve("meta/GoalStack.json").toFile());
        collect(stack.path("goalTree"), out);
        Path seed = p.dir().resolve("bootstrap/seed.txt"), obl = p.dir().resolve("bootstrap/seed.obligations.txt");
        if (Files.isRegularFile(seed)) {
            for (String line : ProjectMerge.parentObligations(Files.readString(seed),
                    Files.isRegularFile(obl) ? Files.readString(obl) : null)) {
                if (!line.startsWith(com.walpro.autobuilder.goal.SeedRequirements.GATE_MARKER)) out.add(line);
            }
        }
        return List.copyOf(out);
    }

    private static void collect(JsonNode node, Set<String> out) {
        for (JsonNode c : node.path("metadata").path("acceptanceCriteria")) out.add(c.asText().strip());
        for (JsonNode s : node.path("subGoals")) collect(s, out);
    }

    /** Each top-level type's whole source, by simple name. */
    private static Map<String, String> typeSources(Map<String, String> main) {
        Map<String, String> out = new LinkedHashMap<>();
        main.forEach((path, text) -> {
            String file = path.substring(path.lastIndexOf('/') + 1);
            out.putIfAbsent(file.substring(0, file.length() - ".java".length()), text);
        });
        return out;
    }

    private static void write(Path outDir, ProjectMerge.Parent a, ProjectMerge.Parent b, List<Decision> decisions) throws IOException {
        if (outDir == null) return;
        Files.createDirectories(outDir);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("first", Map.of("label", a.label(), "runId", a.runId()));
        doc.put("second", Map.of("label", b.label(), "runId", b.runId()));
        doc.put("matches", decisions);
        Files.writeString(outDir.resolve("MATCHES.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        StringBuilder tsv = new StringBuilder("kind\t" + a.label() + "\t" + b.label() + "\tstructure\tverdict\taction\tsource\treason\n");
        for (Decision d : decisions) {
            tsv.append(d.kind()).append('\t').append(d.a().replace('\t', ' ')).append('\t').append(d.b().replace('\t', ' '))
                    .append('\t').append(d.level()).append('\t').append(Optional.ofNullable(d.verdict()).orElse("?"))
                    .append('\t').append(Optional.ofNullable(d.action()).orElse("?")).append('\t').append(d.source())
                    .append('\t').append(d.reason().replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        Files.writeString(outDir.resolve("MATCHES.tsv"), tsv.toString());
    }
}
