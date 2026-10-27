package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.analysis.structure.JavaSources;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.LlmCallContext;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.build.BuildExecutor;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.DerivedTestAuthority;
import com.walpro.autobuilder.core.build.DerivedTestsFile;
import com.walpro.autobuilder.core.build.DerivedTestsFile.Entry;
import com.walpro.autobuilder.core.build.DerivedTestsFile.Status;
import com.walpro.autobuilder.core.build.TestRunGate;
import com.walpro.autobuilder.core.build.TestRunReport;
import com.walpro.autobuilder.core.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T6-U: the tests a change is held to, written from the requirements' own wording BEFORE the code changes, then
 * frozen -- for a target whose specification tests are withheld from the loop.
 *
 * <p><b>The defect this closes.</b> Batch field105 ran the band-1 ladder cases with their specification tests
 * withheld: 0 of 6 true completions against 6 of 6 with the tests given, five of them false completions. In none
 * was the code beyond the model; in every one a behaviour the seed states was never tested, because the only tests
 * were the loop's own, written beside the code they were meant to check, or none at all.
 *
 * <p><b>How, and why this way -- each part kept only where the offline replay showed it helps</b>
 * ({@code scripts/claim_tests/}, the T6-U appends of 2026-10-24/25; the user's design, 2026-10-24):
 * <ol>
 *   <li><b>Divide and conquer.</b> Each requirement is split into ATOMIC claims, one situation and one expected
 *       outcome each, with the source of that outcome: stated by the wording, a fact of the runtime (computed in the
 *       test from the runtime, never written as a literal), or open (no test). One test per claim. The one-pass
 *       writing of a whole requirement's test was right 6 times in 27; claims were right 120 times in 125.</li>
 *   <li><b>Throwaway implementations as the check.</b> {@value #SPIKES} implementations of the change, written from
 *       the wording without ever seeing the tests; a claim's test is kept only when it passes on at least one. The
 *       replay: no wrong test passed any, every requirement kept tests. A reading reviewer accepted 26 of 27 one-pass
 *       tests, wrong ones included, and a judge deciding "test or implementation" kept two contradicting tests
 *       unanimously -- so no model reading decides here.</li>
 *   <li><b>Not built, measured useless:</b> a vote over several writings of a claim (agreement was total on wrong
 *       claims too).</li>
 * </ol>
 * The price: a right claim every throwaway implementation gets wrong is dropped -- the spikes share the model's
 * blind spots (receiver-questions: all three missed the compiler's record-component form). A dropped test is written
 * once more from the runner's report ({@link #rewrite}), since most are broken tests of right claims; a requirement is
 * then the gate's when it has an accepted test and every text it quotes is used by one ({@link #answeredBy});
 * otherwise it stays a reading, as before.
 *
 * <p><b>Which lines.</b> Only an obligation the operator marked {@code gate: derived-test} -- which gate answers
 * which line is the operator's declaration ({@link SeedRequirements#GATE_MARKER}), never inferred from wording.
 * The writing runs in the background when the root is set; closure waits while it does
 * ({@link GoalPlannerService}), and a green suite is not the objective until every marked line is the gate's
 * ({@link ObjectiveMetClosure}).
 */
@Slf4j
@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(
        value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "the accepted tests live in meta/derived-tests.json and the spikes under meta/derived-spikes; this holds "
                + "only a review cache, unsent notes and whether a writing is under way")
@ConditionalOnProperty(name = "autobuilder.obligations.derived-tests", havingValue = "true", matchIfMissing = true)
public class DerivedTests implements DerivedTestAuthority {

    /** Throwaway implementations a claim's test is checked on: as many as the replay measured. */
    static final int SPIKES = 3;

    static final String SEED_PATH = "bootstrap/seed.txt";
    static final String TEST_ROOT = "src/test/";
    static final String MAIN_ROOT = "src/main/";
    /** Where the throwaway implementations are kept (a later proposal for a frozen test is run on them too). */
    static final String SPIKES_DIR = "meta/derived-spikes";

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{3,})\"|`([^`]{3,})`");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SOURCES = """
            For each claim say where its expected outcome comes from:
            - STATED: the requirement or the specification gives it -- copy the words that give it;
            - ENVIRONMENT: a fact about the language runtime or its standard library (for example which methods a \
            standard class declares) -- a test will compute it from the runtime;
            - OPEN: the wording does not fix it.
            Use only situations the requirement or the specification names or plainly implies; never invent an input form.
            Answer with JSON only: [{"claim": "...", "source": "STATED|ENVIRONMENT|OPEN", "words": "..."}]""";

    static final String DECOMPOSE_SYSTEM = "You split ONE requirement of a change to an existing project into atomic "
            + "claims a test can check. A claim is one situation (an input) and one expected outcome.\n" + SOURCES;

    static final String WRITE_SYSTEM = """
            You write ONE test class with ONE test method for ONE claim about a change to an existing project. The \
            code that meets it has not been written yet: write the test from the claim, the requirement and the \
            specification, so that it fails until the code does what the claim says.
            - Check exactly this claim -- its situation and its expected outcome -- and nothing else.
            - Assert only what the claim's source gives. A fact about the runtime (an ENVIRONMENT claim) is computed in \
            the test from the runtime itself, for example by reflection -- never written out as a literal list or value.
            - Do not name a type, method or value the specification and the declarations shown do not name; where the \
            result's type is not named, do not name it.
            - Use the same language, libraries and assertion style as the existing tests shown.
            Answer with the one file in a fenced block whose opening line names its path in brackets, as shown.""";

    static final String SPIKE_SYSTEM = """
            You write the change a specification asks for, in an existing project. Write the full new content of every \
            source file you add or change under src/main/, each in a fenced block whose opening line names its path in \
            brackets, for example:
            ```java [src/main/java/org/example/Thing.java]
            Do not write or change any test. Keep every existing declaration the specification says to keep.""";

    static final String REWRITE_SYSTEM = WRITE_SYSTEM + """


            Your earlier test for this claim was run against an implementation written from the same specification, \
            and the report of that run is shown. That implementation may itself be wrong. Correct the test where IT is \
            wrong -- how it builds its input, how it calls the code, what it names -- and keep the claim's expected \
            outcome exactly as the claim and the specification give it.""";

    /** Repaired implementations per writing, and feedback rounds per repair: as the offline replay measured. */
    static final int REPAIRS = 3;
    static final int ROUNDS = 3;

    static final String REPAIR_SYSTEM = SPIKE_SYSTEM + """

            Some claims about the change are listed below. Earlier implementations of the same specification did not \
            meet them; meet them as well as everything else the specification says, as the specification words them.""";

    static final String REVIEW_SYSTEM = """
            You check whether a test checks a requirement -- as the requirement is WORDED. You see the requirement, \
            the specification it comes from, and the test. You do not see the code, and must not guess at it.
            A test passes the check when it exercises every case and every form the requirement names, with the \
            values and wordings it gives, and asserts what the requirement says the result is. It fails the check \
            when it leaves out a named case or form, uses a different example or wording than the one given, \
            asserts something weaker than the requirement, or asserts something the requirement does not say.
            Answer with the first line exactly "VERDICT: PASS" or "VERDICT: FAIL", then one short paragraph naming \
            what is missing or wrong.""";

    private static final Pattern HEADER = Pattern.compile("^```\\w*\\s*\\[([^\\]]+)\\]\\s*$");

    private final LLMClient llmClient;
    private final FileService files;

    /** Runs a tree's tests: the loop's own build executor and test command, in a tree of the spikes. */
    interface Bench {
        /** Null when the tree compiles, else what the compiler said. */
        String compiles(Path tree);

        /** For each test path (relative to the tree), whether every case of it passed there. */
        Map<String, Boolean> run(Path tree, Map<String, String> tests);

        /** What the runner reported for the tests at {@code only}, all of {@code tests} run together -- never a source. */
        default String failures(Path tree, Map<String, String> tests, Set<String> only) {
            return "";
        }

        /** What the build reported for the one test at {@code path} in {@code tree}: its failures, or the compiler's. */
        default String report(Path tree, String path, String content) {
            return "";
        }
    }

    private Bench bench;

    @Value("${autobuilder.evaluation.fileContentBudgetChars:40000}")
    private int fileContentBudgetChars = 40000;

    /**
     * Where a finished writing is kept for the next run of the same case: what was written depends only on the seed,
     * the obligations and the tree it started from, so a run with all three unchanged reuses it instead of spending
     * the writing again (~20 and ~50 minutes on the band-1 cases). Blank: no reuse.
     */
    @Value("${autobuilder.obligations.derived-tests-cache:${user.home}/.autobuilder/derived-tests-cache}")
    private String cacheDir = "";

    void setCacheDir(String cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** True while the requirements' tests are being written: no closure may rest on a suite that lacks them. */
    private volatile boolean pending;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "derived-tests");
        t.setDaemon(true);
        return t;
    });

    /** Review verdicts per proposed text, so a proposal reaching several writers is judged once. */
    private final Map<String, Boolean> contested = new ConcurrentHashMap<>();

    private final List<String> notes = new ArrayList<>();

    public DerivedTests(LLMClient llmClient, FileService files) {
        this.llmClient = llmClient;
        this.files = files;
    }

    @Autowired(required = false)
    void wireBuilds(BuildExecutor buildExecutor,
                    @Value("${autobuilder.build.test-gate.command:mvn test}") String testCommand) {
        this.bench = new BuildBench(buildExecutor, testCommand);
    }

    void setBench(Bench bench) {
        this.bench = bench;
    }

    /** What a check concluded, and why. */
    record Review(boolean pass, String reason) {}

    /** One atomic claim of a requirement. */
    record Claim(String requirement, String text, String source, String words) {}

    /** True while the tests are being written. */
    public boolean pending() {
        return pending;
    }

    /** The requirements the operator marked {@code gate: derived-test}, markers removed. */
    public List<String> requirements() {
        return requirements(read(SEED_PATH));
    }

    private List<String> requirements(String seedText) {
        return SeedRequirements.answeredByGate(seedText, read(SeedObligationsFile.PATH), SeedRequirements.GATE_DERIVED);
    }

    /**
     * The requirements the test gate now answers: each has an accepted test, and every text it quotes is used by one.
     *
     * <p>Not "every claim accepted": a claim's test that fails on every throwaway implementation is, nearly always, a
     * badly written test of a claim the accepted ones already check -- of the 194 dropped by five writings, about 190
     * fail on the ticket's real implementation too (2026-10-27) -- and one such test made a requirement with 5 to 17
     * accepted tests a reading (field111: 3 of 17 and 1 of 6 answered). What a dropped claim can still leave unchecked
     * shows where the requirement quotes an input: receiver-questions' record-component form, which the spikes all get
     * wrong, is the one quoted text no accepted test uses, and its requirement stays a reading.
     */
    public Set<String> acceptedRequirements() {
        Map<String, List<String>> kept = new LinkedHashMap<>();
        for (Entry e : DerivedTestsFile.read(files)) {
            List<String> texts = kept.computeIfAbsent(e.requirement(), r -> new ArrayList<>());
            if (e.accepted() && e.content() != null) texts.add(e.content());
        }
        Set<String> out = new LinkedHashSet<>();
        kept.forEach((r, texts) -> { if (answeredBy(r, texts)) out.add(r); });
        return out;
    }

    /** True when {@code tests} is not empty and every text {@code requirement} quotes is used by one of them. */
    static boolean answeredBy(String requirement, List<String> tests) {
        if (tests.isEmpty()) return false;
        Matcher m = QUOTED.matcher(requirement);
        while (m.find()) {
            Pattern quoted = quotedPattern(m.group(1) != null ? m.group(1) : m.group(2));
            if (tests.stream().noneMatch(t -> quoted.matcher(comparable(t)).find())) return false;
        }
        return true;
    }

    /** True when nothing is being written and every marked requirement is answered by the test gate. */
    public boolean settled() {
        if (pending) return false;
        return acceptedRequirements().containsAll(requirements());
    }

    /**
     * Write and check the tests of every marked requirement that has none yet, in the background. Called where the
     * root goal is set, beside the obligations file it reads; tests already written are kept across restarts.
     */
    public void ensureFor(String seedText) {
        if (seedText == null || seedText.isBlank() || requirements(seedText).isEmpty()) return;
        pending = true;
        // The key of the starting tree is taken HERE, before the loop's first cycle can change the tree; taken on the
        // writer's thread it would race the loop and miss a kept writing.
        String key = DerivedTestsFile.read(files).isEmpty() ? cacheKey(seedText) : null;
        writer.submit(() -> {
            try {
                ensureNow(seedText, key);
            } finally {
                pending = false;
            }
        });
    }

    /** As {@link #ensureFor}, on the caller's thread. */
    void ensureNow(String seedText) {
        ensureNow(seedText, DerivedTestsFile.read(files).isEmpty() ? cacheKey(seedText) : null);
    }

    private void ensureNow(String seedText, String key) {
        List<String> lines = requirements(seedText);
        if (lines.isEmpty()) return;
        pending = true;
        try {
            List<Entry> entries = new ArrayList<>(DerivedTestsFile.read(files));
            entries.removeIf(e -> !lines.contains(e.requirement()));
            Set<String> done = new LinkedHashSet<>();
            for (Entry e : entries) done.add(e.requirement());
            List<String> todo = lines.stream().filter(l -> !done.contains(l)).toList();
            if (todo.isEmpty()) {
                restore();
                return;     // pending is cleared below
            }
            if (key != null && reuse(key)) {
                restore();
                return;
            }
            Map<String, String> tests = sources(TEST_ROOT);
            String context = "SPECIFICATION:\n" + seedText + "\n\nTHE PROJECT'S DECLARATIONS AS THEY ARE NOW (bodies left out):\n"
                    + outline() + "\n\nTHE EXISTING TESTS:\n" + listing(tests);

            List<Claim> claims = new ArrayList<>();
            for (String requirement : todo) {
                List<Claim> of = claims(requirement, context);
                if (of.isEmpty()) log.warn("T6-U: no checkable claim in \"{}\" -- it stays a reading", requirement);
                claims.addAll(of);
            }
            int counter = entries.size();
            Map<String, List<Claim>> written = new LinkedHashMap<>();   // test path -> the claims it checks
            Map<String, String> texts = new LinkedHashMap<>();          // test path -> test text
            Map<String, String> pathOfClaim = new LinkedHashMap<>();    // claim as compared -> test path
            List<Entry> unwritten = new ArrayList<>();
            for (Claim c : claims) {
                // field108's derivation of edit-hunks: 206 claims, 143 distinct -- the same claim stated under several
                // requirements. Written once; each requirement's entry points at the one test.
                String same = pathOfClaim.get(comparableClaim(c.text()));
                if (same != null) {
                    written.get(same).add(c);
                    continue;
                }
                String name = String.format("DerivedClaim%03dTest", ++counter);
                String header = header(name, List.copyOf(tests.keySet()));
                String answer = ask(WRITE_SYSTEM, "CLAIM (" + c.source() + "): " + c.text() + "\nWORDS THAT GIVE IT: "
                        + c.words() + "\n\nREQUIREMENT:\n" + c.requirement() + "\n\n" + context + "\n\nName the class "
                        + name + ". Open the block exactly as:\n" + header, "derived-test");
                Optional<FileMutation> file = located(answer, name, tests);
                if (file.isEmpty() || !file.get().path().replace('\\', '/').startsWith(TEST_ROOT)) {
                    unwritten.add(new Entry(c.requirement(), c.text(), null, answer, Status.UNACCEPTED,
                            "the answer held no test file named " + name));
                    continue;
                }
                String path = file.get().path().replace('\\', '/');
                written.put(path, new ArrayList<>(List.of(c)));
                texts.put(path, file.get().content());
                pathOfClaim.put(comparableClaim(c.text()), path);
            }

            List<Path> spikes = spikes(seedText, tests);
            Map<String, Integer> passes = new LinkedHashMap<>();
            for (Path spike : spikes) {
                bench.run(spike, texts).forEach((p, ok) -> passes.merge(p, ok ? 1 : 0, Integer::sum));
            }
            Set<String> rewritten = spikes.isEmpty() ? Set.of() : rewrite(context, spikes, written, texts, passes);
            Set<String> recovered = spikes.isEmpty() ? Set.of() : repair(seedText, tests, written, texts, passes);
            for (Map.Entry<String, List<Claim>> w : written.entrySet()) {
                int n = passes.getOrDefault(w.getKey(), 0);
                boolean ok = n > 0 || recovered.contains(w.getKey());
                String reason = spikes.isEmpty() ? "no throwaway implementation compiled, so no test could be checked"
                        : n == 0 && ok ? "failed on every throwaway implementation; passed on a repaired one beside every"
                        + " accepted test"
                        : rewritten.contains(w.getKey()) ? "failed on every throwaway implementation; rewritten once from"
                        + " the runner's report, then passed on " + n + " of " + spikes.size()
                        : "passed on " + n + " of " + spikes.size() + " throwaway implementation(s)";
                for (Claim c : w.getValue()) {
                    entries.add(new Entry(c.requirement(), c.text(), w.getKey(), texts.get(w.getKey()),
                            ok ? Status.ACCEPTED : Status.UNACCEPTED, reason));
                }
                recordAttempt(w.getValue().get(0), w.getKey(), texts.get(w.getKey()), ok, reason);
            }
            entries.addAll(unwritten);
            DerivedTestsFile.write(files, entries);
            if (key != null && !spikes.isEmpty()) keep(key);   // a writing nothing could check is not worth reusing
            long accepted = entries.stream().filter(Entry::accepted).count();
            Set<String> answered = acceptedRequirements();
            log.warn("T6-U: {} claim(s) of {} requirement(s); {} test(s) accepted on {} throwaway implementation(s);"
                            + " {} of {} requirement(s) answered by the test gate{}", claims.size(), todo.size(), accepted,
                    spikes.size(), lines.stream().filter(answered::contains).count(), lines.size(),
                    answered.containsAll(lines) ? "" : " -- no kept test answers the rest, and closure is refused while they"
                            + " are open");
            restore();
        } catch (Exception e) {
            log.warn("T6-U: could not write the requirements' tests: {}", e.getMessage());
        } finally {
            pending = false;
        }
    }

    /** A claim as compared for repeats: case, punctuation and spacing aside -- nothing looser (unmeasured). */
    static String comparableClaim(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
    }

    /** The requirement's atomic claims with a source; OPEN ones dropped (the wording does not fix them). */
    List<Claim> claims(String requirement, String context) {
        String answer = ask(DECOMPOSE_SYSTEM, "REQUIREMENT:\n" + requirement + "\n\n" + context, "derived-test-claims");
        List<Claim> out = new ArrayList<>();
        for (Map<String, Object> c : jsonList(answer)) {
            String text = String.valueOf(c.getOrDefault("claim", "")).strip();
            String source = String.valueOf(c.getOrDefault("source", "OPEN")).strip().toUpperCase(java.util.Locale.ROOT);
            if (text.isEmpty() || source.equals("OPEN")) continue;
            out.add(new Claim(requirement, text, source, String.valueOf(c.getOrDefault("words", ""))));
        }
        return out;
    }

    /** The seed, the obligations and the starting tree (sources and tests, build output aside), as one digest. */
    private String cacheKey(String seedText) {
        if (cacheDir == null || cacheDir.isBlank()) return null;
        try {
            // The prompts too: a changed way of writing must never reuse what the old one wrote.
            StringBuilder sb = new StringBuilder(DECOMPOSE_SYSTEM + WRITE_SYSTEM + REWRITE_SYSTEM + SPIKE_SYSTEM + REPAIR_SYSTEM).append('\u0000')
                    .append(seedText).append('\u0000').append(read(SeedObligationsFile.PATH));
            Path out = files.resolveSecurePath("output");
            try (var walk = Files.walk(out)) {
                for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String rel = out.relativize(p).toString().replace('\\', '/');
                    if (rel.startsWith("target/") || rel.contains("/target/")) continue;
                    sb.append('\u0000').append(rel).append('\u0000').append(sha256(new String(Files.readAllBytes(p),
                            java.nio.charset.StandardCharsets.ISO_8859_1)));
                }
            }
            return sha256(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** Copies a kept writing for {@code key} in: its tests and its throwaway implementations. False when none. */
    private boolean reuse(String key) {
        try {
            Path kept = Path.of(cacheDir, key);
            if (!Files.isRegularFile(kept.resolve("derived-tests.json"))) return false;
            files.writeFile(DerivedTestsFile.PATH, Files.readString(kept.resolve("derived-tests.json")));
            Path spikes = files.resolveSecurePath(SPIKES_DIR);
            deleteTree(spikes);
            if (Files.isDirectory(kept.resolve("spikes"))) copyTree(kept.resolve("spikes"), spikes);
            log.warn("T6-U: the requirements' tests were written before for this seed, these obligations and this"
                    + " starting tree -- reused from {} instead of written again", kept);
            return true;
        } catch (Exception e) {
            log.warn("T6-U: could not reuse the kept tests for {}: {}", key, e.getMessage());
            return false;
        }
    }

    /** Keeps this writing for the next run of the same case. */
    private void keep(String key) {
        try {
            Path kept = Path.of(cacheDir, key);
            deleteTree(kept);
            Files.createDirectories(kept);
            Files.writeString(kept.resolve("derived-tests.json"), DerivedTestsFile.readText(files));
            Path spikes = files.resolveSecurePath(SPIKES_DIR);
            if (Files.isDirectory(spikes)) copyTree(spikes, kept.resolve("spikes"));
        } catch (Exception e) {
            log.warn("T6-U: could not keep the tests for reuse: {}", e.getMessage());
        }
    }

    /** The first JSON list of objects in a model's answer. */
    static List<Map<String, Object>> jsonList(String answer) {
        if (answer == null) return List.of();
        for (int i = answer.indexOf('['); i >= 0; i = answer.indexOf('[', i + 1)) {
            for (int j = answer.lastIndexOf(']'); j > i; j = answer.lastIndexOf(']', j - 1)) {
                try {
                    return JSON.readValue(answer.substring(i, j + 1), new TypeReference<List<Map<String, Object>>>() {});
                } catch (Exception ignored) {
                    // a shorter span may parse
                }
            }
        }
        return List.of();
    }

    /**
     * Up to {@value #SPIKES} throwaway implementations of the change, written from the specification, the sources and
     * the existing tests -- never a claim's test -- each in a copy of the tree under {@value #SPIKES_DIR}; one that
     * does not compile is asked once more with the compiler's words, then dropped.
     */
    private List<Path> spikes(String seedText, Map<String, String> tests) throws Exception {
        List<Path> kept = new ArrayList<>();
        if (bench == null) return kept;
        Path output = files.resolveSecurePath("output");
        String user = "SPECIFICATION:\n" + seedText + "\n\nTHE PROJECT'S SOURCES AS THEY ARE NOW:\n" + listing(sources(MAIN_ROOT))
                + "\n\nTHE EXISTING TESTS:\n" + listing(tests);
        for (int s = 1; s <= SPIKES; s++) {
            Path tree = files.resolveSecurePath(SPIKES_DIR + "/spike-" + s);
            deleteTree(tree);
            copyTree(output, tree);
            String answer = ask(SPIKE_SYSTEM, user, "derived-test-spike");
            String errors = null;
            int changed = 0;
            for (int attempt = 0; attempt < 2; attempt++) {
                changed += writeMain(tree, answer);
                // An answer that changes no source is not an implementation: the unchanged tree compiles, and every
                // claim test then fails on it (2026-10-27: three such answers, "I'll start by exploring the project
                // structure", and 141 of 143 tests dropped).
                errors = changed == 0 ? NO_CHANGE : bench.compiles(tree);
                if (errors == null || attempt == 1) break;
                answer = ask(SPIKE_SYSTEM, user + "\n\nYOUR EARLIER ANSWER:\n" + answer + "\n\n"
                        + (changed == 0 ? errors : "IT DID NOT COMPILE:\n" + errors) + "\nWrite the corrected full files.",
                        "derived-test-spike");
            }
            if (errors == null) kept.add(tree);
            log.info("T6-U: throwaway implementation {} {}", s, errors == null ? "compiles"
                    : changed == 0 ? "changes no source -- dropped" : "does not compile -- dropped");
        }
        return kept;
    }

    /**
     * A claim's test that failed on every throwaway implementation, written once more: the writer is shown what the
     * build reported for it on the first one, told that implementation may be the one that is wrong, and the rewrite
     * replaces the test only when the claim's quoted words are still in it and it then passes on a throwaway
     * implementation, as any new test must. Returns the paths whose test was replaced; {@code texts} and
     * {@code passes} are updated in place.
     *
     * <p>Why: the dropped tests of five writings, scored on the ticket's real implementation (2026-10-27), are nearly
     * all broken tests of right claims -- an edit block with no closing fence, an invented result type -- and
     * edit-hunks' "occurs N times" kept no test in any of three writings. A repaired implementation cannot recover
     * those; the test is what is wrong. Offline ({@code scripts/claim_tests/rewrite.py}): receiver-questions' writing,
     * 4 of 10 dropped tests rewritten and accepted, all 4 right on the real implementation and every reference tree.
     */
    private Set<String> rewrite(String context, List<Path> spikes, Map<String, List<Claim>> written,
                                Map<String, String> texts, Map<String, Integer> passes) {
        Map<String, String> again = new LinkedHashMap<>();
        long dropped = written.keySet().stream().filter(p -> passes.getOrDefault(p, 0) == 0).count();
        for (Map.Entry<String, List<Claim>> w : written.entrySet()) {
            String path = w.getKey();
            if (passes.getOrDefault(path, 0) > 0) continue;
            Claim c = w.getValue().get(0);
            String name = stem(fileName(path));
            String said = bench.report(spikes.get(0), path, texts.get(path));
            String answer = ask(REWRITE_SYSTEM, "CLAIM (" + c.source() + "): " + c.text() + "\nWORDS THAT GIVE IT: "
                    + c.words() + "\n\nREQUIREMENT:\n" + c.requirement() + "\n\n" + context + "\n\nYOUR EARLIER TEST:\n"
                    + "```" + extOf(path).replace(".", "") + " [" + path + "]\n" + texts.get(path) + "```\n\nTHE RUN ON ONE"
                    + " IMPLEMENTATION REPORTED:\n" + (said.isBlank() ? "(nothing)" : said) + "\n\nName the class " + name
                    + ". Open the block exactly as:\n```" + extOf(path).replace(".", "") + " [" + path + "]",
                    "derived-test-rewrite");
            Optional<FileMutation> file = located(answer, name, Map.of(path, texts.get(path)));
            if (file.isEmpty()) continue;
            if (missingQuotes(c.text(), file.get().content()).isPresent()) {
                log.info("T6-U: the rewrite of {} left out the claim's quoted words -- not used", name);
                continue;
            }
            again.put(path, file.get().content());
        }
        if (again.isEmpty()) return Set.of();
        Map<String, Integer> now = new LinkedHashMap<>();
        for (Path spike : spikes) bench.run(spike, again).forEach((p, ok) -> now.merge(p, ok ? 1 : 0, Integer::sum));
        Set<String> replaced = new LinkedHashSet<>();
        for (Map.Entry<String, String> r : again.entrySet()) {
            int n = now.getOrDefault(r.getKey(), 0);
            if (n == 0) continue;
            texts.put(r.getKey(), r.getValue());
            passes.put(r.getKey(), n);
            replaced.add(r.getKey());
        }
        log.warn("T6-U: {} claim test(s) failed on every throwaway implementation; {} rewritten, {} of them then passed"
                + " on one -- kept", dropped, again.size(), replaced.size());
        return replaced;
    }

    /**
     * The spikes' blind spot (field108/109: receiver-questions' record-component claims failed on every spike, so the
     * loop was never held to them, and with the tests given it solves that case 3 of 3): up to {@value #REPAIRS}
     * repaired implementations, each told the dropped claims (never their tests) and given, for up to {@value #ROUNDS}
     * rounds, what the runner reported for the ones still failing -- the loop's own way to a change. A round that
     * breaks an accepted test is undone. A dropped claim is recovered when a repair passes it while EVERY accepted test
     * passes beside it: one implementation then meets it and everything else, so it contradicts nothing accepted.
     * Measured offline (scripts/claim_tests/repair.py): no contradicting test kept in four repairs; the blind-spot
     * claims recovered in one repair of three -- hence several.
     */
    private Set<String> repair(String seedText, Map<String, String> tests, Map<String, List<Claim>> written,
                               Map<String, String> texts, Map<String, Integer> passes) throws Exception {
        Map<String, String> accepted = new LinkedHashMap<>();
        Map<String, String> dropped = new LinkedHashMap<>();
        texts.forEach((p, t) -> (passes.getOrDefault(p, 0) > 0 ? accepted : dropped).put(p, t));
        Set<String> recovered = new LinkedHashSet<>();
        if (dropped.isEmpty()) return recovered;
        Path output = files.resolveSecurePath("output");
        String base = "SPECIFICATION:\n" + seedText + "\n\nTHE PROJECT'S SOURCES AS THEY ARE NOW:\n" + listing(sources(MAIN_ROOT))
                + "\n\nTHE EXISTING TESTS:\n" + listing(tests);
        Map<String, String> everything = new LinkedHashMap<>(accepted);
        everything.putAll(dropped);
        for (int r = 1; r <= REPAIRS && !dropped.isEmpty(); r++) {
            // A claim one repair recovered is accepted for the next: two recovered claims must hold together too.
            for (String p : recovered) if (dropped.containsKey(p)) accepted.put(p, dropped.remove(p));
            if (dropped.isEmpty()) break;
            StringBuilder claimList = new StringBuilder();
            for (String p : dropped.keySet()) claimList.append("- ").append(written.get(p).get(0).text()).append('\n');
            String user = base + "\n\nCLAIMS EARLIER IMPLEMENTATIONS DID NOT MEET:\n" + claimList;
            Path tree = files.resolveSecurePath(SPIKES_DIR + "/repair-" + r);
            deleteTree(tree);
            copyTree(output, tree);
            String answer = ask(REPAIR_SYSTEM, user, "derived-test-repair");
            if (writeMain(tree, answer) == 0) {
                answer = ask(REPAIR_SYSTEM, user + "\n\nYOUR EARLIER ANSWER:\n" + answer + "\n\n" + NO_CHANGE
                        + "\nWrite the corrected full files.", "derived-test-repair");
                if (writeMain(tree, answer) == 0) {
                    log.info("T6-U: repaired implementation {} changes no source -- dropped", r);
                    continue;
                }
            }
            if (bench.compiles(tree) != null) {
                answer = ask(REPAIR_SYSTEM, user + "\n\nYOUR EARLIER CHANGE:\n" + answer + "\n\nIT DID NOT COMPILE:\n"
                        + bench.compiles(tree) + "\nWrite the corrected full files.", "derived-test-repair");
                writeMain(tree, answer);
                if (bench.compiles(tree) != null) {
                    log.info("T6-U: repaired implementation {} does not compile -- dropped", r);
                    continue;
                }
            }
            Path backup = null;
            for (int round = 0; round <= ROUNDS; round++) {
                Map<String, Boolean> res = bench.run(tree, everything);
                boolean acceptedHold = accepted.keySet().stream().allMatch(p -> Boolean.TRUE.equals(res.get(p)));
                if (!acceptedHold) {
                    if (backup != null) {
                        deleteTree(tree);
                        copyTree(backup, tree);
                    }
                    log.info("T6-U: repaired implementation {} round {} broke an accepted test -- undone", r, round);
                    break;
                }
                dropped.keySet().stream().filter(p -> Boolean.TRUE.equals(res.get(p))).forEach(recovered::add);
                List<String> still = dropped.keySet().stream().filter(p -> !Boolean.TRUE.equals(res.get(p))).toList();
                log.info("T6-U: repaired implementation {} round {}: {} of {} dropped claim(s) pass beside every accepted"
                        + " test", r, round, dropped.size() - still.size(), dropped.size());
                if (still.isEmpty() || round == ROUNDS) break;
                Path prev = files.resolveSecurePath(SPIKES_DIR + "/repair-" + r + "-previous");
                deleteTree(prev);
                copyTree(tree, prev);
                backup = prev;
                Map<String, String> stillTests = new LinkedHashMap<>();
                for (String p : still) stillTests.put(p, dropped.get(p));
                answer = ask(REPAIR_SYSTEM, user + "\n\nYOUR CURRENT CHANGE:\n" + answer + "\n\nCHECKS OF THOSE CLAIMS STILL"
                        + " FAIL, AS THE TEST RUNNER REPORTED THEM:\n" + bench.failures(tree, everything, stillTests.keySet())
                        + "\nWrite the corrected full files.", "derived-test-repair");
                writeMain(tree, answer);
                if (bench.compiles(tree) != null) {
                    deleteTree(tree);
                    copyTree(prev, tree);
                    log.info("T6-U: repaired implementation {} round {} did not compile -- undone", r, round + 1);
                }
            }
            deleteTree(files.resolveSecurePath(SPIKES_DIR + "/repair-" + r + "-previous"));
        }
        if (!recovered.isEmpty()) {
            log.warn("T6-U: {} claim test(s) that failed on every throwaway implementation passed on a repaired one beside"
                    + " every accepted test -- kept", recovered.size());
        }
        return recovered;
    }

    /** Writes the answer's files under src/main/ into {@code tree}; how many it wrote. */
    private int writeMain(Path tree, String answer) throws java.io.IOException {
        int n = 0;
        for (Map.Entry<String, String> f : filesOf(answer).entrySet()) {
            if (!f.getKey().startsWith(MAIN_ROOT)) continue;
            Path p = tree.resolve(f.getKey()).normalize();
            if (!p.startsWith(tree)) continue;
            Files.createDirectories(p.getParent());
            Files.writeString(p, f.getValue());
            n++;
        }
        return n;
    }

    static final String NO_CHANGE = "YOUR ANSWER CHANGED NO SOURCE FILE: it held no fenced block whose opening line names a"
            + " path under src/main/. Write the change itself, as the full files.";

    /** Path blocks of an answer: a file runs from its header to the last bare fence before the next header. */
    static Map<String, String> filesOf(String answer) {
        Map<String, String> out = new LinkedHashMap<>();
        if (answer == null) return out;
        // A header or closing fence starts at column 0: indented, the same text is the code's own (an edit form's
        // sources write fenced examples in their strings and comments).
        String[] lines = answer.split("\n", -1);
        List<Integer> heads = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) if (HEADER.matcher(lines[i].stripTrailing()).matches()) heads.add(i);
        for (int n = 0; n < heads.size(); n++) {
            int h = heads.get(n);
            int end = n + 1 < heads.size() ? heads.get(n + 1) : lines.length;
            int close = -1;
            for (int i = h + 1; i < end; i++) if (lines[i].stripTrailing().equals("```")) close = i;
            if (close < 0) continue;
            Matcher m = HEADER.matcher(lines[h].stripTrailing());
            m.matches();
            out.put(m.group(1).strip(), String.join("\n", java.util.Arrays.copyOfRange(lines, h + 1, close)) + "\n");
        }
        return out;
    }

    /** The opening of the block the model is asked for: beside the existing tests, under the test root. */
    static String header(String name, List<String> testPaths) {
        String dir = testPaths.isEmpty() ? TEST_ROOT.substring(0, TEST_ROOT.length() - 1) : dirOf(testPaths.get(0));
        String ext = testPaths.isEmpty() ? "" : extOf(testPaths.get(0));
        String lang = ext.isEmpty() ? "" : ext.substring(1);
        return "```" + lang + " [" + dir + "/" + name + ext + "]";
    }

    /**
     * The answer's file for {@code name}. One file is asked for, so its text runs from the first fence to the LAST one:
     * a test of an edit form writes fences inside its own strings. The path is the block's header when it names
     * {@code name}; otherwise -- the local model often writes a bare fenced block -- beside the existing test whose
     * first line it shares (its package, in the projects seen so far), else beside the first.
     */
    static Optional<FileMutation> located(String answer, String name, Map<String, String> tests) {
        if (answer == null) return Optional.empty();
        Matcher open = Pattern.compile("(?m)^[ \\t]*```([^\\n]*)\\n").matcher(answer);
        if (!open.find()) return Optional.empty();
        int close = answer.lastIndexOf("```");
        if (close <= open.end()) return Optional.empty();
        String content = answer.substring(open.end(), close);
        if (!content.contains(name)) return Optional.empty();
        Matcher bracket = Pattern.compile("\\[([^\\]]+)\\]").matcher(open.group(1));
        if (bracket.find() && fileName(bracket.group(1).strip()).startsWith(name + ".")) {
            return Optional.of(new FileMutation(bracket.group(1).strip(), content));
        }
        if (tests.isEmpty()) return Optional.empty();
        String first = firstLine(content);
        String beside = tests.entrySet().stream().filter(t -> firstLine(t.getValue()).equals(first))
                .map(Map.Entry::getKey).findFirst().orElse(tests.keySet().iterator().next());
        return Optional.of(new FileMutation(dirOf(beside) + "/" + name + extOf(beside), content));
    }

    /** Every text the requirement quotes, compared without escapes or spacing, must appear in the test. */
    static Optional<String> missingQuotes(String requirement, String test) {
        String body = comparable(test);
        Matcher m = QUOTED.matcher(requirement);
        List<String> absent = new ArrayList<>();
        while (m.find()) {
            String quoted = m.group(1) != null ? m.group(1) : m.group(2);
            if (!quotedPattern(quoted).matcher(body).find()) absent.add(quoted);
        }
        return absent.isEmpty() ? Optional.empty()
                : Optional.of("the requirement quotes " + String.join(", ", absent.stream().map(q -> "\"" + q + "\"").toList())
                + ", and the test never uses " + (absent.size() == 1 ? "it" : "them"));
    }

    /**
     * A quoted text as a pattern: literal, except that a capital letter standing alone is a placeholder for one value
     * ("occurs N times" is written "occurs 2 times" by a test that checks it).
     */
    static Pattern quotedPattern(String quoted) {
        StringBuilder re = new StringBuilder();
        String text = comparable(quoted);
        Matcher placeholder = Pattern.compile("(?<![A-Za-z0-9_])[A-Z](?![A-Za-z0-9_])").matcher(text);
        int at = 0;
        while (placeholder.find()) {
            re.append(Pattern.quote(text.substring(at, placeholder.start()))).append("[A-Za-z0-9_.]+");
            at = placeholder.end();
        }
        re.append(Pattern.quote(text.substring(at)));
        return Pattern.compile(re.toString());
    }

    private static String comparable(String text) {
        return text.replace("\\", "").replaceAll("\\s+", " ").strip();
    }

    static Review verdict(String answer) {
        if (answer == null) return new Review(false, "the review gave no verdict");
        String a = answer.strip();
        int nl = a.indexOf('\n');
        String first = (nl < 0 ? a : a.substring(0, nl)).strip().toUpperCase(java.util.Locale.ROOT);
        String why = nl < 0 ? "" : a.substring(nl + 1).strip();
        if (first.contains("VERDICT: PASS")) return new Review(true, why.isEmpty() ? "the review passed it" : why);
        if (first.contains("VERDICT: FAIL")) return new Review(false, why.isEmpty() ? "the review failed it" : why);
        return new Review(false, "the review gave no verdict");
    }

    /**
     * A writer's text for a frozen test is accepted only as a new test would be -- it passes on a throwaway
     * implementation -- and, since a writer could weaken it there, only when the claim's quoted words are still in it
     * and a reading of the claim and the text (never the code) passes it as well.
     */
    @Override
    public boolean contest(String writer, String pathUnderOutput, String content) {
        List<Entry> entries = new ArrayList<>(DerivedTestsFile.read(files));
        Entry current = entries.stream().filter(e -> e.accepted() && pathUnderOutput.equals(e.path())).findFirst().orElse(null);
        if (current == null) return false;
        if (content.equals(current.content())) return true;
        String key = sha256(pathUnderOutput + '\u0000' + content);
        Boolean prior = contested.get(key);
        String reason;
        boolean ok;
        if (prior == null) {
            Optional<String> missing = missingQuotes(current.claim() == null ? current.requirement() : current.claim(), content);
            boolean onSpike = false;
            if (missing.isEmpty() && bench != null) {
                for (Path spike : keptSpikes()) {
                    if (Boolean.TRUE.equals(bench.run(spike, Map.of(pathUnderOutput, content)).get(pathUnderOutput))) {
                        onSpike = true;
                        break;
                    }
                }
            }
            Review review = missing.isEmpty() && onSpike
                    ? verdict(ask(REVIEW_SYSTEM, "REQUIREMENT:\n" + (current.claim() == null ? current.requirement()
                    : current.claim()) + "\n\nSPECIFICATION:\n" + read(SEED_PATH) + "\n\nTEST:\n" + content, "derived-test-review"))
                    : new Review(false, missing.orElse("it passes on no throwaway implementation of the wording"));
            ok = review.pass();
            reason = review.reason();
            contested.put(key, ok);
        } else {
            ok = prior;
            reason = ok ? "accepted earlier" : "the same text was judged earlier and not accepted";
        }
        synchronized (notes) {
            notes.add(ok ? "Your corrected version of " + pathUnderOutput + " was accepted: " + reason
                    : "Your corrected version of " + pathUnderOutput + " was NOT accepted, and the test stands as written: "
                    + reason);
        }
        if (!ok) {
            log.warn("T6-U: {} proposed a new text for {} -- not accepted: {}", writer, pathUnderOutput, reason);
            return false;
        }
        entries.replaceAll(e -> e.accepted() && pathUnderOutput.equals(e.path()) ? new Entry(e.requirement(), e.claim(),
                e.path(), content, Status.ACCEPTED, "rewritten by " + writer + ": " + reason) : e);
        try {
            DerivedTestsFile.write(files, entries);
        } catch (Exception e) {
            log.warn("T6-U: could not record the accepted new text of {}: {}", pathUnderOutput, e.getMessage());
            return false;
        }
        log.warn("T6-U: {} proposed a new text for {} -- accepted: {}", writer, pathUnderOutput, reason);
        return true;
    }

    private List<Path> keptSpikes() {
        List<Path> out = new ArrayList<>();
        try {
            Path dir = files.resolveSecurePath(SPIKES_DIR);
            if (!Files.isDirectory(dir)) return out;
            try (var list = Files.list(dir)) {
                for (Path p : list.sorted().toList()) {
                    // the spikes and the repaired implementations: a recovered claim passes only on the latter
                    if (Files.isDirectory(p) && !p.getFileName().toString().endsWith("-previous")
                            && bench.compiles(p) == null) out.add(p);
                }
            }
        } catch (Exception ignored) {
            // a spike that cannot be read checks nothing
        }
        return out;
    }

    @Override
    public void restore() {
        int written = 0;
        for (Entry e : DerivedTestsFile.read(files)) {
            if (!e.accepted() || e.path() == null) continue;
            String rel = "output/" + e.path();
            try {
                if (files.exists(rel) && e.content().equals(files.readFile(rel))) continue;
                files.writeFile(rel, e.content());
                written++;
            } catch (Exception ex) {
                log.warn("T6-U: could not write {}: {}", rel, ex.getMessage());
            }
        }
        if (written > 0) log.info("T6-U: wrote {} test(s) written from the requirements back into the tree", written);
    }

    @Override
    public String drainNotes() {
        synchronized (notes) {
            if (notes.isEmpty()) return null;
            String out = String.join("\n", notes);
            notes.clear();
            return out;
        }
    }

    /** Every claim's test and how it fared, appended -- the record a replay of the check is scored on. */
    static final String ATTEMPTS_PATH = "meta/derived-tests-attempts.ndjson";

    private void recordAttempt(Claim claim, String path, String content, boolean accepted, String reason) {
        try {
            var line = JSON.writeValueAsString(Map.of("requirement", claim.requirement(), "claim", claim.text(),
                    "source", claim.source(), "path", path, "content", content, "accepted", accepted, "reason", reason));
            Path f = files.resolveSecurePath(ATTEMPTS_PATH);
            Files.createDirectories(f.getParent());
            Files.writeString(f, line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("T6-U: could not record a claim's test: {}", e.getMessage());
        }
    }

    /** The main sources' declarations without their bodies: how the code is called, never what it does. */
    private String outline() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> f : sources(MAIN_ROOT).entrySet()) {
            String o = outline(f.getValue());
            if (o != null) sb.append("--- ").append(f.getKey()).append(" ---\n").append(capped(o)).append('\n');
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    public static String outline(String source) {
        CompilationUnit unit = JavaSources.parser().parse(source).getResult().orElse(null);
        if (unit == null) return null;
        unit.findAll(MethodDeclaration.class).forEach(m -> m.getBody().ifPresent(b -> m.setBody(new BlockStmt())));
        unit.findAll(ConstructorDeclaration.class).forEach(c -> c.setBody(new BlockStmt()));
        unit.findAll(InitializerDeclaration.class).forEach(i -> i.setBody(new BlockStmt()));
        return unit.toString();
    }

    private String listing(Map<String, String> sources) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> f : sources.entrySet()) {
            sb.append("--- ").append(f.getKey()).append(" ---\n").append(capped(f.getValue())).append('\n');
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    /** The text files under {@code under} in the tree, the derived tests left out. */
    private Map<String, String> sources(String under) {
        Map<String, String> out = new java.util.TreeMap<>();
        try {
            Path root = files.resolveSecurePath("output");
            Path dir = root.resolve(under);
            if (!Files.isDirectory(dir)) return out;
            Set<String> derived = new java.util.HashSet<>();
            for (Entry e : DerivedTestsFile.read(files)) if (e.path() != null) derived.add(e.path());
            try (var walk = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    if (derived.contains(rel) || !isText(p)) continue;
                    out.put(rel, Files.readString(p));
                }
            }
        } catch (Exception e) {
            log.warn("T6-U: could not read {} for the requirements' tests: {}", under, e.getMessage());
        }
        return out;
    }

    private static boolean isText(Path p) {
        try {
            String type = Files.probeContentType(p);
            if (type != null) return type.startsWith("text/");
            byte[] head = Files.readAllBytes(p);
            for (int i = 0; i < Math.min(head.length, 2048); i++) if (head[i] == 0) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String capped(String text) {
        return text.length() <= fileContentBudgetChars ? text
                : text.substring(0, fileContentBudgetChars) + "\n... (the rest of this file is left out)";
    }

    private String ask(String system, String user, String purposeName) {
        ResponseEntity<ChatResponse> resp;
        try (var purpose = LlmCallContext.scopeIfUnscoped(purposeName)) {
            resp = llmClient.ask(List.of(new Message("system", system), new Message("user", user)));
        }
        return resp == null || resp.getBody() == null ? "" : resp.getBody().getFirstContent();
    }

    private String read(String path) {
        try {
            return files.exists(path) ? files.readFile(path) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void copyTree(Path from, Path to) throws java.io.IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = from.relativize(p);
                if (rel.getNameCount() > 0 && rel.getName(0).toString().equals("target")) continue;
                Path t = to.resolve(rel.toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    private static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String firstLine(String text) {
        for (String l : text.split("\\R")) if (!l.isBlank()) return l.strip();
        return "";
    }

    private static String fileName(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static String extOf(String path) {
        String f = fileName(path);
        int dot = f.lastIndexOf('.');
        return dot < 0 ? "" : f.substring(dot);
    }

    /** The loop's own build executor and test command, run in a spike's tree; reports read as the test gate reads them. */
    static final class BuildBench implements Bench {
        private final BuildExecutor builds;
        private final String command;

        BuildBench(BuildExecutor builds, String command) {
            this.builds = builds;
            this.command = command;
        }

        @Override
        public String compiles(Path tree) {
            long start = System.currentTimeMillis();
            BuildOutcome out = builds.executeIn(tree.toString(), "derived-tests", command);
            if (TestRunGate.reportOf(tree, start).isPresent()) return null;
            if (out.errorFiles().isEmpty() && out.result() != com.walpro.autobuilder.core.build.BuildResult.COMPILE_ERROR) {
                return null;
            }
            return out.stderrTail() == null || out.stderrTail().isBlank() ? "the build failed" : out.stderrTail();
        }

        @Override
        public Map<String, Boolean> run(Path tree, Map<String, String> tests) {
            Map<String, Boolean> result = new LinkedHashMap<>();
            Map<String, String> left = new LinkedHashMap<>(tests);
            try {
                for (Map.Entry<String, String> t : left.entrySet()) {
                    Path p = tree.resolve(t.getKey());
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, t.getValue());
                }
                // A test that does not compile takes the run down with it: drop it (it failed here), and run the rest.
                for (int round = 0; round <= tests.size() && !left.isEmpty(); round++) {
                    long start = System.currentTimeMillis();
                    BuildOutcome out = builds.executeIn(tree.toString(), "derived-tests", command);
                    Optional<TestRunReport> report = TestRunGate.reportOf(tree, start);
                    if (report.isPresent()) {
                        for (String path : left.keySet()) result.put(path, passed(report.get(), stem(fileName(path))));
                        left.clear();
                        break;
                    }
                    List<String> broken = left.keySet().stream()
                            .filter(path -> out.errorFiles().stream().anyMatch(f -> f.replace('\\', '/').endsWith(path)))
                            .toList();
                    if (broken.isEmpty()) break;    // the implementation itself does not build: nothing passes here
                    for (String path : broken) {
                        result.put(path, false);
                        left.remove(path);
                        Files.deleteIfExists(tree.resolve(path));
                    }
                }
            } catch (Exception e) {
                log.warn("T6-U: could not run the requirements' tests on {}: {}", tree, e.getMessage());
            } finally {
                for (String path : tests.keySet()) {
                    try {
                        Files.deleteIfExists(tree.resolve(path));
                    } catch (Exception ignored) {
                        // a spike keeps no claim test between runs
                    }
                }
            }
            for (String path : tests.keySet()) result.putIfAbsent(path, false);
            return result;
        }

        @Override
        public String failures(Path tree, Map<String, String> tests, Set<String> only) {
            StringBuilder sb = new StringBuilder();
            try {
                for (Map.Entry<String, String> t : tests.entrySet()) {
                    Path p = tree.resolve(t.getKey());
                    Files.createDirectories(p.getParent());
                    Files.writeString(p, t.getValue());
                }
                long start = System.currentTimeMillis();
                builds.executeIn(tree.toString(), "derived-tests", command);
                Optional<TestRunReport> report = TestRunGate.reportOf(tree, start);
                Set<String> names = new LinkedHashSet<>();
                for (String p : only) names.add(stem(fileName(p)));
                if (report.isPresent()) {
                    for (TestRunReport.Failure f : report.get().failing()) {
                        String cls = f.className().substring(f.className().lastIndexOf('.') + 1);
                        if (!names.contains(cls)) continue;
                        sb.append(cls).append('.').append(f.method()).append(": ")
                                .append(f.message() == null ? f.outcome() : f.message().strip()).append('\n');
                    }
                }
            } catch (Exception e) {
                log.warn("T6-U: could not read the failures on {}: {}", tree, e.getMessage());
            } finally {
                for (String path : tests.keySet()) {
                    try {
                        Files.deleteIfExists(tree.resolve(path));
                    } catch (Exception ignored) {
                        // a tree keeps no claim test between runs
                    }
                }
            }
            String out = sb.toString();
            return out.length() > 4000 ? out.substring(0, 4000) : out;
        }

        @Override
        public String report(Path tree, String path, String content) {
            Path p = tree.resolve(path);
            try {
                Files.createDirectories(p.getParent());
                Files.writeString(p, content);
                long start = System.currentTimeMillis();
                BuildOutcome out = builds.executeIn(tree.toString(), "derived-tests", command);
                Optional<TestRunReport> report = TestRunGate.reportOf(tree, start);
                StringBuilder sb = new StringBuilder();
                if (report.isPresent()) {
                    String name = stem(fileName(path));
                    for (TestRunReport.Failure f : report.get().failing()) {
                        if (!f.className().equals(name) && !f.className().endsWith("." + name)) continue;
                        sb.append(name).append('.').append(f.method()).append(": ")
                                .append(f.message() == null ? f.outcome() : f.message().strip()).append('\n');
                    }
                } else {
                    // It did not compile: the compiler's lines about this file, and the two that follow each
                    // ("symbol:", "location:").
                    String[] lines = out.stderrTail() == null ? new String[0] : out.stderrTail().split("\n");
                    String file = fileName(path);
                    for (int i = 0; i < lines.length; i++) {
                        if (!lines[i].contains(file)) continue;
                        for (int j = i; j < Math.min(lines.length, i + 3); j++) sb.append(lines[j].strip()).append('\n');
                    }
                    if (sb.length() == 0 && out.stderrTail() != null) sb.append(out.stderrTail());
                }
                String s = sb.toString();
                return s.length() > 3000 ? s.substring(0, 3000) : s;
            } catch (Exception e) {
                log.warn("T6-U: could not read the report of {} on {}: {}", path, tree, e.getMessage());
                return "";
            } finally {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // a tree keeps no claim test between runs
                }
            }
        }

        private static boolean passed(TestRunReport report, String simpleName) {
            for (Map.Entry<String, List<TestRunReport.Case>> c : report.byClass().entrySet()) {
                if (!c.getKey().equals(simpleName) && !c.getKey().endsWith("." + simpleName)) continue;
                return !c.getValue().isEmpty() && c.getValue().stream().allMatch(x -> x.outcome() == TestRunReport.Outcome.PASSED);
            }
            return false;
        }

    }
}
