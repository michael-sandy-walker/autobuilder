package com.walpro.autobuilder.goal.purpose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
import com.walpro.autobuilder.core.checkpoint.CheckpointableState;
import com.walpro.autobuilder.core.checkpoint.StateBucket;
import com.walpro.autobuilder.core.checkpoint.StateHolder;
import com.walpro.autobuilder.core.init.RunIdentityService;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.SeedObligationsFile;
import com.walpro.autobuilder.goal.SeedRequirements;
import com.walpro.autobuilder.graph.input.InputGraphService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T4-EC slice 1: what this project is for, as the record of the inputs it was given.
 *
 * <p>Every reader of the purpose asks here instead of reading the root goal's text. The seed is entry 1 of the
 * {@link InputLedger}; the ledger is {@value #PATH} beside {@code meta/GoalStack.json}, so it travels with the project
 * and can be read by hand, and every entry and event is recorded in the graph as history.
 *
 * <p>T4-EG: what the inputs left in the project's files is checkpointed state: a rewind returns it to the point, and a
 * later point brings it back ({@link #FILES}).
 *
 * <p>Wherever a root goal is set, the seed is recorded ({@link #recordSeed}); a project whose root predates the
 * ledger gets its entry from the root's text when it boots ({@link #ensureFor}).
 */
@Slf4j
@Service
@StateHolder(value = StateBucket.CAPTURE,
        note = "the project's inputs as their files hold them: the ledger, what each input placed, protected and held, and the adoption record")
public class PurposeService implements CheckpointableState {

    public static final String PATH = "meta/inputs.json";

    /**
     * T4-EG: the project's specification as one text, written whenever what the project consists of changes and read
     * as it is by every reader of the purpose. Absent while the project is only its seed, which is then the
     * specification itself.
     */
    public static final String SPEC_FILE = "meta/specification.txt";
    static final String SEED_FILE = "bootstrap/seed.txt";

    /**
     * T4-EG: every file an input writes outside {@code output/}, file or directory. The tree itself is the checkpoint's
     * snapshot and the goals its goal history; this is the rest of what bringing a project in changes.
     */
    static final List<String> FILES = List.of(PATH, SPEC_FILE, "meta/inputs",
            com.walpro.autobuilder.core.build.AdoptionManifest.PATH,
            com.walpro.autobuilder.core.build.AdoptionManifest.SERVED_PATH,
            com.walpro.autobuilder.core.build.ProtectedPaths.BASELINE_DIR);

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final FileService fileService;
    private final InputGraphService graph;
    private final RunIdentityService runIdentity;
    private final boolean loadFromGraph;

    private volatile InputLedger ledger = InputLedger.EMPTY;

    @Autowired
    public PurposeService(FileService fileService, ObjectProvider<InputGraphService> graph,
                          ObjectProvider<RunIdentityService> runIdentity, GoalPlannerConfigProperties config) {
        this(fileService, graph.getIfAvailable(), runIdentity.getIfAvailable(), config != null && config.isSyncFromGraph());
    }

    private PurposeService(FileService fileService, InputGraphService graph, RunIdentityService runIdentity,
                           boolean loadFromGraph) {
        this.fileService = fileService;
        this.graph = graph;
        this.runIdentity = runIdentity;
        this.loadFromGraph = loadFromGraph;
    }

    /** A purpose kept in the project's files only -- for a goal planner built outside a Spring context. */
    public static PurposeService fileOnly(FileService fileService) {
        PurposeService s = new PurposeService(fileService, null, null, false);
        s.load();
        return s;
    }

    /**
     * T4-EG: the file is the project's state and the graph its history. A rewind puts the file back as it was and
     * leaves the graph's record of what came in since, so reading the graph first would bring a rewound-out import
     * back at the next boot. The graph answers only for a project whose file is gone, and the file is written from it.
     */
    @PostConstruct
    public void load() {
        ledger = readFile();
        if (!ledger.isEmpty() || !loadFromGraph || graph == null) return;
        try {
            ledger = new InputLedger(graph.findAll(), graph.findEvents());
        } catch (Exception e) {
            log.warn("T4-EC: {} is missing and the graph cannot be read ({}) -- no inputs", PATH, e.getMessage());
            return;
        }
        if (!ledger.isEmpty()) writeFile();
    }

    public InputLedger ledger() {
        return ledger;
    }

    @Override
    public String stateKey() {
        return "purpose";
    }

    /** Each of {@link #FILES} that exists, by path relative to the project, with its text. */
    @Override
    public synchronized Object captureState() {
        Map<String, String> out = new java.util.TreeMap<>();
        for (String path : FILES) {
            try {
                java.nio.file.Path p = fileService.resolveSecurePath(path);
                if (java.nio.file.Files.isRegularFile(p)) {
                    out.put(path, java.nio.file.Files.readString(p));
                } else if (java.nio.file.Files.isDirectory(p)) {
                    try (var walk = java.nio.file.Files.walk(p)) {
                        for (java.nio.file.Path f : walk.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
                            out.put(path + "/" + p.relativize(f).toString().replace('\\', '/'), java.nio.file.Files.readString(f));
                        }
                    }
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("T4-EG: " + path + " could not be captured: " + e.getMessage(), e);
            }
        }
        return out;
    }

    /**
     * The files as captured, and nothing else of {@link #FILES}; the ledger read back from them. A point that captured
     * nothing -- one written before the inputs were part of a point -- leaves them as they are: its start-of-run value
     * is what is on disk, and deleting it would take a project's inputs away on its first Continue.
     */
    @Override
    public synchronized void restoreState(JsonNode state, ObjectMapper mapper) {
        if (state == null || state.isNull()) {
            ledger = readFile();
            return;
        }
        try {
            for (String path : FILES) fileService.deleteRecursively(path);
            for (var it = state.fields(); it.hasNext(); ) {
                var e = it.next();
                fileService.writeFile(e.getKey(), e.getValue().asText());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("T4-EG: the inputs' files could not be restored: " + e.getMessage(), e);
        }
        ledger = readFile();
    }

    /**
     * The project's specification -- {@value #SPEC_FILE} as written, or the seed while the project is nothing more; null
     * while it has none. A project kept before the file existed gets it written on first read.
     */
    public String specification() {
        try {
            if (fileService.exists(SPEC_FILE)) return fileService.readString(SPEC_FILE);
        } catch (Exception e) {
            log.warn("T4-EG: {} unreadable ({}) -- the specification is joined from the ledger", SPEC_FILE, e.getMessage());
            return ledger.specification();
        }
        if (ledger.kept().size() > 1) writeSpecification();
        return ledger.specification();
    }

    /** {@value #SPEC_FILE} as the ledger now says the project is: removed while it is only the seed. */
    private void writeSpecification() {
        try {
            if (ledger.kept().size() <= 1) {
                fileService.deleteRecursively(SPEC_FILE);
            } else {
                fileService.ensureFolderExists("meta");
                fileService.writeFile(SPEC_FILE, ledger.specification());
            }
        } catch (Exception e) {
            log.warn("T4-EG: {} not written: {}", SPEC_FILE, e.getMessage());
        }
    }

    /**
     * The digest of the seed's source text: the key the stuck layer's cross-run priors were stored under before the
     * ledger existed (the raw {@code bootstrap/seed.txt}), kept so those priors stay reachable. Null with no seed.
     */
    public String seedKey() {
        InputEntry seed = ledger.seed();
        return seed == null ? null : seed.sourceDigest();
    }

    /** Where {@code input}'s obligations are kept: the seed's stay the target's tracked file, a later input's beside it in meta. */
    public String obligationsPath(InputEntry input) {
        return input.sequence() == 1 ? SeedObligationsFile.PATH : "meta/inputs/" + input.id() + "/obligations.txt";
    }

    /** {@code input}'s obligations file, or null without one. Read per call: an operator's edit takes effect mid-run. */
    private String obligationsText(InputEntry input) {
        try {
            return fileService.readFile(obligationsPath(input));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * What the project must do, as one list: every kept input's lines read alike (T4-EG -- a line does not say which
     * input it came from, so nothing can treat an imported one differently). Each input's lines stay in its own file
     * because each file is checked against its own text's hash, and the seed's is a tracked file.
     */
    private List<String> across(java.util.function.BiFunction<String, String, List<String>> read) {
        java.util.Set<String> out_ = linesOut();
        List<String> out = new ArrayList<>();
        for (InputEntry e : ledger.kept()) {
            for (String line : read.apply(e.content(), obligationsText(e))) {
                if (!out_.contains(line.strip())) out.add(line);
            }
        }
        return out;
    }

    /**
     * Lines that are not requirements now: a claim held or set aside, and what a decided rule replaced -- by a kept
     * input; a pending one changes nothing until it is kept.
     */
    public java.util.Set<String> linesOut() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (InputEvent e : ledger.events()) {
            // T4-EG: only a pending import can be cancelled, and a pending import never counted -- a cancellation
            // takes nothing out of the project's own lines (they may well read the same as the import's).
            if (ledger.isKept(e.inputId())) {
                if (e.keepsLineOut()) out.add(e.heldLine().strip());
                if (e.replacedReceiving()) e.replaces().forEach(l -> out.add(l.strip()));
            }
        }
        return out;
    }

    public List<InputEvent> openConflicts() {
        return ledger.events().stream().filter(e -> InputEvent.CONFLICT.equals(e.kind()) && e.open())
                .filter(e -> !ledger.withdrawn(e.inputId())).toList();
    }

    public InputEvent event(String id) {
        return ledger.events().stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    /** A difference the operator's intent settled for the input. */
    public synchronized InputEvent recordCorrection(String inputId, String subject, String receiving, String other,
                                                    List<String> replaces) {
        return store(new InputEvent("event-" + UUID.randomUUID(), InputEvent.CORRECTION, inputId, subject, receiving, other,
                null, null, replaces, InputEvent.DECIDED, InputEvent.FOR_INPUT, "intent", Instant.now()));
    }

    /** Something nothing settled: held until someone decides. */
    public synchronized InputEvent raiseConflict(String inputId, String subject, String receiving, String other,
                                                 String heldLine, String heldPath) {
        return store(new InputEvent("event-" + UUID.randomUUID(), InputEvent.CONFLICT, inputId, subject, receiving, other,
                heldLine, heldPath, List.of(), InputEvent.OPEN, null, null, Instant.now()));
    }

    /** T4-ED: an import landed but not yet confirmed by the operator; {@code label} is what was brought in. */
    public synchronized InputEvent recordPendingImport(String inputId, String label, String by) {
        return store(new InputEvent("event-" + UUID.randomUUID(), InputEvent.IMPORT, inputId, label, null, null,
                null, null, List.of(), InputEvent.OPEN, null, by, Instant.now()));
    }

    /** T4-ED: the imports that wait for confirmation: neither kept nor taken back out. */
    public List<InputEvent> pendingImports() {
        return ledger.events().stream().filter(e -> InputEvent.IMPORT.equals(e.kind()) && e.open())
                .filter(e -> !ledger.withdrawn(e.inputId())).toList();
    }

    /** T4-ED: a pending import confirmed: it stays, and nothing more waits for it. */
    public synchronized InputEvent keepImport(InputEvent pending, String by) {
        return decide(pending, InputEvent.KEPT, by, List.of());
    }

    /** A pending import cancelled: it stays in the record and asks nothing; {@code carried} are its goals' criteria. */
    public synchronized InputEvent recordWithdrawal(String inputId, String by, List<String> carried) {
        return store(new InputEvent("event-" + UUID.randomUUID(), InputEvent.WITHDRAWAL, inputId, "withdrawn", null, null,
                null, null, carried, InputEvent.DECIDED, null, by, Instant.now()));
    }

    /** A conflict decided; the event stays in the ledger with who decided and how. */
    public synchronized InputEvent decide(InputEvent open, String decision, String by, List<String> replaces) {
        return decide(open, decision, by, replaces, null);
    }

    /** A conflict decided, with the operator's own resolution when they chose neither side as it is. */
    public synchronized InputEvent decide(InputEvent open, String decision, String by, List<String> replaces,
                                          String resolution) {
        return store(new InputEvent(open.id(), open.kind(), open.inputId(), open.subject(), open.receiving(), open.other(),
                open.heldLine(), open.heldPath(), replaces, InputEvent.DECIDED, decision, by, Instant.now(), resolution));
    }

    private InputEvent store(InputEvent event) {
        ledger = ledger.with(event);
        if (graph != null) {
            try {
                graph.saveEvent(event, runIdentity == null ? null : runIdentity.lineageId());
            } catch (Exception e) {
                log.warn("T4-EC: event {} was not written to the graph: {}", event.id(), e.getMessage());
            }
        }
        writeFile();
        return event;
    }

    public List<String> answeredByGate(String gate) {
        return across((spec, written) -> SeedRequirements.answeredByGate(spec, written, gate));
    }

    public List<String> rules() {
        return across(SeedRequirements::rules);
    }

    public List<String> obligationsOrClauses() {
        return across(SeedRequirements::obligationsOrClauses);
    }

    public List<String> verifiableObligationsOrSplit() {
        return across(SeedRequirements::verifiableObligationsOrSplit);
    }

    public List<String> notAlreadyCoveredVerifiable(List<String> banked) {
        return across((spec, written) -> SeedRequirements.notAlreadyCoveredVerifiable(spec, banked, written));
    }

    /** The project's written obligation lines as written, markers kept. */
    public List<String> writtenLines() {
        java.util.Set<String> out_ = linesOut();
        List<String> out = new ArrayList<>();
        for (InputEntry e : ledger.kept()) {
            String written = obligationsText(e);
            if (written != null) written.lines().filter(l -> !l.isBlank() && !l.startsWith("#") && !out_.contains(l.strip())).forEach(out::add);
        }
        return out;
    }

    /** The project's obligations files as they stand, for a digest of what the project was asked. */
    public String writtenText() {
        StringBuilder out = new StringBuilder();
        for (InputEntry e : ledger.kept()) {
            String written = obligationsText(e);
            if (written != null) out.append(written);
        }
        return out.toString();
    }

    /** A root goal was set: its text is the project's seed, and a new root starts a new ledger. */
    public synchronized void recordSeed(String content, String addedBy) {
        InputEntry seed = new InputEntry("input-" + UUID.randomUUID(), 1, InputEntry.KIND_SEED, content,
                sha256(sourceText(content)), addedBy, Instant.now(),
                runIdentity == null ? null : runIdentity.runId(),
                runIdentity == null ? null : runIdentity.lineageId(), null, null);
        ledger = InputLedger.seeded(seed);
        persist(seed);
    }

    private void persist(InputEntry input) {
        if (graph != null) {
            try {
                graph.save(input);
            } catch (Exception e) {
                log.warn("T4-EC: input {} was not written to the graph: {}", input.id(), e.getMessage());
            }
        }
        writeFile();
    }

    /** A further input arrived: appended to the ledger, never replacing what is there. */
    public synchronized InputEntry addInput(String kind, String content, String addedBy) {
        return append(kind, content, sha256(content), addedBy, null, null);
    }

    /** A project brought in: {@code intent} is what it is for; its run and lineage say where it came from. */
    public synchronized InputEntry addProjectInput(String intent, String addedBy, String sourceRunId, String sourceLineageId,
                                                   String treeDigest) {
        return append(InputEntry.KIND_PROJECT, intent, treeDigest, addedBy, sourceRunId, sourceLineageId);
    }

    private InputEntry append(String kind, String content, String digest, String addedBy, String sourceRunId,
                              String sourceLineageId) {
        InputEntry input = new InputEntry("input-" + UUID.randomUUID(), ledger.entries().size() + 1, kind, content,
                digest, addedBy, Instant.now(),
                runIdentity == null ? null : runIdentity.runId(),
                runIdentity == null ? null : runIdentity.lineageId(), sourceRunId, sourceLineageId);
        ledger = ledger.with(input);
        persist(input);
        return input;
    }

    /** The goal an input landed as, and its own root where the graph holds that; the graph's links are provenance. */
    public void linkInput(String inputId, String goalId, String sourceRootGoalId) {
        if (graph == null) return;
        try {
            graph.link(inputId, goalId, sourceRootGoalId);
        } catch (Exception e) {
            log.warn("T4-EC: input {} was not linked in the graph: {}", inputId, e.getMessage());
        }
    }

    /**
     * The root goal as loaded: record it as the seed when the ledger has none, or when the ledger's seed is not the
     * root's text (a root set by a path that did not record it).
     */
    public synchronized void ensureFor(String rootText) {
        if (rootText == null) return;
        InputEntry seed = ledger.seed();
        if (seed != null && rootText.equals(seed.content())) return;
        if (seed != null) {
            log.warn("T4-EC: the recorded seed input {} is not the root goal's text -- recording the root's text", seed.id());
        }
        recordSeed(rootText, "migrated");
    }

    /** The bytes the seed was read from where they exist, so the digest matches a key computed from them before. */
    private String sourceText(String content) {
        try {
            if (fileService.exists(SEED_FILE)) return fileService.readString(SEED_FILE);
        } catch (Exception e) {
            log.debug("T4-EC: {} unreadable: {}", SEED_FILE, e.getMessage());
        }
        return content;
    }

    private InputLedger readFile() {
        try {
            if (!fileService.exists(PATH)) return InputLedger.EMPTY;
            JsonNode doc = MAPPER.readTree(fileService.readString(PATH));
            List<InputEntry> entries = new ArrayList<>();
            for (JsonNode n : doc.path("inputs")) entries.add(MAPPER.treeToValue(n, InputEntry.class));
            List<InputEvent> events = new ArrayList<>();
            for (JsonNode n : doc.path("events")) events.add(MAPPER.treeToValue(n, InputEvent.class));
            return new InputLedger(entries, events);
        } catch (Exception e) {
            log.warn("T4-EC: {} unreadable: {}", PATH, e.getMessage());
            return InputLedger.EMPTY;
        }
    }

    private void writeFile() {
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("inputs", ledger.entries());
            if (!ledger.events().isEmpty()) doc.put("events", ledger.events());
            fileService.ensureFolderExists("meta");
            fileService.writeFile(PATH, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        } catch (Exception e) {
            log.warn("T4-EC: {} not written: {}", PATH, e.getMessage());
        }
        // T4-EG: what the project consists of may have changed -- an input kept, a text input given, a seed set.
        writeSpecification();
    }

    static String sha256(String text) {
        if (text == null) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
