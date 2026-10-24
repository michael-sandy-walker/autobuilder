package com.walpro.autobuilder.goal;

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
import com.walpro.autobuilder.core.build.DerivedTestAuthority;
import com.walpro.autobuilder.core.build.DerivedTestsFile;
import com.walpro.autobuilder.core.build.DerivedTestsFile.Entry;
import com.walpro.autobuilder.core.build.DerivedTestsFile.Status;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.protocol.evolution.EvolutionResponseParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T6-U: the tests a change is held to, written from the requirements' own wording BEFORE the code changes, then
 * frozen -- for a target whose specification tests are withheld from the loop (the user's choice of design,
 * 2026-10-23).
 *
 * <p><b>The defect this closes.</b> Batch field105 ran the band-1 ladder cases with their specification tests
 * withheld: 0 of 6 true completions against 6 of 6 with the tests given, five of them false completions. In none
 * was the code beyond the model; in every one a behaviour the seed states was never tested -- the Map receiver in
 * the compiler's own wording, {@code parse(String)} without the tree -- because the only tests were the loop's
 * own, written beside the code they were meant to check, or none at all. With tests in front of it the loop
 * finishes; these are those tests, and they cannot be bent to fit the code because no code existed when they were
 * written and no writer may change them except through {@link #contest}.
 *
 * <p><b>Which lines.</b> Only an obligation the operator marked {@code gate: derived-test} -- which gate answers
 * which line is the operator's declaration ({@link SeedRequirements#GATE_MARKER}), never inferred from wording.
 * Once accepted, its test is part of the suite, and closure takes the test gate's verdict for the line instead of
 * a model's reading of the code (field105: a model confirmed "the existing parse(String) applies no edit" from
 * code that does the opposite, twice).
 *
 * <p><b>The check against the wording</b> (the user, 2026-10-23: "include the check against the requirement's
 * wording"), structure first, a model last: every text the requirement quotes must appear in the test, then a
 * reader who sees the requirement, the specification and the test -- never the code -- says whether the test
 * checks what the requirement says, in every case and form it names. A test that fails either is written again,
 * with the reason, up to {@value #ATTEMPTS} times; a line still without one is left to the reading it had before,
 * and the objective is not "met by the project's own tests" while any such line is open.
 */
@Slf4j
@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(
        value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "the accepted tests live in meta/derived-tests.json; this holds only a review cache and unsent notes")
@ConditionalOnProperty(name = "autobuilder.obligations.derived-tests", havingValue = "true", matchIfMissing = true)
public class DerivedTests implements DerivedTestAuthority {

    /** Writings per requirement before it is left without a test: the reason goes back each time. */
    static final int ATTEMPTS = 3;

    static final String SEED_PATH = "bootstrap/seed.txt";
    static final String TEST_ROOT = "src/test/";

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]{3,})\"|`([^`]{3,})`");

    static final String DERIVE_SYSTEM = """
            You write ONE test class for ONE requirement of a change to an existing project. The code that meets the \
            requirement has not been written yet: write the test from the requirement's own words and the \
            specification, so that it fails until the code does what the requirement says and passes once it does.
            - Test every case and every form the requirement names, with the exact values, wordings and examples it \
            gives; where the specification quotes an example input for this requirement, use it as written.
            - Call the code only as the specification and the declarations shown say it is called.
            - Use the same language, test libraries and assertion style as the existing tests shown.
            - Test nothing the requirement does not say.
            Answer with the one file, in a fenced block headed by its path in brackets, under src/test/, its file \
            named exactly as given. No other files.""";

    static final String REVIEW_SYSTEM = """
            You check whether a test checks a requirement -- as the requirement is WORDED. You see the requirement, \
            the specification it comes from, and the test. You do not see the code, and must not guess at it.
            A test passes the check when it exercises every case and every form the requirement names, with the \
            values and wordings it gives, and asserts what the requirement says the result is. It fails the check \
            when it leaves out a named case or form, uses a different example or wording than the one given, \
            asserts something weaker than the requirement, or asserts something the requirement does not say.
            Answer with the first line exactly "VERDICT: PASS" or "VERDICT: FAIL", then one short paragraph naming \
            what is missing or wrong.""";

    private final LLMClient llmClient;
    private final FileService files;

    @Value("${autobuilder.evaluation.fileContentBudgetChars:40000}")
    private int fileContentBudgetChars = 40000;

    /** True while the requirements' tests are being written: no closure may rest on a suite that lacks them. */
    private volatile boolean pending;

    /** Review verdicts per proposed text, so a proposal reaching several writers is judged once. */
    private final Map<String, Review> reviewed = new ConcurrentHashMap<>();

    private final List<String> notes = new ArrayList<>();

    public DerivedTests(LLMClient llmClient, FileService files) {
        this.llmClient = llmClient;
        this.files = files;
    }

    /** What the wording check concluded, and why. */
    record Review(boolean pass, String reason) {}

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

    /** The requirements whose test was accepted -- the ones the test gate now answers. */
    public Set<String> acceptedRequirements() {
        Set<String> out = new LinkedHashSet<>();
        for (Entry e : DerivedTestsFile.read(files)) if (e.accepted()) out.add(e.requirement());
        return out;
    }

    /** True when nothing is being written and every marked requirement has an accepted test. */
    public boolean settled() {
        if (pending) return false;
        return acceptedRequirements().containsAll(requirements());
    }

    /**
     * Write and check the test of every marked requirement that has none accepted. Called where the root goal is
     * set, beside the obligations file it reads; an accepted test is kept across restarts and never rewritten.
     */
    public void ensureFor(String seedText) {
        if (seedText == null || seedText.isBlank()) return;
        List<String> lines = requirements(seedText);
        if (lines.isEmpty()) return;
        pending = true;
        try {
            List<Entry> entries = new ArrayList<>(DerivedTestsFile.read(files));
            entries.removeIf(e -> !lines.contains(e.requirement()));
            String existing = existingTests();
            String outline = outline();
            Map<String, String> tests = sources(TEST_ROOT);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (entries.stream().anyMatch(e -> e.requirement().equals(line) && e.accepted())) continue;
                entries.removeIf(e -> e.requirement().equals(line));
                entries.add(derive(seedText, line, String.format("DerivedRequirement%02dTest", i + 1), existing, outline,
                        tests));
                DerivedTestsFile.write(files, entries);
            }
            long accepted = entries.stream().filter(Entry::accepted).count();
            log.warn("T6-U: {} of {} requirement(s) marked gate: {} have an accepted test written from their wording{}",
                    accepted, lines.size(), SeedRequirements.GATE_DERIVED,
                    accepted == lines.size() ? "" : " -- the rest are left to a reading, and the objective is not met"
                            + " by the project's own tests while they are open");
            restore();
        } catch (Exception e) {
            log.warn("T6-U: could not write the requirements' tests: {}", e.getMessage());
        } finally {
            pending = false;
        }
    }

    private Entry derive(String seedText, String requirement, String name, String existing, String outline,
                         Map<String, String> tests) {
        String header = header(name, List.copyOf(tests.keySet()));
        String feedback = null;
        String last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            StringBuilder user = new StringBuilder("REQUIREMENT:\n").append(requirement)
                    .append("\n\nSPECIFICATION:\n").append(seedText)
                    .append("\n\nTHE PROJECT'S DECLARATIONS AS THEY ARE NOW (bodies left out):\n").append(outline)
                    .append("\n\nTHE EXISTING TESTS:\n").append(existing)
                    .append("\n\nName the file ").append(name).append(", and the class in it ").append(name)
                    .append(". Open the block with its path in brackets, for example:\n").append(header);
            if (feedback != null) {
                user.append("\n\nAn earlier version was not accepted: ").append(feedback)
                        .append("\nThat version:\n").append(last);
            }
            String answer = ask(DERIVE_SYSTEM, user.toString(), "derived-test");
            Optional<FileMutation> file = located(answer, name, tests);
            if (file.isEmpty()) {
                feedback = "the answer held no file named " + name + " -- open its block with the path in brackets, as "
                        + header;
                last = answer;
                continue;
            }
            String path = file.get().path().replace('\\', '/');
            if (!path.startsWith(TEST_ROOT)) {
                feedback = "the file was not under " + TEST_ROOT + " (" + path + ")";
                last = file.get().content();
                continue;
            }
            Review review = review(seedText, requirement, name, file.get().content());
            log.info("T6-U: test for \"{}\" attempt {}: {} -- {}", requirement, attempt,
                    review.pass() ? "accepted" : "not accepted", review.reason());
            if (review.pass()) return new Entry(requirement, path, file.get().content(), Status.ACCEPTED, review.reason());
            feedback = review.reason();
            last = file.get().content();
        }
        return new Entry(requirement, null, last, Status.UNACCEPTED, feedback);
    }

    /** The opening of the block the model is asked for: beside the existing tests, under the test root. */
    static String header(String name, List<String> testPaths) {
        String dir = testPaths.isEmpty() ? TEST_ROOT.substring(0, TEST_ROOT.length() - 1) : dirOf(testPaths.get(0));
        String ext = testPaths.isEmpty() ? "" : extOf(testPaths.get(0));
        String lang = ext.isEmpty() ? "" : ext.substring(1);
        return "```" + lang + " [" + dir + "/" + name + ext + "]";
    }

    /**
     * The answer's file for {@code name}: a block headed by its path, or -- field106 run 1: the local model wrote all
     * eighteen of its answers as a bare fenced block -- the one block that declares {@code name}, placed beside the
     * existing test whose first line it shares (its package, in the projects seen so far), else beside the first.
     */
    static Optional<FileMutation> located(String answer, String name, Map<String, String> tests) {
        Optional<FileMutation> headed = new EvolutionResponseParser().parse(answer).addedOrChangedFiles().stream()
                .filter(f -> fileName(f.path()).startsWith(name + ".")).findFirst();
        if (headed.isPresent() || answer == null || tests.isEmpty()) return headed;
        Matcher block = Pattern.compile("```[^\\n]*\\n(.*?)```", Pattern.DOTALL).matcher(answer);
        List<String> declaring = new ArrayList<>();
        while (block.find()) if (block.group(1).contains(name)) declaring.add(block.group(1));
        if (declaring.size() != 1) return Optional.empty();
        String content = declaring.get(0);
        String first = firstLine(content);
        String beside = tests.entrySet().stream().filter(t -> firstLine(t.getValue()).equals(first))
                .map(Map.Entry::getKey).findFirst().orElse(tests.keySet().iterator().next());
        return Optional.of(new FileMutation(dirOf(beside) + "/" + name + extOf(beside), content));
    }

    private static String firstLine(String text) {
        for (String l : text.split("\\R")) if (!l.isBlank()) return l.strip();
        return "";
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String extOf(String path) {
        String f = fileName(path);
        int dot = f.lastIndexOf('.');
        return dot < 0 ? "" : f.substring(dot);
    }

    /** The wording check: every quoted text present, then a reader who sees no code. */
    Review review(String seedText, String requirement, String name, String test) {
        String key = sha256(requirement + '\u0000' + test);
        return reviewed.computeIfAbsent(key, k -> {
            Optional<String> missing = missingQuotes(requirement, test);
            if (missing.isPresent()) return new Review(false, missing.get());
            if (!test.contains(name)) return new Review(false, "the test does not declare " + name);
            String answer = ask(REVIEW_SYSTEM, "REQUIREMENT:\n" + requirement + "\n\nSPECIFICATION:\n" + seedText
                    + "\n\nTEST:\n" + test, "derived-test-review");
            return verdict(answer);
        });
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
     * A quoted text as a pattern: literal, except that a capital letter standing alone is a placeholder for one
     * value ("occurs N times" is written "occurs 2 times" by a test that checks it) -- the field105 seed's own
     * wording, and the way a specification writes a count or a name it does not fix.
     */
    static Pattern quotedPattern(String quoted) {
        StringBuilder re = new StringBuilder();
        Matcher placeholder = Pattern.compile("(?<![A-Za-z0-9_])[A-Z](?![A-Za-z0-9_])").matcher(comparable(quoted));
        int at = 0;
        String text = comparable(quoted);
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

    @Override
    public boolean contest(String writer, String pathUnderOutput, String content) {
        List<Entry> entries = new ArrayList<>(DerivedTestsFile.read(files));
        Entry current = entries.stream().filter(e -> e.accepted() && pathUnderOutput.equals(e.path())).findFirst().orElse(null);
        if (current == null) return false;
        if (content.equals(current.content())) return true;
        Review review = review(read(SEED_PATH), current.requirement(), stem(fileName(pathUnderOutput)), content);
        synchronized (notes) {
            if (review.pass()) {
                notes.add("Your corrected version of " + pathUnderOutput + " was accepted: " + review.reason());
            } else {
                notes.add("Your corrected version of " + pathUnderOutput + " was NOT accepted, and the test stands as"
                        + " written: " + review.reason());
            }
        }
        if (!review.pass()) {
            log.warn("T6-U: {} proposed a new text for {} -- not accepted: {}", writer, pathUnderOutput, review.reason());
            return false;
        }
        entries.replaceAll(e -> e == current ? new Entry(e.requirement(), e.path(), content, Status.ACCEPTED,
                "rewritten by " + writer + ": " + review.reason()) : e);
        try {
            DerivedTestsFile.write(files, entries);
        } catch (Exception e) {
            log.warn("T6-U: could not record the accepted new text of {}: {}", pathUnderOutput, e.getMessage());
            return false;
        }
        log.warn("T6-U: {} proposed a new text for {} -- accepted against its requirement's wording: {}",
                writer, pathUnderOutput, review.reason());
        return true;
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

    /** The tests already in the tree, each in full up to the per-file budget. */
    private String existingTests() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> f : sources(TEST_ROOT).entrySet()) {
            sb.append("--- ").append(f.getKey()).append(" ---\n").append(capped(f.getValue())).append('\n');
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    /** The main sources' declarations without their bodies: how the code is called, never what it does. */
    private String outline() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> f : sources("src/main/").entrySet()) {
            String o = outline(f.getValue());
            if (o != null) sb.append("--- ").append(f.getKey()).append(" ---\n").append(capped(o)).append('\n');
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    static String outline(String source) {
        CompilationUnit unit = JavaSources.parser().parse(source).getResult().orElse(null);
        if (unit == null) return null;
        unit.findAll(MethodDeclaration.class).forEach(m -> m.getBody().ifPresent(b -> m.setBody(new BlockStmt())));
        unit.findAll(ConstructorDeclaration.class).forEach(c -> c.setBody(new BlockStmt()));
        unit.findAll(InitializerDeclaration.class).forEach(i -> i.setBody(new BlockStmt()));
        return unit.toString();
    }

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
        return resp.getBody() == null ? "" : resp.getBody().getFirstContent();
    }

    private String read(String path) {
        try {
            return files.exists(path) ? files.readFile(path) : "";
        } catch (Exception e) {
            return "";
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

    private static String fileName(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
