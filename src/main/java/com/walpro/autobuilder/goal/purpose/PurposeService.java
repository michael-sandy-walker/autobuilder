package com.walpro.autobuilder.goal.purpose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.config.GoalPlannerConfigProperties;
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
 * {@link InputLedger}; the ledger lives in the graph (primary, like goals) with {@value #PATH} derived beside
 * {@code meta/GoalStack.json}, so it travels with the project and can be read by hand.
 *
 * <p>Wherever a root goal is set, the seed is recorded ({@link #recordSeed}); a project whose root predates the
 * ledger gets its entry from the root's text when it boots ({@link #ensureFor}).
 */
@Slf4j
@Service
public class PurposeService {

    public static final String PATH = "meta/inputs.json";
    static final String SEED_FILE = "bootstrap/seed.txt";

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

    @PostConstruct
    public void load() {
        List<InputEntry> entries = List.of();
        if (loadFromGraph && graph != null) {
            try {
                entries = graph.findAll();
            } catch (Exception e) {
                log.warn("T4-EC: inputs not readable from the graph ({}) -- reading {}", e.getMessage(), PATH);
            }
        }
        if (entries.isEmpty()) entries = readFile();
        ledger = new InputLedger(entries);
    }

    public InputLedger ledger() {
        return ledger;
    }

    /** The inputs so far as one specification; null while the project has none. */
    public String specification() {
        return ledger.specification();
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

    /** A line of what the project must do, and the input it came from. */
    public record Requirement(String inputId, String text) {
    }

    private List<Requirement> across(java.util.function.BiFunction<String, String, List<String>> read) {
        List<Requirement> out = new ArrayList<>();
        for (InputEntry e : ledger.entries()) {
            for (String line : read.apply(e.content(), obligationsText(e))) out.add(new Requirement(e.id(), line));
        }
        return out;
    }

    private static List<String> texts(List<Requirement> requirements) {
        return requirements.stream().map(Requirement::text).toList();
    }

    /** Every input's lines answered by {@code gate}, each with its input. */
    public List<Requirement> gated(String gate) {
        return across((spec, written) -> SeedRequirements.answeredByGate(spec, written, gate));
    }

    public List<String> answeredByGate(String gate) {
        return texts(gated(gate));
    }

    public List<String> rules() {
        return texts(across(SeedRequirements::rules));
    }

    public List<String> obligationsOrClauses() {
        return texts(across(SeedRequirements::obligationsOrClauses));
    }

    public List<String> verifiableObligationsOrSplit() {
        return texts(across(SeedRequirements::verifiableObligationsOrSplit));
    }

    public List<String> notAlreadyCoveredVerifiable(List<String> banked) {
        return texts(across((spec, written) -> SeedRequirements.notAlreadyCoveredVerifiable(spec, banked, written)));
    }

    /** Every input's written obligation lines as written, markers kept. */
    public List<String> writtenLines() {
        List<String> out = new ArrayList<>();
        for (InputEntry e : ledger.entries()) {
            String written = obligationsText(e);
            if (written != null) written.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).forEach(out::add);
        }
        return out;
    }

    /** Every input's obligations file as it stands, for a digest of what the project was asked. */
    public String writtenText() {
        StringBuilder out = new StringBuilder();
        for (InputEntry e : ledger.entries()) {
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
                runIdentity == null ? null : runIdentity.lineageId());
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
        InputEntry input = new InputEntry("input-" + UUID.randomUUID(), ledger.entries().size() + 1, kind, content,
                sha256(content), addedBy, Instant.now(),
                runIdentity == null ? null : runIdentity.runId(),
                runIdentity == null ? null : runIdentity.lineageId());
        ledger = ledger.with(input);
        persist(input);
        return input;
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

    private List<InputEntry> readFile() {
        try {
            if (!fileService.exists(PATH)) return List.of();
            JsonNode inputs = MAPPER.readTree(fileService.readString(PATH)).path("inputs");
            List<InputEntry> out = new ArrayList<>();
            for (JsonNode n : inputs) out.add(MAPPER.treeToValue(n, InputEntry.class));
            return out;
        } catch (Exception e) {
            log.warn("T4-EC: {} unreadable: {}", PATH, e.getMessage());
            return List.of();
        }
    }

    private void writeFile() {
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("inputs", ledger.entries());
            fileService.ensureFolderExists("meta");
            fileService.writeFile(PATH, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        } catch (Exception e) {
            log.warn("T4-EC: {} not written: {}", PATH, e.getMessage());
        }
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
