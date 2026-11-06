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

    /**
     * @param runs this input's module is what the project runs, in a project of modules. T4-EG: every import lands
     *             pending, scripted or not -- keeping it ({@link #keep}) is the one way into the project
     */
    public record Request(String label, String runId, Path dir, String intent, String addedBy, boolean runs) {
    }

    /**
     * T4-ED: an import as the home screen shows it -- what came in, what of it is still open, and whether it waits for
     * the operator to keep it. {@code label} is what was brought in, by name, when that was recorded (older imports
     * carry only their intent).
     */
    public record Import(String inputId, String label, String intent, Instant at, String addedBy, boolean pending,
                         int newFiles, long filesBothHave, long rulesThatDiffer, long claims) {
    }

    /** One carried criterion's verdict in the merged tree: met (null when unanswered) and who answered. */
    public record Verdict(String criterion, Boolean met, String source, List<ProjectMerge.Origin> origins) {
    }

    /** {@code met} (T4-ED): the carried criteria the merged code meets; {@code unmet} those it does not yet. */
    public record Report(String inputId, String goalId, List<String> placed, List<String> corrections, List<InputEvent> conflicts,
                         List<String> notes, int criteria, long byStructure, long byModel, long unanswered,
                         List<String> unmet, int obligations, long met) {
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
        // T4-ED: the same tree a second time adds nothing but its conflicts again (field: one project brought in three
        // times, 59 open conflicts, most of them repeats). A changed version of it is a new input.
        if (landing.treeDigest() != null) {
            goals.purpose().ledger().active().stream()
                    .filter(e -> InputEntry.KIND_PROJECT.equals(e.kind()) && landing.treeDigest().equals(e.sourceDigest()))
                    .findFirst().ifPresent(e -> {
                        throw new IllegalArgumentException(r.label() + " was already brought in, unchanged, at " + e.addedAt()
                                + (goals.purpose().ledger().pending(e.id()) ? "; cancel that import first to bring it in again" : ""));
                    });
        }

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
        goals.purpose().recordPendingImport(input.id(), r.label(), r.addedBy());
        String dir = "meta/inputs/" + input.id();
        files.ensureFolderExists(dir);
        files.writeFile(dir + "/placed.txt", String.join("\n", landing.placed()) + "\n");
        // T4-EI: a file the project already had and the landing rewrote (the build gaining the input's dependencies)
        // goes back as it was if the import is cancelled.
        for (String p : landing.files().keySet()) {
            if (before.containsKey(p)) keepOriginal(input.id(), p, before.get(p));
        }
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
        // What stays out of the work: the project's own, and this import's held claims -- it is pending, so the
        // project's list does not count its events yet.
        java.util.Set<String> out = new java.util.HashSet<>(goals.purpose().linesOut());
        goals.purpose().ledger().events().stream().filter(e -> e.inputId().equals(input.id()) && e.keepsLineOut())
                .forEach(e -> out.add(e.heldLine().strip()));
        if (landing.tree() != null) stamp(landing.tree(), verdicts);
        List<String> unmet = verdicts.stream().filter(v -> Boolean.FALSE.equals(v.met())).map(Verdict::criterion)
                .filter(c -> !out.contains(c.strip())).toList();
        // The goal closes on what the intent asks: without criteria nothing could ever close it (field119: the run sat on
        // it from 39/39 to its deadline), and field117 only closed because a stale line happened to land on its goal.
        List<String> criteria = new ArrayList<>(intentLines);
        unmet.stream().filter(u -> !criteria.contains(u)).forEach(criteria::add);
        // T4-EG: nothing lands in the goal tree while the import is pending; keep() lands it from what is kept here.
        ObjectNode kept = MAPPER.createObjectNode();
        kept.set("criteria", MAPPER.valueToTree(criteria));
        if (landing.tree() != null) kept.set("tree", landing.tree());
        files.writeFile(dir + LANDING_FILE, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(kept));
        String goalId = null;

        log.warn("T4-EC: {} brought in as {} -- {} file(s) placed, {} correction(s), {} conflict(s) held, {} criteria answered, {} unmet, {} obligation(s), to use: {}",
                r.label(), input.id(), landing.placed().size(), corrections.size(), goals.purpose().openConflicts().size(),
                verdicts.size(), unmet.size(), obligations, uses);
        List<InputEvent> conflicts = goals.purpose().openConflicts().stream().filter(e -> e.inputId().equals(input.id())).toList();
        return new Report(input.id(), goalId, landing.placed(), corrections, conflicts, landing.notes(), verdicts.size(),
                verdicts.stream().filter(v -> v.source().startsWith("structure")).count(),
                verdicts.stream().filter(v -> v.source().equals("model")).count(),
                verdicts.stream().filter(v -> v.met() == null).count(), unmet, obligations,
                verdicts.stream().filter(v -> Boolean.TRUE.equals(v.met())).count());
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
        // A conflicted file is named by its place here; in a project of modules that is under the input's module, while
        // the input keeps it at its own root (T4-ED field: "app/pom.xml" was looked for inside the input and the import failed).
        String module = landing.module() == null ? null : landing.module() + "/";
        for (String path : landing.conflictedFiles()) {
            String held = "meta/inputs/" + input.id() + "/held/" + path;
            Path to = files.getRootDir().resolve(held);
            Files.createDirectories(to.getParent());
            String own = module != null && path.startsWith(module) ? path.substring(module.length()) : path;
            Files.copy(source.resolve(own), to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
        if (InputEvent.FOR_INPUT.equals(decision)) {
            if (open.heldPath() != null) {
                Path to = files.getRootDir().resolve("output").resolve(open.subject());
                if (Files.isRegularFile(to)) keepOriginal(open.inputId(), open.subject(), Files.readString(to));
                Files.copy(files.getRootDir().resolve(open.heldPath()), to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } else if (difference) {
                String line = open.subject() + ": " + open.other();
                InputEntry input = goals.purpose().ledger().entries().stream().filter(e -> e.id().equals(open.inputId())).findFirst().orElseThrow();
                addRequirement(input, line);
                decidedWork(open.inputId(), line);
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
        String line = difference ? open.subject() + ": " + resolution
                : open.heldPath() != null ? "Merge " + open.subject() + " with the copy brought in (kept at " + open.heldPath()
                        + "): " + resolution
                : resolution;
        InputEntry input = goals.purpose().ledger().entries().stream().filter(e -> e.id().equals(open.inputId())).findFirst().orElseThrow();
        addRequirement(input, line);
        decidedWork(open.inputId(), line);
        log.warn("T4-EC: conflict '{}' decided by {} with their own resolution: {}", open.subject(), by, resolution);
        return decided;
    }

    /** T4-ED: the imports waiting for the operator, each with what came in and what of it is still open. */
    public List<Import> pending() {
        return imports().stream().filter(Import::pending).toList();
    }

    /** T4-ED: every project brought in and not taken back out -- kept or still pending -- oldest first. */
    public List<Import> imports() {
        List<InputEvent> open = goals.purpose().openConflicts();
        java.util.Map<String, InputEvent> pending = new java.util.HashMap<>();
        goals.purpose().pendingImports().forEach(e -> pending.put(e.inputId(), e));
        java.util.Map<String, String> labels = new java.util.HashMap<>();
        goals.purpose().ledger().events().stream().filter(e -> InputEvent.IMPORT.equals(e.kind()))
                .forEach(e -> labels.put(e.inputId(), e.subject()));
        List<Import> out = new ArrayList<>();
        for (InputEntry input : goals.purpose().ledger().active()) {
            if (!InputEntry.KIND_PROJECT.equals(input.kind())) continue;
            String placed = "meta/inputs/" + input.id() + "/placed.txt";
            int newFiles;
            try {
                newFiles = files.exists(placed) ? (int) files.readFile(placed).lines().filter(l -> !l.isBlank()).count() : 0;
            } catch (IOException e) {
                newFiles = 0;
            }
            List<InputEvent> mine = open.stream().filter(e -> e.inputId().equals(input.id())).toList();
            out.add(new Import(input.id(), labels.get(input.id()), input.content(), input.addedAt(), input.addedBy(),
                    pending.containsKey(input.id()), newFiles,
                    mine.stream().filter(e -> e.heldPath() != null).count(),
                    mine.stream().filter(e -> e.heldPath() == null && e.heldLine() == null).count(),
                    mine.stream().filter(e -> e.heldLine() != null).count()));
        }
        return out;
    }

    /**
     * T4-ED: a pending import kept -- T4-EG: from here it is the project. Its conflicts are decided first: the pending
     * stage is where they are settled, or the import is not continued (user, 2026-11-04).
     */
    public synchronized InputEvent keep(String inputId, String by) {
        InputEvent pending = goals.purpose().pendingImports().stream().filter(e -> e.inputId().equals(inputId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no import waits for confirmation: " + inputId));
        List<String> open = goals.purpose().openConflicts().stream().filter(e -> e.inputId().equals(inputId))
                .map(InputEvent::subject).toList();
        if (!open.isEmpty()) {
            throw new IllegalArgumentException("decide its conflicts before keeping it (" + open.size() + " open: "
                    + String.join("; ", open) + ")");
        }
        landKept(inputId);
        log.warn("T4-ED: import {} ({}) kept by {}", inputId, pending.subject(), by);
        return goals.purpose().keepImport(pending, by);
    }

    /** What keep() lands: the criteria of the intent goal and the import's stamped goal tree. */
    static final String LANDING_FILE = "/landing.json";
    /** Work a decision made while the import was pending, one line each, landed at keep. */
    static final String WORK_FILE = "/work.txt";

    /** A decision's work: kept beside the pending import and landed with it (T4-EG: nothing lands while pending). */
    private void decidedWork(String inputId, String line) throws IOException {
        String path = "meta/inputs/" + inputId + WORK_FILE;
        String before = files.exists(path) ? files.readFile(path) : "";
        files.writeFile(path, before + line.replace('\n', ' ') + "\n");
    }

    /**
     * T4-EG slice 4: the kept import enters the goal tree -- its intent as a goal under the root, its own goals unified
     * with the project's (GoalUnifier: one tree, no second root), the work its decisions made, and provenance links.
     */
    private void landKept(String inputId) {
        String dir = "meta/inputs/" + inputId;
        InputEntry input = goals.purpose().ledger().entries().stream().filter(e -> e.id().equals(inputId)).findFirst().orElseThrow();
        try {
            JsonNode kept = files.exists(dir + LANDING_FILE) ? MAPPER.readTree(files.readFile(dir + LANDING_FILE)) : MAPPER.createObjectNode();
            List<String> criteria = new ArrayList<>();
            kept.path("criteria").forEach(c -> criteria.add(c.asText()));
            String goalId = goals.landIntent(inputId, input.content(), criteria);
            JsonNode tree = kept.get("tree");
            String sourceRoot = tree == null ? null : tree.path("metadata").path("sourceGoalId").asText(null);
            goals.purpose().linkInput(inputId, goalId, sourceRoot);
            if (tree != null) new GoalUnifier(goals, comparison, goals.specification(), inputId).unify(tree, goalId);
            if (files.exists(dir + WORK_FILE)) {
                for (String line : files.readFile(dir + WORK_FILE).lines().filter(l -> !l.isBlank()).toList()) {
                    goals.addDecidedWork(line, inputId);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("the import could not be landed: " + e.getMessage(), e);
        }
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
     * A pending import cancelled: it leaves nothing. Its files leave the tree, its module the build; its goals are
     * subsumed; the ledger records the cancellation (a {@code withdrawal} event), so the history keeps it. Nothing was
     * built on it -- the loop holds while an import is pending.
     *
     * <p>T4-EG: only a PENDING import can be taken out. A kept one is the project, and what was built on it since cannot
     * be cut out of it (user, 2026-11-04: taking out was meant for the pending stage only); to go back to before an
     * import, the project is rewound.
     */
    public synchronized InputEvent cancel(String inputId, String by) throws IOException {
        InputEntry input = goals.purpose().ledger().entries().stream().filter(e -> e.id().equals(inputId)).findFirst().orElse(null);
        if (input == null) throw new IllegalArgumentException("no such input: " + inputId);
        if (goals.purpose().ledger().withdrawn(inputId)) throw new IllegalArgumentException("already cancelled: " + inputId);
        if (!goals.purpose().ledger().pending(inputId)) {
            throw new IllegalArgumentException("a kept input is part of the project and cannot be taken out;"
                    + " to go back to before it, rewind the project");
        }
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
        // T4-EI: what the project had before the import rewrote it -- the landing's build changes, a held file decided for
        // the input -- goes back as it was.
        int restored = 0;
        if (files.exists(dir + RESTORE_FILE)) {
            for (String p : files.readFile(dir + RESTORE_FILE).lines().filter(l -> !l.isBlank()).toList()) {
                Files.writeString(output.resolve(p), files.readFile(dir + RESTORE_DIR + p));
                restored++;
            }
        }
        // T4-EI: a source kept to be read while its capability is adapted leaves with the import.
        Path reference = files.getRootDir().resolve(dir + REFERENCE_DIR);
        if (Files.isDirectory(reference)) org.springframework.util.FileSystemUtils.deleteRecursively(reference);
        files.writeFile(dir + ProtectedPaths.INPUT_FILE, "\n");
        manifest.removeFiles(placed);
        InputEvent event = goals.purpose().recordWithdrawal(inputId, by, goals.withdrawGoals(inputId));
        log.warn("T4-EG: pending import {} cancelled by {} -- {} file(s) out, {} restored", inputId, by, placed.size(), restored);
        return event;
    }

    /** T4-EI: the project's own files an import rewrote, one path a line; their text before it under {@link #RESTORE_DIR}. */
    static final String RESTORE_FILE = "/restore.txt";
    static final String RESTORE_DIR = "/restore/";
    /** T4-EI: a capability import's source, kept read-only while a capability is adapted from it. */
    public static final String REFERENCE_DIR = "/reference";

    /** Keeps {@code path}'s text before the import first rewrote it; a later rewrite keeps the first. */
    private void keepOriginal(String inputId, String path, String text) throws IOException {
        String dir = "meta/inputs/" + inputId;
        String listed = files.exists(dir + RESTORE_FILE) ? files.readFile(dir + RESTORE_FILE) : "";
        if (listed.lines().anyMatch(path::equals)) return;
        files.writeFile(dir + RESTORE_DIR + path, text);
        files.writeFile(dir + RESTORE_FILE, listed + path + "\n");
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
