package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService;
import com.walpro.autobuilder.core.build.ProtectedPaths;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.graph.goal.GoalGraphService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * T4-EC stage 1 in the application: {@link ProjectMerge}'s child written as this loop's target, its criteria answered
 * against it, and its goal tree put in the graph.
 *
 * <p>The child is written under {@code file.base-path}, which must hold no code yet: the loop's own test gate, record
 * of what was adopted and verifier then read it as they read any target. Each distinct criterion is answered once:
 * <ol>
 *   <li>by STRUCTURE where a record answers it -- the test run of the child, the changes since adoption, the
 *       declarations as adopted ({@link CriteriaVerificationService#structuralAnswer}; a {@code gate: tests} obligation
 *       by the test gate's run);</li>
 *   <li>by a MODEL for the rest, in one check with the child's test run in the prompt;</li>
 *   <li>else unanswered.</li>
 * </ol>
 * Who answered is kept beside every verdict: a criterion that structure finds unmet in the child is the merge's
 * finding; one only a model finds unmet is a lead to check by hand.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectMergeService {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public static final String REPORT = "MERGE-REPORT.tsv";

    private final CriteriaVerificationService verifier;
    private final TestRunGate testRunGate;
    private final GoalGraphService goalGraphService;

    @Value("${file.base-path}")
    private String fileBasePath;

    /** One distinct criterion's verdict in the child: met (null when unanswered) and who answered. */
    public record Verdict(String criterion, Boolean met, String source, List<ProjectMerge.Origin> origins) {
    }

    /** What the merge produced. {@code unmet} names every criterion found unmet, with who found it. */
    public record Report(String child, int files, List<String> conflicts, Integer testsRun, Boolean testsPass,
                         int criteria, long byStructure, long byModel, long unanswered, List<String> unmet,
                         boolean graphWritten, List<String> notes) {
    }

    public synchronized Report merge(ProjectMerge.Parent first, ProjectMerge.Parent second) throws IOException {
        Path root = Path.of(fileBasePath);
        Path output = root.resolve("output");
        if (Files.isDirectory(output.resolve("src"))) {
            throw new IllegalStateException(output + " already holds code -- a merge writes a new child, never over one");
        }
        ProjectMerge.Result r = ProjectMerge.merge(first, second, Instant.now());
        List<String> notes = new ArrayList<>(r.notes());
        write(output, r.tree());
        write(root.resolve(ProtectedPaths.BASELINE_DIR), r.adopted());
        if (r.manifest() != null) {
            Files.writeString(root.resolve(com.walpro.autobuilder.core.build.AdoptionManifest.PATH),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(r.manifest()));
        }
        write(root.resolve("bootstrap"), r.bootstrap());
        Files.writeString(root.resolve("MERGE-PROVENANCE.tsv"), "file\tmember\tfrom\n" + String.join("\n", r.provenance()) + "\n");
        Files.writeString(root.resolve("MERGE-CONFLICTS.txt"), r.conflicts().isEmpty() ? "" : String.join("\n", r.conflicts()) + "\n");
        log.warn("T4-EC: merged {} and {} into {} -- {} file(s), {} conflict(s), {} distinct criteria",
                first.label(), second.label(), root, r.tree().size(), r.conflicts().size(), r.criteria().size());

        testRunGate.runOnTree("project-merge", true);
        Optional<TestRunReport> run = testRunGate.freshReport();
        if (run.isEmpty()) notes.add("the child's test run left no report -- test claims stay unanswered");

        Map<String, String> files = new LinkedHashMap<>();
        r.tree().forEach((p, t) -> {
            if (p.startsWith("src/") || p.equals("pom.xml")) files.put(p, t);
        });
        List<Verdict> verdicts = answer(r, files, run, r.goalStack().path("goalTree").path("description").asText());
        stamp(r.goalStack(), verdicts);
        Files.createDirectories(root.resolve("meta"));
        Files.writeString(root.resolve("meta/GoalStack.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(r.goalStack()));
        Files.writeString(root.resolve(REPORT), report(verdicts));

        boolean graphWritten = false;
        try {
            GoalEntity tree = MAPPER.treeToValue(r.goalStack().get("goalTree"), GoalEntity.class);
            save(tree, null);
            graphWritten = true;
        } catch (Exception e) {
            notes.add("the goal tree did not reach the graph: " + e.getMessage());
            log.warn("T4-EC: the merged goal tree did not reach the graph: {}", e.getMessage());
        }
        List<String> unmet = verdicts.stream().filter(v -> Boolean.FALSE.equals(v.met()))
                .map(v -> v.criterion() + " [" + v.source() + "]").toList();
        Report out = new Report(root.toString(), r.tree().size(), r.conflicts(), run.map(TestRunReport::run).orElse(null),
                run.map(TestRunReport::passes).orElse(null), verdicts.size(),
                verdicts.stream().filter(v -> v.source().startsWith("structure")).count(),
                verdicts.stream().filter(v -> v.source().equals("model")).count(),
                verdicts.stream().filter(v -> v.met() == null).count(), unmet, graphWritten, notes);
        log.warn("T4-EC: {} criteria answered against the child -- {} by structure, {} by a model, {} unanswered; unmet: {}",
                out.criteria(), out.byStructure(), out.byModel(), out.unanswered(), unmet.isEmpty() ? "none" : unmet);
        return out;
    }

    private List<Verdict> answer(ProjectMerge.Result r, Map<String, String> files, Optional<TestRunReport> run, String subject) {
        Map<String, Verdict> byText = new LinkedHashMap<>();
        List<ProjectMerge.Criterion> open = new ArrayList<>();
        for (ProjectMerge.Criterion c : r.criteria()) {
            Boolean met;
            String source;
            if (c.gateTests()) {
                met = run.map(TestRunReport::passes).orElse(null);
                source = "structure: the test gate";
            } else {
                met = verifier.structuralAnswer(c.text(), c.origins().get(0).goalDescription(), files);
                source = "structure";
            }
            if (met == null) open.add(c);
            else byText.put(c.text(), new Verdict(c.text(), met, source, c.origins()));
        }
        Map<Integer, Boolean> model = open.isEmpty() ? Map.of()
                : verifier.verifyWithTestRun(subject, open.stream().map(ProjectMerge.Criterion::text).toList(), files);
        for (int i = 0; i < open.size(); i++) {
            ProjectMerge.Criterion c = open.get(i);
            Boolean met = model.get(i + 1);
            byText.put(c.text(), new Verdict(c.text(), met, met == null ? "none" : "model", c.origins()));
        }
        List<Verdict> out = new ArrayList<>();
        for (ProjectMerge.Criterion c : r.criteria()) out.add(byText.get(c.text()));
        return out;
    }

    /** Each node's verdicts in the child beside the ones it had in its parent; obligations on the parent's root. */
    private static void stamp(ObjectNode goalStack, List<Verdict> verdicts) {
        Map<String, ObjectNode> nodes = new LinkedHashMap<>();
        index(goalStack.path("goalTree"), nodes);
        for (Verdict v : verdicts) {
            for (ProjectMerge.Origin o : v.origins()) {
                ObjectNode node = nodes.get(o.goalId());
                if (node == null) continue;
                ObjectNode meta = (ObjectNode) node.path("metadata");
                if ("criterion".equals(o.kind())) {
                    if (v.met() != null) meta.withObject("/criteriaPassStatus").put(String.valueOf(o.index()), v.met());
                    meta.withObject("/criteriaVerdictSource").put(String.valueOf(o.index()), v.source());
                } else {
                    ObjectNode row = meta.withArray("mergeObligations").addObject();
                    row.put("text", v.criterion());
                    if (v.met() == null) row.putNull("met");
                    else row.put("met", v.met());
                    row.put("source", v.source());
                    row.put("kind", o.kind());
                }
            }
        }
    }

    private static void index(JsonNode node, Map<String, ObjectNode> out) {
        if (!node.isObject()) return;
        out.put(node.path("id").asText(), (ObjectNode) node);
        for (JsonNode c : node.path("subGoals")) index(c, out);
    }

    private static String report(List<Verdict> verdicts) {
        StringBuilder sb = new StringBuilder("met\tsource\tcriterion\tfrom (parent:kind:goal#index=verdict there)\n");
        for (Verdict v : verdicts) {
            sb.append(v.met() == null ? "?" : v.met() ? "yes" : "NO").append('\t').append(v.source()).append('\t')
                    .append(v.criterion().replace('\t', ' ')).append('\t');
            List<String> from = new ArrayList<>();
            for (ProjectMerge.Origin o : v.origins()) {
                from.add(o.parent() + ":" + o.kind() + ":" + o.sourceGoalId() + "#" + o.index()
                        + (o.parentVerdict() == null ? "" : "=" + o.parentVerdict()));
            }
            sb.append(String.join(", ", from)).append('\n');
        }
        return sb.toString();
    }

    private void save(GoalEntity g, String parentId) {
        goalGraphService.save(g);
        if (parentId != null) goalGraphService.setParent(g.getId(), parentId);
        for (GoalEntity c : g.getSubGoals()) save(c, g.getId());
    }

    private static void write(Path dir, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
    }
}
