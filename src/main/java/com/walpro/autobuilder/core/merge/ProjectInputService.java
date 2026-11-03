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
import com.walpro.autobuilder.goal.purpose.InputEvent;
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
import java.time.Instant;
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

    /** @param runs this input's module is what the project runs, in a project of modules */
    public record Request(String label, String runId, Path dir, String intent, String addedBy, boolean runs) {
    }

    /** One carried criterion's verdict in the merged tree: met (null when unanswered) and who answered. */
    public record Verdict(String criterion, Boolean met, String source, List<ProjectMerge.Origin> origins) {
    }

    public record Report(String inputId, String goalId, List<String> placed, List<String> corrections, List<InputEvent> conflicts,
                         List<String> notes, int criteria, long byStructure, long byModel, long unanswered,
                         List<String> unmet, int obligations) {
    }

    /**
     * T4-ED: where an input under way stands, for the home screen -- the step out of {@link #STEPS}, and within the
     * slow ones how far through it is ({@code done} of {@code of}; 0 of 0 when a step has no count). {@code all} names
     * every step, so a reader shows them without keeping its own list.
     */
    public record Progress(String input, int step, int steps, String doing, int done, int of, Instant since, List<String> all) {
    }

    /** What bringing an input in does, in order. */
    public static final List<String> STEPS = List.of(
            "placing its code",
            "writing its obligations",
            "holding files both projects have",
            "finding the types its intent uses",
            "comparing its types with this project's",
            "answering its criteria on the merged code",
            "checking what cannot hold here",
            "placing its goals");

    private volatile Progress progress;

    /** The input under way, or empty when none is. */
    public Optional<Progress> progress() {
        return Optional.ofNullable(progress);
    }

    private void step(String input, int step, int done, int of) {
        Progress was = progress;
        progress = new Progress(input, step + 1, STEPS.size(), STEPS.get(step), done, of,
                was != null && was.step() == step + 1 ? was.since() : Instant.now(), STEPS);
    }

    private final GoalPlannerService goals;
    private final FileService files;
    private final AdoptionManifest manifest;
    private final TestRunGate testRunGate;
    private final CriteriaVerificationService verifier;
    private final ObjectProvider<SeedObligationsGenerator> generator;
    private final InputComparison comparison;

    public ProjectInputService(GoalPlannerService goals, FileService files, AdoptionManifest manifest, TestRunGate testRunGate,
                               CriteriaVerificationService verifier, ObjectProvider<SeedObligationsGenerator> generator,
                               InputComparison comparison) {
        this.goals = goals;
        this.files = files;
        this.manifest = manifest;
        this.testRunGate = testRunGate;
        this.verifier = verifier;
        this.generator = generator;
        this.comparison = comparison;
    }

    public synchronized Report bring(Request r) throws IOException {
        if (r.intent() == null || r.intent().isBlank()) throw new IllegalArgumentException("an input needs an intent");
        if (goals.getRootGoal() == null) throw new IllegalStateException("there is no project to bring this into yet");
        try {
            return land(r);
        } finally {
            progress = null;
        }
    }

    private Report land(Request r) throws IOException {
        step(r.label(), 0, 0, 0);
        Path root = files.getRootDir();
        Path output = root.resolve("output");
        ProjectMerge.Parent parent = new ProjectMerge.Parent(r.label(), r.runId(), r.dir());
        Map<String, String> before = ProjectMerge.files(output);
        ProjectInput.Landing landing = ProjectInput.land(before,
                ProjectMerge.files(root.resolve(ProtectedPaths.BASELINE_DIR)), parent);

        Files.createDirectories(output);
        manifest.recordIfAbsent();   // the project as it stood, before the input's files are in it
        for (Map.Entry<String, String> e : landing.files().entrySet()) {
            Path p = output.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
        if (r.runs() && landing.module() != null) {
            Files.writeString(output.resolve(com.walpro.autobuilder.core.build.ProjectLayout.RUN_MODULE_FILE), landing.module() + "\n");
        }
        manifest.addFiles(landing.placed());
        for (String p : landing.placed()) {
            // The text its declarations are kept against.
            if (landing.protectedLines().contains(ProtectedPaths.MEMBERS_LINE + p)) {
                files.writeFile(ProtectedPaths.BASELINE_DIR + "/" + p, landing.files().get(p));
            }
        }

        InputEntry input = goals.purpose().addProjectInput(r.intent(), r.addedBy(), r.runId(), landing.sourceLineage(),
                landing.treeDigest());
        String dir = "meta/inputs/" + input.id();
        files.ensureFolderExists(dir);
        files.writeFile(dir + "/placed.txt", String.join("\n", landing.placed()) + "\n");
        files.writeFile(dir + ProtectedPaths.INPUT_FILE, String.join("\n", landing.protectedLines()) + "\n");
        List<String> intentLines = new ArrayList<>();
        step(r.label(), 1, 0, 0);
        int obligations = writeObligations(input, landing.obligations(), intentLines);
        step(r.label(), 2, 0, 0);
        holdFiles(input, landing, parent);
        step(r.label(), 3, 0, 0);
        List<String> uses = typesToUse(r, landing.placed());
        files.writeFile(dir + IntentUse.USES_FILE, String.join("\n", uses) + "\n");
        List<String> corrections = compare(input, before, parent);

        step(r.label(), 5, 0, landing.criteria().size());
        List<Verdict> verdicts = answer(landing, r.intent());
        // A carried claim that failed here is unfinished work, unless it cannot be true of this project at all.
        String specification = goals.specification();
        long failed = verdicts.stream().filter(v -> Boolean.FALSE.equals(v.met())).count();
        int checked = 0;
        for (Verdict v : verdicts) {
            if (Boolean.FALSE.equals(v.met())) step(r.label(), 6, checked++, (int) failed);
            if (Boolean.FALSE.equals(v.met()) && comparison.contradicts(specification, r.intent(), v.criterion())) {
                goals.purpose().raiseConflict(input.id(), v.criterion(), specification.strip().lines().findFirst().orElse(""),
                        v.criterion(), v.criterion(), null);
            }
        }
        step(r.label(), 7, 0, 0);
        java.util.Set<String> out = goals.purpose().linesOut();
        GoalEntity tree = null;
        if (landing.tree() != null) {
            stamp(landing.tree(), verdicts);
            tree = MAPPER.treeToValue(landing.tree(), GoalEntity.class);
        }
        List<String> unmet = verdicts.stream().filter(v -> Boolean.FALSE.equals(v.met())).map(Verdict::criterion)
                .filter(c -> !out.contains(c.strip())).toList();
        // The goal closes on what the intent asks: without criteria nothing could ever close it (field119: the run sat on
        // it from 39/39 to its deadline), and field117 only closed because a stale line happened to land on its goal.
        List<String> criteria = new ArrayList<>(intentLines);
        unmet.stream().filter(u -> !criteria.contains(u)).forEach(criteria::add);
        String goalId = goals.landInput(input.id(), r.intent(), tree, criteria);
        String sourceRoot = landing.tree() == null ? null : landing.tree().path("metadata").path("sourceGoalId").asText(null);
        goals.purpose().linkInput(input.id(), goalId, sourceRoot);

        log.warn("T4-EC: {} brought in as {} -- {} file(s) placed, {} correction(s), {} conflict(s) held, {} criteria answered, {} unmet, {} obligation(s), to use: {}",
                r.label(), input.id(), landing.placed().size(), corrections.size(), goals.purpose().openConflicts().size(),
                verdicts.size(), unmet.size(), obligations, uses);
        List<InputEvent> conflicts = goals.purpose().openConflicts().stream().filter(e -> e.inputId().equals(input.id())).toList();
        return new Report(input.id(), goalId, landing.placed(), corrections, conflicts, landing.notes(), verdicts.size(),
                verdicts.stream().filter(v -> v.source().startsWith("structure")).count(),
                verdicts.stream().filter(v -> v.source().equals("model")).count(),
                verdicts.stream().filter(v -> v.met() == null).count(), unmet, obligations);
    }

    /** The input's types its intent says to use, by placed path: closure asks structure about them. */
    private List<String> typesToUse(Request r, List<String> placed) {
        List<String> types = placed.stream().filter(IntentUse::isMainType).toList();
        if (types.isEmpty()) return List.of();
        List<String> named = comparison.typesToUse(r.label(), r.intent(), types.stream().map(IntentUse::simpleName).toList());
        return types.stream().filter(p -> named.contains(IntentUse.simpleName(p))).toList();
    }

    /** A file both projects have: the receiving one stays, the input's copy is kept with the conflict. */
    private void holdFiles(InputEntry input, ProjectInput.Landing landing, ProjectMerge.Parent parent) throws IOException {
        Path source = Files.isDirectory(parent.dir().resolve("output")) ? parent.dir().resolve("output") : parent.dir();
        for (String path : landing.conflictedFiles()) {
            String held = "meta/inputs/" + input.id() + "/held/" + path;
            Path to = files.getRootDir().resolve(held);
            Files.createDirectories(to.getParent());
            Files.copy(source.resolve(path), to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            goals.purpose().raiseConflict(input.id(), path, "the receiving project's file", "the input's file", null, held);
        }
    }

    /**
     * Each of the input's types against what deals with the same thing here: structure names nothing a model has not
     * got to name better (it pairs by method names, so {@code getTitle()} and {@code title()} never meet), so a model
     * finds the candidates, and every side is shown with the types it refers to: shown alone, a type reads as having no
     * rule at all where the rule sits in a type it uses.
     */
    private List<String> compare(InputEntry input, Map<String, String> receiving, ProjectMerge.Parent parent) throws IOException {
        Path dir = parent.dir();
        Map<String, String> theirs = new TreeMap<>();
        ProjectMerge.files(Files.isDirectory(dir.resolve("output")) ? dir.resolve("output") : dir).forEach((p, t) -> {
            if (p.startsWith("src/main/java/") && p.endsWith(".java")) theirs.put(p, t);
        });
        Map<String, String> ours = new TreeMap<>();
        receiving.forEach((p, t) -> {
            if (p.contains("src/main/java/") && p.endsWith(".java")) ours.put(p, t);
        });
        if (theirs.isEmpty() || ours.isEmpty()) return List.of();
        String specification = goals.specification();
        List<String> names = ours.keySet().stream().map(ProjectInputService::simpleName).sorted().toList();
        List<String> corrections = new ArrayList<>();
        java.util.Set<String> asked = new java.util.HashSet<>();
        int compared = 0;
        for (Map.Entry<String, String> type : theirs.entrySet()) {
            step(parent.label(), 4, compared++, theirs.size());
            List<String> same = comparison.sameConcept(specification, names, type.getValue());
            if (same.isEmpty()) continue;
            String ourCode = named(ours, same);
            String theirCode = withWhatItRefersTo(type, theirs);
            for (InputComparison.Difference d : comparison.differences(specification, ourCode, theirCode)) {
                if (!asked.add(d.what().toLowerCase(java.util.Locale.ROOT))) continue;
                if (comparison.settledByIntent(input.content(), d)) {
                    goals.purpose().recordCorrection(input.id(), d.what(), d.receiving(), d.other(),
                            comparison.stating(goals.statedRules(), d));
                    addRequirement(input, d.what() + ": " + d.other());
                    corrections.add(d.what());
                } else {
                    goals.purpose().raiseConflict(input.id(), d.what(), d.receiving(), d.other(), null, null);
                }
            }
        }
        return corrections;
    }

    private static String simpleName(String path) {
        return path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
    }

    /** Every receiving file named after a candidate: a rule of {@code Book} may sit in {@code BookService}. */
    private static String named(Map<String, String> ours, List<String> same) {
        StringBuilder out = new StringBuilder();
        ours.forEach((p, t) -> {
            if (same.stream().anyMatch(simpleName(p)::startsWith)) out.append(t).append("\n\n");
        });
        return out.toString();
    }

    private static String withWhatItRefersTo(Map.Entry<String, String> type, Map<String, String> theirs) {
        StringBuilder out = new StringBuilder(type.getValue());
        theirs.forEach((p, t) -> {
            if (!p.equals(type.getKey()) && java.util.regex.Pattern.compile("\\b" + simpleName(p) + "\\b").matcher(type.getValue()).find()) {
                out.append("\n\n").append(t);
            }
        });
        return out.toString();
    }

    private void addRequirement(InputEntry input, String line) throws IOException {
        String path = goals.purpose().obligationsPath(input);
        List<String> lines = new ArrayList<>();
        if (files.exists(path)) lines.addAll(SeedObligationsFile.read(files.readFile(path), input.content()).obligations());
        if (!lines.contains(line)) lines.add(line);
        files.writeFile(path, SeedObligationsFile.render(input.content(), lines));
    }

    /**
     * A held conflict decided. For the input: a difference's rule replaces the receiving one and becomes work; a claim
     * goes back to being a requirement; a held file takes the receiving file's place. For the receiving project: a
     * claim stays out for good, and nothing else changes.
     */
    public synchronized InputEvent decide(String id, String decision, String by) throws IOException {
        return decide(id, decision, by, null);
    }

    /**
     * T4-ED: a held conflict decided, possibly by neither side as it is: with {@code own}, the operator's
     * {@code resolution} is what holds. A difference: it replaces the receiving rule, as the input's would, and becomes
     * work. A file both have: merging the input's held copy into the receiving file becomes work. A claim: it stays
     * out, and the resolution is the requirement in its place.
     */
    public synchronized InputEvent decide(String id, String decision, String by, String resolution) throws IOException {
        if (!InputEvent.FOR_INPUT.equals(decision) && !InputEvent.FOR_RECEIVING.equals(decision)
                && !InputEvent.FOR_OWN.equals(decision)) {
            throw new IllegalArgumentException("a decision is input, receiving or own");
        }
        if (InputEvent.FOR_OWN.equals(decision) && (resolution == null || resolution.isBlank())) {
            throw new IllegalArgumentException("an own decision says what holds instead");
        }
        InputEvent open = goals.purpose().event(id);
        if (open == null || !open.open()) throw new IllegalArgumentException("no open conflict: " + id);
        boolean difference = open.heldLine() == null && open.heldPath() == null;
        if (InputEvent.FOR_OWN.equals(decision)) return decideOwn(open, difference, by, resolution.strip());
        InputComparison.Difference d = new InputComparison.Difference(open.subject(), open.receiving(), open.other());
        List<String> replaces = InputEvent.FOR_INPUT.equals(decision) && difference
                ? comparison.stating(goals.statedRules(), d) : List.of();
        InputEvent decided = goals.purpose().decide(open, decision, by, replaces);
        goals.conflictDecided();
        if (InputEvent.FOR_INPUT.equals(decision)) {
            if (open.heldPath() != null) {
                Path to = files.getRootDir().resolve("output").resolve(open.subject());
                Files.copy(files.getRootDir().resolve(open.heldPath()), to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else if (difference) {
                String line = open.subject() + ": " + open.other();
                InputEntry input = goals.purpose().ledger().entries().stream().filter(e -> e.id().equals(open.inputId())).findFirst().orElseThrow();
                addRequirement(input, line);
                goals.addDecidedWork(line, open.inputId());
            }
        }
        log.warn("T4-EC: conflict '{}' decided for {} by {}", open.subject(), decision, by);
        return decided;
    }

    private InputEvent decideOwn(InputEvent open, boolean difference, String by, String resolution) throws IOException {
        List<String> replaces = difference
                ? comparison.stating(goals.statedRules(), new InputComparison.Difference(open.subject(), open.receiving(), resolution))
                : List.of();
        InputEvent decided = goals.purpose().decide(open, InputEvent.FOR_OWN, by, replaces, resolution);
        goals.conflictDecided();
        String line = difference ? open.subject() + ": " + resolution
                : open.heldPath() != null ? "Merge " + open.subject() + " with the copy brought in (kept at " + open.heldPath()
                        + "): " + resolution
                : resolution;
        InputEntry input = goals.purpose().ledger().entries().stream().filter(e -> e.id().equals(open.inputId())).findFirst().orElseThrow();
        addRequirement(input, line);
        goals.addDecidedWork(line, open.inputId());
        log.warn("T4-EC: conflict '{}' decided by {} with their own resolution: {}", open.subject(), by, resolution);
        return decided;
    }

    /** T4-ED: the ways an open conflict can be resolved, each with what it changes -- advice for the operator. */
    public List<InputComparison.Option> options(String id) {
        InputEvent open = goals.purpose().event(id);
        if (open == null || !open.open()) throw new IllegalArgumentException("no open conflict: " + id);
        String intent = goals.purpose().ledger().entries().stream().filter(e -> e.id().equals(open.inputId()))
                .map(InputEntry::content).findFirst().orElse("");
        String kind = open.heldPath() != null ? "a file both projects have"
                : open.heldLine() != null ? "a requirement the brought project carried, which may not hold here"
                : "a rule both projects have, with different results";
        return comparison.options(goals.specification(), intent, kind, open.subject(), open.receiving(), open.other());
    }

    /**
     * T4-EC slice 8: an input taken back out, the work done since kept. Its files leave the tree, its module the build;
     * its obligations, corrections and open conflicts stop counting, its goals are subsumed; the ledger records the
     * withdrawal, so nothing of the history is lost. What referred to its files is the loop's work now: goal text names them.
     */
    public synchronized InputEvent withdraw(String inputId, String by) throws IOException {
        InputEntry input = goals.purpose().ledger().entries().stream().filter(e -> e.id().equals(inputId)).findFirst().orElse(null);
        if (input == null) throw new IllegalArgumentException("no such input: " + inputId);
        if (input.sequence() == 1) throw new IllegalArgumentException("the seed is what the project is; it cannot be withdrawn");
        if (goals.purpose().ledger().withdrawn(inputId)) throw new IllegalArgumentException("already withdrawn: " + inputId);
        String dir = "meta/inputs/" + inputId;
        List<String> placed = files.exists(dir + "/placed.txt")
                ? files.readFile(dir + "/placed.txt").lines().filter(l -> !l.isBlank()).toList() : List.of();
        Path output = files.getRootDir().resolve("output");
        String module = null;
        for (String p : placed) {
            Files.deleteIfExists(output.resolve(p));
            Path baseline = files.getRootDir().resolve(ProtectedPaths.BASELINE_DIR).resolve(p);
            Files.deleteIfExists(baseline);
            if (p.endsWith("/pom.xml")) module = p.substring(0, p.indexOf('/'));
        }
        if (module != null) {
            Path pom = output.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                Files.writeString(pom, Files.readString(pom).replaceAll("\\s*<module>" + java.util.regex.Pattern.quote(module) + "</module>", ""));
            }
        }
        files.writeFile(dir + ProtectedPaths.INPUT_FILE, "\n");
        manifest.removeFiles(placed);
        InputEvent event = goals.purpose().recordWithdrawal(inputId, by, goals.withdrawGoals(inputId));
        goals.conflictDecided();
        if (!placed.isEmpty()) {
            goals.addDecidedWork("The project builds and works without these files, which were withdrawn: " + String.join(", ", placed), null);
        }
        log.warn("T4-EC: input {} withdrawn by {} -- {} file(s) out", inputId, by, placed.size());
        return event;
    }

    /** The intent's lines, generated like a text input's, then what the input verified; the operator may edit them. */
    private int writeObligations(InputEntry input, List<String> verified, List<String> intentLines) throws IOException {
        String path = goals.purpose().obligationsPath(input);
        SeedObligationsGenerator g = generator.getIfAvailable();
        if (g != null) g.ensureFor(input.content(), path);
        List<String> lines = new ArrayList<>();
        if (files.exists(path)) lines.addAll(SeedObligationsFile.read(files.readFile(path), input.content()).obligations());
        if (lines.isEmpty()) lines.addAll(SeedRequirements.statedVerifiableIn(input.content()));
        for (String l : lines) {
            if (!SeedRequirements.isRule(l) && SeedRequirements.gateOf(l) == null) intentLines.add(l);
        }
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
            if (p.contains("src/") || p.endsWith("pom.xml")) tree.put(p, t);
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
