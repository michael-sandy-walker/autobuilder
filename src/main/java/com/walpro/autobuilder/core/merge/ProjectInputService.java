package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.walpro.autobuilder.agent.evaluation.CriteriaVerificationService;
import com.walpro.autobuilder.core.build.AdoptionManifest;
import com.walpro.autobuilder.core.build.ProtectedPaths;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalPlannerService;
import com.walpro.autobuilder.goal.SeedObligationsFile;
import com.walpro.autobuilder.goal.SeedObligationsGenerator;
import com.walpro.autobuilder.goal.SeedRequirements;
import com.walpro.autobuilder.goal.purpose.InputEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * T4-EC slice 4: one project brought into the project this application is working on, as one operation over code,
 * goal tree, criteria and graph, on the live state. The caller holds the cycle lock, so a stopped project and a running
 * one between cycles are the same call.
 */
@Slf4j
@Service
public class ProjectInputService {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public record Request(String label, String runId, Path dir, String intent, String addedBy) {
    }

    /** One carried criterion's verdict in the merged tree: met (null when unanswered) and who answered. */
    public record Verdict(String criterion, Boolean met, String source, List<ProjectMerge.Origin> origins) {
    }

    public record Report(String inputId, String goalId, List<String> placed, List<String> conflicts, List<String> notes,
                         int criteria, long byStructure, long byModel, long unanswered, List<String> unmet,
                         int obligations) {
    }

    private final GoalPlannerService goals;
    private final FileService files;
    private final AdoptionManifest manifest;
    private final TestRunGate testRunGate;
    private final CriteriaVerificationService verifier;
    private final ObjectProvider<SeedObligationsGenerator> generator;

    public ProjectInputService(GoalPlannerService goals, FileService files, AdoptionManifest manifest, TestRunGate testRunGate,
                               CriteriaVerificationService verifier, ObjectProvider<SeedObligationsGenerator> generator) {
        this.goals = goals;
        this.files = files;
        this.manifest = manifest;
        this.testRunGate = testRunGate;
        this.verifier = verifier;
        this.generator = generator;
    }

    public synchronized Report bring(Request r) throws IOException {
        if (r.intent() == null || r.intent().isBlank()) throw new IllegalArgumentException("an input needs an intent");
        if (goals.getRootGoal() == null) throw new IllegalStateException("there is no project to bring this into yet");
        Path root = files.getRootDir();
        Path output = root.resolve("output");
        ProjectMerge.Parent parent = new ProjectMerge.Parent(r.label(), r.runId(), r.dir());
        ProjectInput.Landing landing = ProjectInput.land(ProjectMerge.files(output),
                ProjectMerge.files(root.resolve(ProtectedPaths.BASELINE_DIR)), parent);

        manifest.recordIfAbsent();   // the project as it stood, before the input's files are in it
        for (Map.Entry<String, String> e : landing.files().entrySet()) {
            Path p = output.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
        manifest.addFiles(landing.placed());

        InputEntry input = goals.purpose().addProjectInput(r.intent(), r.addedBy(), r.runId(), landing.sourceLineage(),
                landing.treeDigest());
        String dir = "meta/inputs/" + input.id();
        files.ensureFolderExists(dir);
        files.writeFile(dir + "/placed.txt", String.join("\n", landing.placed()) + "\n");
        files.writeFile(dir + ProtectedPaths.INPUT_FILE, String.join("\n", landing.protectedLines()) + "\n");
        int obligations = writeObligations(input, landing.obligations());

        List<Verdict> verdicts = answer(landing, r.intent());
        GoalEntity tree = null;
        if (landing.tree() != null) {
            stamp(landing.tree(), verdicts);
            tree = MAPPER.treeToValue(landing.tree(), GoalEntity.class);
        }
        List<String> unmet = verdicts.stream().filter(v -> Boolean.FALSE.equals(v.met())).map(Verdict::criterion).toList();
        String goalId = goals.landInput(input.id(), r.intent(), tree, unmet);
        String sourceRoot = landing.tree() == null ? null : landing.tree().path("metadata").path("sourceGoalId").asText(null);
        goals.purpose().linkInput(input.id(), goalId, sourceRoot);

        log.warn("T4-EC: {} brought in as {} -- {} file(s) placed, {} conflict(s), {} criteria answered, {} unmet, {} obligation(s)",
                r.label(), input.id(), landing.placed().size(), landing.conflicts().size(), verdicts.size(), unmet.size(), obligations);
        return new Report(input.id(), goalId, landing.placed(), landing.conflicts(), landing.notes(), verdicts.size(),
                verdicts.stream().filter(v -> v.source().startsWith("structure")).count(),
                verdicts.stream().filter(v -> v.source().equals("model")).count(),
                verdicts.stream().filter(v -> v.met() == null).count(), unmet, obligations);
    }

    /** The intent's lines, generated like a text input's, then what the input verified; the operator may edit them. */
    private int writeObligations(InputEntry input, List<String> verified) throws IOException {
        String path = goals.purpose().obligationsPath(input);
        SeedObligationsGenerator g = generator.getIfAvailable();
        if (g != null) g.ensureFor(input.content(), path);
        List<String> lines = new ArrayList<>();
        if (files.exists(path)) lines.addAll(SeedObligationsFile.read(files.readFile(path), input.content()).obligations());
        if (lines.isEmpty()) lines.addAll(SeedRequirements.statedVerifiableIn(input.content()));
        for (String v : verified) {
            if (!lines.contains(v)) lines.add(v);
        }
        files.writeFile(path, SeedObligationsFile.render(input.content(), lines));
        return lines.size();
    }

    /** Every carried criterion against the merged tree, once: the test gate, then structure, then a model. */
    private List<Verdict> answer(ProjectInput.Landing landing, String subject) throws IOException {
        if (landing.criteria().isEmpty()) return List.of();
        testRunGate.runOnTree("project-input", true);
        Optional<TestRunReport> run = testRunGate.freshReport();
        Map<String, String> tree = new LinkedHashMap<>();
        new TreeMap<>(ProjectMerge.files(files.getRootDir().resolve("output"))).forEach((p, t) -> {
            if (p.startsWith("src/") || p.equals("pom.xml")) tree.put(p, t);
        });
        Map<String, Verdict> byText = new LinkedHashMap<>();
        List<ProjectMerge.Criterion> open = new ArrayList<>();
        for (ProjectMerge.Criterion c : landing.criteria()) {
            Boolean met;
            String source;
            if (c.gateTests()) {
                met = run.map(TestRunReport::passes).orElse(null);
                source = "structure: the test gate";
            } else {
                met = verifier.structuralAnswer(c.text(), c.origins().get(0).goalDescription(), tree);
                source = "structure";
            }
            if (met == null) open.add(c);
            else byText.put(c.text(), new Verdict(c.text(), met, source, c.origins()));
        }
        Map<Integer, Boolean> model = open.isEmpty() ? Map.of()
                : verifier.verifyWithTestRun(subject, open.stream().map(ProjectMerge.Criterion::text).toList(), tree);
        for (int i = 0; i < open.size(); i++) {
            ProjectMerge.Criterion c = open.get(i);
            Boolean met = model.get(i + 1);
            byText.put(c.text(), new Verdict(c.text(), met, met == null ? "none" : "model", c.origins()));
        }
        List<Verdict> out = new ArrayList<>();
        for (ProjectMerge.Criterion c : landing.criteria()) out.add(byText.get(c.text()));
        return out;
    }

    /** Each node's verdicts as answered in the merged tree; unanswered, the input's own verdict stands. */
    private static void stamp(ObjectNode tree, List<Verdict> verdicts) {
        Map<String, ObjectNode> nodes = new LinkedHashMap<>();
        index(tree, nodes);
        for (ObjectNode node : nodes.values()) {
            JsonNode carried = node.path("metadata").path("parentCriteriaPassStatus");
            if (carried.isObject()) ((ObjectNode) node.get("metadata")).set("criteriaPassStatus", carried.deepCopy());
        }
        for (Verdict v : verdicts) {
            for (ProjectMerge.Origin o : v.origins()) {
                ObjectNode node = nodes.get(o.goalId());
                if (node == null || !"criterion".equals(o.kind())) continue;
                ObjectNode meta = (ObjectNode) node.path("metadata");
                if (v.met() != null) meta.withObject("/criteriaPassStatus").put(String.valueOf(o.index()), v.met());
                meta.withObject("/criteriaVerdictSource").put(String.valueOf(o.index()), v.source());
            }
        }
    }

    private static void index(JsonNode node, Map<String, ObjectNode> out) {
        if (!node.isObject()) return;
        out.put(node.path("id").asText(), (ObjectNode) node);
        for (JsonNode c : node.path("subGoals")) index(c, out);
    }
}
