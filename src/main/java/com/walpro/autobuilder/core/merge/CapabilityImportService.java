package com.walpro.autobuilder.core.merge;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.walpro.autobuilder.core.merge.CapabilityCut.Capability;
import com.walpro.autobuilder.core.merge.CapabilityCut.Choice;
import com.walpro.autobuilder.core.merge.CapabilityCut.Mode;
import com.walpro.autobuilder.core.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * T4-EI: a capability import -- the stage in front of today's import, which it leaves unchanged. The source's
 * capabilities are discovered and listed; the operator chooses per capability whether it comes in as code, adapted,
 * as an idea, or not at all, says what it is for here, and whether an adapted one keeps its source readable; the
 * choices make a NARROWED input -- only the code of capabilities taken as code, as a bare tree, so none of the source's
 * goals or obligations ride along -- and an intent written from the descriptions. That input goes through
 * {@link ProjectInputService#bring} as any other: conflicts, keep and cancel are the import's own.
 *
 * <p>Nothing here touches the project until the import is made: a selection lives under
 * {@code meta/capability-imports/<id>/}; once it lands, what came from which capability is recorded beside the
 * import ({@code meta/inputs/<input>/capabilities.json}) with the source kept as a reference when one was chosen.
 */
@Slf4j
@Service
public class CapabilityImportService {

    static final String DIR = "meta/capability-imports/";
    public static final String RECORD = "/capabilities.json";
    private static final int MAX_GOAL_LINES = 30;
    private static final int MAX_GOAL_CHARS = 200;
    private static final Set<String> NOT_COPIED = Set.of("target", ".git", "node_modules", ".idea");

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final FileService files;
    private final CapabilityDiscovery discovery;

    public CapabilityImportService(FileService files, CapabilityDiscovery discovery) {
        this.files = files;
        this.discovery = discovery;
    }

    /** What the choices would bring in, and what keeps them from being met; {@code intent} is the import's intent. */
    public record Preview(List<String> files, Map<String, List<String>> provenance, List<String> cutOut, List<String> shared,
                          List<String> unattributed, List<String> dependencies, List<String> problems, String intent) {
    }

    /**
     * One capability import from discovery to landing. {@code state}: {@code choosing} until it is imported
     * ({@code imported}, with its {@code inputId}) or cancelled ({@code cancelled}). {@code cut}: the capabilities as
     * the model's cut for the current choices attributes them (null while nothing is taken as code), with the probes
     * it made; the preview and the import both use it, so what was previewed is what lands.
     */
    public record Selection(String id, String label, String runId, String dir, String addedBy, Instant at,
                            List<Capability> capabilities, List<String> unknown, List<String> probes,
                            List<Choice> choices, Preview preview, String state, String inputId,
                            List<Capability> cut, List<String> cutProbes) {

        Selection with(List<Choice> c, Preview p, List<Capability> k, List<String> kp) {
            return new Selection(id, label, runId, dir, addedBy, at, capabilities, unknown, probes, c, p, state, inputId, k, kp);
        }

        Selection in(String s, String input) {
            return new Selection(id, label, runId, dir, addedBy, at, capabilities, unknown, probes, choices, preview, s, input, cut, cutProbes);
        }

        /** What the cut works from: the model's cut when there is one, else discovery's own attribution. */
        List<Capability> attributed() {
            return cut != null ? cut : capabilities;
        }
    }

    /** Finds what {@code dir} can do; the selection waits for the operator's choices. Slow: a model probes the source. */
    public Selection discover(String label, String runId, Path dir, String addedBy) throws IOException {
        if (label == null || label.isBlank() || dir == null) throw new IllegalArgumentException("a capability import needs a label and a dir");
        Path code = codeOf(dir);
        Map<String, String> source = ProjectMerge.files(code);
        if (CapabilityCut.inventory(source).isEmpty()) {
            throw new IllegalArgumentException(label + " declares nothing under src/main/java to take capabilities from");
        }
        CapabilityDiscovery.Found found = discovery.discover(code, source, goalsOf(dir));
        if (found.capabilities().isEmpty()) {
            throw new IllegalStateException("no capabilities were found in " + label
                    + (found.answer() == null ? ": the model gave no answer" : ": the answer named none"));
        }
        Selection s = new Selection("cap-" + UUID.randomUUID().toString().substring(0, 8), label, runId,
                dir.toAbsolutePath().normalize().toString(), addedBy == null ? "operator" : addedBy, Instant.now(),
                found.capabilities(), found.unknown(), found.probes(), List.of(), null, "choosing", null, null, null);
        save(s);
        log.warn("T4-EI: {} -- {} capabilities found in {} ({} unknown declaration(s) dropped)", s.id(), s.capabilities().size(),
                label, s.unknown().size());
        return s;
    }

    public Selection get(String id) throws IOException {
        if (id == null || !id.matches("cap-[0-9a-f]{8}")) throw new IllegalArgumentException("no such capability import: " + id);
        String path = DIR + id + "/selection.json";
        if (!files.exists(path)) throw new IllegalArgumentException("no such capability import: " + id);
        return MAPPER.readValue(files.readFile(path), Selection.class);
    }

    /** The selections still waiting for choices or an import. */
    public List<Selection> open() throws IOException {
        Path root = files.getRootDir().resolve(DIR);
        if (!Files.isDirectory(root)) return List.of();
        List<Selection> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.sorted().toList()) {
                Selection s = get(d.getFileName().toString());
                if ("choosing".equals(s.state())) out.add(s);
            }
        }
        return out;
    }

    /** The operator's choices, checked; the preview says what they would bring in and what keeps them from being met. */
    public Selection choose(String id, List<Choice> choices) throws IOException {
        Selection s = choosing(id);
        Map<String, Choice> byId = new LinkedHashMap<>();
        for (Choice c : choices == null ? List.<Choice>of() : choices) {
            if (s.capabilities().stream().noneMatch(k -> k.id().equals(c.capability()))) {
                throw new IllegalArgumentException("no such capability: " + c.capability());
            }
            Mode mode = c.mode() == null ? Mode.LEAVE_OUT : c.mode();
            if (mode != Mode.LEAVE_OUT && (c.description() == null || c.description().isBlank())) {
                throw new IllegalArgumentException("say what " + nameOf(s, c.capability()) + " is for here");
            }
            boolean reference = c.reference() && (mode == Mode.ADAPT || mode == Mode.IDEA);
            byId.put(c.capability(), new Choice(c.capability(), mode, c.description() == null ? "" : c.description().strip(), reference));
        }
        List<Choice> made = List.copyOf(byId.values());
        // The cut is the model's, made by probing the source for these choices; structure then checks it (the preview's
        // problems). Without an answer nothing is guessed: the import is refused until the choices are checked again.
        List<Capability> cut = null;
        List<String> cutProbes = null;
        List<String> refused = new ArrayList<>();
        List<Capability> taken = s.capabilities().stream().filter(c -> byId.containsKey(c.id()) && byId.get(c.id()).mode() == Mode.CODE).toList();
        if (!taken.isEmpty()) {
            List<Capability> others = s.capabilities().stream().filter(c -> !taken.contains(c)).toList();
            Path code = codeOf(Path.of(s.dir()));
            CapabilityDiscovery.Cut answer = discovery.cut(code, ProjectMerge.files(code), taken, others);
            cutProbes = answer.probes();
            if (answer.answered()) cut = refined(s.capabilities(), made, answer);
            else refused.add("the cut could not be worked out: the model gave no answer -- check the choices again");
        }
        Selection draft = s.with(made, null, cut, cutProbes);
        Preview p = preview(draft, made);
        if (!refused.isEmpty()) {
            List<String> problems = new ArrayList<>(refused);
            problems.addAll(p.problems());
            p = new Preview(p.files(), p.provenance(), p.cutOut(), p.shared(), p.unattributed(), p.dependencies(), problems, p.intent());
        }
        Selection chosen = draft.with(made, p, cut, cutProbes);
        save(chosen);
        return chosen;
    }

    /**
     * The narrowed input for {@link ProjectInputService#bring}: the cut written as a bare tree under the selection, and
     * the intent. Refused while the choices cannot be met or take nothing in.
     */
    public ProjectInputService.Request request(String id) throws IOException {
        Selection s = choosing(id);
        if (s.preview() == null || s.choices().stream().allMatch(c -> c.mode() == Mode.LEAVE_OUT)) {
            throw new IllegalArgumentException("choose at least one capability to take in, and say what it is for here");
        }
        Preview p = s.preview();
        if (!p.problems().isEmpty()) {
            throw new IllegalArgumentException("the choices cannot be met as made: " + String.join("; ", p.problems()));
        }
        Path cut = files.getRootDir().resolve(DIR + id + "/cut");
        if (Files.isDirectory(cut)) FileSystemUtils.deleteRecursively(cut);
        Files.createDirectories(cut);
        for (Map.Entry<String, String> e : cutOf(s, s.choices()).files().entrySet()) {
            Path to = cut.resolve(e.getKey());
            Files.createDirectories(to.getParent());
            Files.writeString(to, e.getValue());
        }
        return new ProjectInputService.Request(s.label(), s.runId(), cut, p.intent(), s.addedBy(), false);
    }

    /**
     * The import made: what came from which capability is recorded beside it, and the source is kept read-only under it
     * when an adapted or idea capability was chosen with its source as a reference.
     */
    public Selection landed(String id, String inputId) throws IOException {
        Selection s = choosing(id);
        String dir = "meta/inputs/" + inputId;
        boolean reference = s.choices().stream().anyMatch(Choice::reference);
        if (reference) copy(codeOf(Path.of(s.dir())), files.getRootDir().resolve(dir + ProjectInputService.REFERENCE_DIR));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("selection", s.id());
        record.put("source", s.label());
        record.put("sourceDir", s.dir());
        record.put("sourceRun", s.runId());
        record.put("reference", reference ? dir + ProjectInputService.REFERENCE_DIR : null);
        List<Map<String, Object>> rows = new ArrayList<>();
        CapabilityCut.Result cut = cutOf(s, s.choices());
        for (Capability c : s.capabilities()) {
            Choice choice = choiceOf(s, c.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.id());
            row.put("name", c.name());
            row.put("summary", c.summary());
            row.put("mode", choice.mode().name().toLowerCase(java.util.Locale.ROOT));
            row.put("description", choice.description());
            row.put("reference", choice.reference());
            row.put("declarations", c.declarations());
            // What the cut attributed to it, when a cut was made: for a capability taken as code, what travelled.
            s.attributed().stream().filter(k -> k.id().equals(c.id())).findFirst()
                    .ifPresent(k -> row.put("cutDeclarations", k.declarations()));
            row.put("dependencies", c.dependencies());
            row.put("placed", cut.provenance().entrySet().stream().filter(e -> e.getValue().contains(c.name())).map(Map.Entry::getKey).toList());
            rows.add(row);
        }
        record.put("capabilities", rows);
        record.put("cutProbes", s.cutProbes());
        record.put("cutOut", cut.cutOut());
        record.put("shared", cut.shared());
        record.put("unattributed", cut.unattributed());
        files.writeFile(dir + RECORD, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(record));
        Selection done = s.in("imported", inputId);
        save(done);
        log.warn("T4-EI: {} imported as {} -- {} file(s) placed{}", id, inputId, cut.files().size(),
                reference ? ", source kept as a reference" : "");
        return done;
    }

    /** A selection not imported is dropped; the project was never touched. After the import, cancel the import itself. */
    public Selection cancel(String id) throws IOException {
        Selection s = choosing(id);
        Path cut = files.getRootDir().resolve(DIR + id + "/cut");
        if (Files.isDirectory(cut)) FileSystemUtils.deleteRecursively(cut);
        Selection cancelled = s.in("cancelled", null);
        save(cancelled);
        log.warn("T4-EI: {} cancelled before its import", id);
        return cancelled;
    }

    /** What a capability import of the project recorded, by input id; empty for an input that was not one. */
    public JsonNode record(String inputId) throws IOException {
        String path = "meta/inputs/" + inputId + RECORD;
        return files.exists(path) ? MAPPER.readTree(files.readFile(path)) : MAPPER.createObjectNode();
    }

    private Selection choosing(String id) throws IOException {
        Selection s = get(id);
        if (!"choosing".equals(s.state())) throw new IllegalArgumentException(id + " is " + s.state() + " already");
        return s;
    }

    private Preview preview(Selection s, List<Choice> choices) throws IOException {
        CapabilityCut.Result cut = cutOf(s, choices);
        Set<String> dependencies = new LinkedHashSet<>();
        for (Capability c : s.capabilities()) {
            if (choiceOf(s, choices, c.id()).mode() == Mode.CODE) dependencies.addAll(c.dependencies());
        }
        return new Preview(List.copyOf(cut.files().keySet()), cut.provenance(), cut.cutOut(), cut.shared(), cut.unattributed(),
                List.copyOf(dependencies), cut.problems(), intent(s, choices, List.copyOf(dependencies)));
    }

    private CapabilityCut.Result cutOf(Selection s, List<Choice> choices) throws IOException {
        return CapabilityCut.cut(ProjectMerge.files(codeOf(Path.of(s.dir()))), s.attributed(), choices);
    }

    /**
     * The capabilities as the model's cut attributes them: a capability taken as code lists what the cut takes of it
     * (what no taken capability listed goes to the first), a capability not taken keeps what it listed -- so what
     * travels although it lists it is shown as shared -- but for a type the cut takes only part of, plus the members
     * the cut leaves out for it; a member left out for a capability nobody named is
     * attributed to "not taken". Names and ids stay discovery's, so provenance reads the same.
     */
    static List<Capability> refined(List<Capability> capabilities, List<Choice> choices, CapabilityDiscovery.Cut cut) {
        Set<String> code = new LinkedHashSet<>();
        choices.stream().filter(c -> c.mode() == Mode.CODE).forEach(c -> code.add(c.capability()));
        // A type the cut takes only part of: a capability not taken that lists it whole must not take it out whole.
        Set<String> partlyTaken = new LinkedHashSet<>();
        for (String id : cut.take()) if (id.contains(".")) partlyTaken.add(id.substring(0, id.lastIndexOf('.')));
        partlyTaken.removeAll(cut.take());
        Map<String, List<String>> lists = new LinkedHashMap<>();
        Set<String> claimed = new LinkedHashSet<>();
        for (Capability c : capabilities) {
            List<String> mine = new ArrayList<>();
            if (code.contains(c.id())) {
                for (String id : cut.take()) {
                    String type = id.contains(".") ? id.substring(0, id.lastIndexOf('.')) : id;
                    if (c.declarations().contains(id) || c.declarations().contains(type)) {
                        mine.add(id);
                        claimed.add(id);
                    }
                }
            } else {
                // What it listed stays listed -- what travels anyway is then shown as shared, not silently dropped.
                for (String id : c.declarations()) {
                    if (!partlyTaken.contains(id)) mine.add(id);
                }
                String name = c.name().toLowerCase(java.util.Locale.ROOT);
                cut.leave().forEach((id, owner) -> {
                    String o = owner.toLowerCase(java.util.Locale.ROOT);
                    if (!o.isBlank() && (o.contains(name) || name.contains(o))) mine.add(id);
                });
            }
            lists.put(c.id(), mine);
        }
        String first = capabilities.stream().map(Capability::id).filter(code::contains).findFirst().orElse(null);
        for (String id : cut.take()) if (first != null && !claimed.contains(id)) lists.get(first).add(id);
        List<Capability> out = new ArrayList<>();
        for (Capability c : capabilities) {
            out.add(new Capability(c.id(), c.name(), c.summary(), List.copyOf(lists.get(c.id())), c.dependencies(), c.reference(), c.referenceWhy()));
        }
        Set<String> placedOut = new LinkedHashSet<>();
        out.stream().filter(c -> !code.contains(c.id())).forEach(c -> placedOut.addAll(c.declarations()));
        List<String> orphans = cut.leave().keySet().stream().filter(id -> !placedOut.contains(id)).toList();
        if (!orphans.isEmpty()) out.add(new Capability("c-not-taken", "not taken", "members the cut left out", orphans, List.of(), false, null));
        return out;
    }

    /** The import's intent, one line per chosen capability: how it comes in and what it is for here, in the operator's words. */
    static String intent(Selection s, List<Choice> choices, List<String> dependencies) {
        StringBuilder sb = new StringBuilder("Capabilities taken from " + s.label() + ", as chosen:\n");
        List<String> left = new ArrayList<>();
        boolean reference = false;
        for (Capability c : s.capabilities()) {
            Choice choice = choiceOf(s, choices, c.id());
            switch (choice.mode()) {
                case CODE -> sb.append("- ").append(c.name()).append(", taken as code (its code is placed in this project): ")
                        .append(choice.description()).append('\n');
                case ADAPT -> {
                    sb.append("- ").append(c.name()).append(", adapted -- re-implemented in this project's own structure, none of ")
                            .append(s.label()).append("'s code for it placed")
                            .append(choice.reference() ? "; " + s.label() + "'s source is kept to read while doing it" : "")
                            .append(": ").append(choice.description()).append('\n');
                    reference |= choice.reference();
                }
                case IDEA -> {
                    sb.append("- ").append(c.name()).append(", as an idea only -- its approach applied here in this project's own way")
                            .append(choice.reference() ? ", " + s.label() + "'s source kept to read" : "")
                            .append(": ").append(choice.description()).append('\n');
                    reference |= choice.reference();
                }
                case LEAVE_OUT -> left.add(c.name());
            }
        }
        if (!left.isEmpty()) sb.append("Left out, not taken on: ").append(String.join(", ", left)).append(".\n");
        if (!dependencies.isEmpty()) {
            sb.append("The code taken as code relies on: ").append(String.join(", ", dependencies)).append(".\n");
        }
        return sb.toString().strip();
    }

    private static Choice choiceOf(Selection s, String capability) {
        return choiceOf(s, s.choices(), capability);
    }

    private static Choice choiceOf(Selection s, List<Choice> choices, String capability) {
        return choices.stream().filter(c -> c.capability().equals(capability)).findFirst()
                .orElse(new Choice(capability, Mode.LEAVE_OUT, "", false));
    }

    private static String nameOf(Selection s, String capability) {
        return s.capabilities().stream().filter(c -> c.id().equals(capability)).map(Capability::name).findFirst().orElse(capability);
    }

    private void save(Selection s) throws IOException {
        files.writeFile(DIR + s.id() + "/selection.json", MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(s));
    }

    /** A project's code: its {@code output/}, or the directory itself for a codebase from elsewhere. */
    static Path codeOf(Path dir) {
        return Files.isDirectory(dir.resolve("output")) ? dir.resolve("output") : dir;
    }

    /** What the source was built toward, one goal a line, when it has a goal tree. */
    static String goalsOf(Path dir) {
        Path stack = dir.resolve("meta/GoalStack.json");
        if (!Files.isRegularFile(stack)) return null;
        try {
            List<String> lines = new ArrayList<>();
            walk(MAPPER.readTree(stack.toFile()).path("goalTree"), lines);
            return lines.isEmpty() ? null : String.join("\n", lines);
        } catch (IOException e) {
            return null;
        }
    }

    private static void walk(JsonNode goal, List<String> lines) {
        if (goal.isMissingNode() || lines.size() >= MAX_GOAL_LINES) return;
        String text = goal.path("description").asText("").strip().replaceAll("\\s+", " ");
        if (!text.isEmpty()) {
            lines.add("- " + (text.length() > MAX_GOAL_CHARS ? text.substring(0, MAX_GOAL_CHARS) + "..." : text)
                    + " [" + goal.path("status").asText("?") + "]");
        }
        for (JsonNode sub : goal.path("subGoals")) walk(sub, lines);
    }

    private static void copy(Path from, Path to) throws IOException {
        if (Files.isDirectory(to)) FileSystemUtils.deleteRecursively(to);
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path rel = from.relativize(p);
                boolean skipped = false;
                for (Path part : rel) skipped |= NOT_COPIED.contains(part.toString());
                if (skipped) continue;
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }
}
