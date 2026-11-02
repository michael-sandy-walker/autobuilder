package com.walpro.autobuilder.goal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.build.DerivedTestsFile;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T6-U: the tests a change is held to -- each requirement split into atomic claims, one test per claim written from
 * the wording, kept only when it passes on a throwaway implementation written from the same wording. Invented
 * coordinates; the model and the builds are stubbed, so what is tested is the routing, not a model.
 */
class DerivedTestsTest {

    private static final String SEED = """
              An existing project, adopted, and asked to change. A refused edit names its reason.

              OBJECTIVE: edits are applied as above, and the whole suite passes.
            """;
    private static final String REQUIREMENT = "A piece that occurs more than once is refused with \"occurs N times\".";
    private static final String TEST_DIR = "src/test/java/org/ink";

    @TempDir
    Path base;

    private FileService files;
    /** Answers per prompt kind, in order. */
    private final Map<String, Deque<String>> answers = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    private LLMClient llm;
    /** What the stub bench does: which spike trees compile, and which test texts pass there. */
    private final Deque<String> compileResults = new ArrayDeque<>();
    private final Map<String, Boolean> passesWhenContains = new LinkedHashMap<>();
    /** Test texts that pass only on a repaired implementation, and ones that fail there. */
    private final List<String> passesOnRepairOnly = new ArrayList<>();
    private final List<String> failsOnRepair = new ArrayList<>();
    /** Where the stub bench ran which tests: tree name -> test paths. */
    private final Map<String, List<String>> ran = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        files = new FileService(base.toString(), new ObjectMapper());
        files.writeFile("bootstrap/seed.txt", SEED);
        obligations(REQUIREMENT);
        files.writeFile("output/src/main/java/org/ink/Quill.java",
                "package org.ink;\npublic class Quill {\n  public int ink() { return 1 + 1; }\n}\n");
        files.writeFile("output/" + TEST_DIR + "/QuillTest.java", "package org.ink;\nclass QuillTest {}\n");
        llm = mock(LLMClient.class);
        when(llm.ask(any())).thenAnswer(inv -> {
            List<Message> m = inv.getArgument(0);
            String system = m.get(0).getContent();
            asked.add(m.get(1).getContent());
            String kind = system.equals(DerivedTests.DECOMPOSE_SYSTEM) ? "claims"
                    : system.equals(DerivedTests.WRITE_SYSTEM) ? "write"
                    : system.equals(DerivedTests.REWRITE_SYSTEM) ? "rewrite"
                    : system.equals(DerivedTests.SPIKE_SYSTEM) ? "spike"
                    : system.equals(DerivedTests.REPAIR_SYSTEM) ? "repair" : "review";
            Deque<String> q = answers.getOrDefault(kind, new ArrayDeque<>());
            ChatResponse body = mock(ChatResponse.class);
            when(body.getFirstContent()).thenReturn(q.isEmpty() ? "" : q.poll());
            return ResponseEntity.ok(body);
        });
    }

    private void obligations(String... derived) throws Exception {
        List<String> lines = new ArrayList<>(List.of("rule: Keep the project's naming."));
        for (String d : derived) lines.add(SeedRequirements.GATE_MARKER + " " + SeedRequirements.GATE_DERIVED + " " + d);
        files.writeFile(SeedObligationsFile.PATH, SeedObligationsFile.render(SEED, lines));
    }

    private void answer(String kind, String... texts) {
        answers.computeIfAbsent(kind, k -> new ArrayDeque<>()).addAll(List.of(texts));
    }

    private static String test(int n, String body) {
        String name = String.format("DerivedClaim%03dTest", n);
        return "```java [" + TEST_DIR + "/" + name + ".java]\npackage org.ink;\nclass " + name + " {\n" + body + "\n}\n```\n";
    }

    /** T4-EC slice 1: the specification comes from the project's purpose, its seed recorded as a root sets it. */
    private static com.walpro.autobuilder.goal.purpose.PurposeService seeded(FileService f) {
        var p = com.walpro.autobuilder.goal.purpose.PurposeService.fileOnly(f);
        p.recordSeed(SEED, "test");
        return p;
    }

    private static final String SPIKE = "```java [src/main/java/org/ink/Quill.java]\npackage org.ink;\npublic class Quill {}\n```\n";

    private DerivedTests derived() {
        DerivedTests d = new DerivedTests(llm, files);
        d.usePurpose(seeded(files));
        d.setBench(new DerivedTests.Bench() {
            public String compiles(Path tree) {
                if (compileResults.isEmpty()) return null;
                String r = compileResults.poll();
                return r.isEmpty() ? null : r;
            }

            public Map<String, Boolean> run(Path tree, Map<String, String> tests) {
                boolean repaired = tree.getFileName().toString().startsWith("repair-");
                ran.computeIfAbsent(tree.getFileName().toString(), k -> new ArrayList<>()).addAll(tests.keySet());
                Map<String, Boolean> out = new LinkedHashMap<>();
                tests.forEach((p, t) -> out.put(p, repaired && failsOnRepair.stream().anyMatch(t::contains) ? false
                        : repaired && passesOnRepairOnly.stream().anyMatch(t::contains) ? true
                        : passesWhenContains.entrySet().stream().anyMatch(e -> t.contains(e.getKey()) && e.getValue())));
                return out;
            }

            public String failures(Path tree, Map<String, String> tests, java.util.Set<String> only) {
                return "DerivedClaim002Test.it: expected Map but was empty";
            }

            public String report(Path tree, String path, String content) {
                return "DerivedClaim002Test.it: no edit was applied -- the block has no closing fence";
            }
        });
        return d;
    }

    @Test
    void eachClaimGetsATestAndOnlyOnesPassingOnAThrowawayImplementationAreFrozen() throws Exception {
        answer("claims", """
                Here they are:
                [{"claim": "two matches are refused", "source": "STATED", "words": "refused"},
                 {"claim": "the reason says occurs 2 times", "source": "STATED", "words": "occurs N times"},
                 {"claim": "what a third hunk does", "source": "OPEN", "words": ""}]""");
        answer("write", test(1, "  // RIGHT refused"), test(2, "  // WRONG reason"));
        answer("spike", SPIKE, SPIKE, SPIKE);
        passesWhenContains.put("RIGHT", true);
        DerivedTests derived = derived();

        derived.ensureNow(SEED);

        List<DerivedTestsFile.Entry> entries = DerivedTestsFile.read(files);
        assertThat(entries).as("the OPEN claim gets no test").hasSize(2);
        assertThat(entries.get(0).accepted()).isTrue();
        assertThat(entries.get(0).claim()).isEqualTo("two matches are refused");
        assertThat(entries.get(0).reason()).isEqualTo("passed on 1 of 1 throwaway implementation(s)");
        assertThat(entries.get(1).accepted()).as("fails on every throwaway implementation").isFalse();
        assertThat(Files.readString(base.resolve("output/" + TEST_DIR + "/DerivedClaim001Test.java"))).contains("RIGHT");
        assertThat(Files.exists(base.resolve("output/" + TEST_DIR + "/DerivedClaim002Test.java"))).isFalse();
        assertThat(derived.acceptedRequirements()).as("no accepted test uses the quoted \"occurs N times\": a reading")
                .isEmpty();
        assertThat(derived.settled()).isFalse();
        assertThat(asked.stream().filter(a -> a.contains("THE PROJECT'S SOURCES AS THEY ARE NOW")))
                .as("the throwaway and repaired implementations never see a claim's test source")
                .noneMatch(a -> a.contains("// RIGHT refused") || a.contains("// WRONG reason"));
        assertThat(asked.stream().filter(a -> a.startsWith("CLAIM")))
                .as("a claim's test sees the declarations, never what the code does").noneMatch(a -> a.contains("1 + 1"));
    }

    @Test
    void aRequirementWhoseQuotedTextAnAcceptedTestUsesIsTheTestGatesAndSettled() throws Exception {
        answer("claims", "[{\"claim\": \"refused\", \"source\": \"STATED\", \"words\": \"refused\"}]");
        answer("write", test(1, "  // RIGHT occurs 2 times"));
        answer("spike", SPIKE, SPIKE, SPIKE, SPIKE);
        compileResults.addAll(List.of("Quill.java:[3,1] cannot find symbol", ""));   // spike 1: broken, then fixed
        passesWhenContains.put("RIGHT", true);
        DerivedTests derived = derived();

        derived.ensureNow(SEED);

        assertThat(derived.acceptedRequirements()).containsExactly(REQUIREMENT);
        assertThat(derived.settled()).isTrue();
        assertThat(asked).as("a spike that does not compile is asked again with the compiler's words")
                .anyMatch(a -> a.contains("IT DID NOT COMPILE") && a.contains("cannot find symbol"));
    }

    @Test
    void withoutAnyCompilingThrowawayImplementationNothingIsFrozen() throws Exception {
        answer("claims", "[{\"claim\": \"refused\", \"source\": \"STATED\", \"words\": \"refused\"}]");
        answer("write", test(1, "  // RIGHT"));
        answer("spike", SPIKE, SPIKE, SPIKE, SPIKE, SPIKE, SPIKE);
        for (int i = 0; i < 6; i++) compileResults.add("does not compile");
        passesWhenContains.put("RIGHT", true);

        derived().ensureNow(SEED);

        assertThat(DerivedTestsFile.read(files)).singleElement().satisfies(e -> {
            assertThat(e.accepted()).isFalse();
            assertThat(e.reason()).contains("no throwaway implementation compiled");
        });
    }

    @Test
    void writtenTestsAreKeptAcrossARestartAndPutBackWhenTheTreeLosesThem() throws Exception {
        answer("claims", "[{\"claim\": \"refused\", \"source\": \"STATED\", \"words\": \"refused\"}]");
        answer("write", test(1, "  // RIGHT"));
        answer("spike", SPIKE, SPIKE, SPIKE);
        passesWhenContains.put("RIGHT", true);
        derived().ensureNow(SEED);
        Path written = base.resolve("output/" + TEST_DIR + "/DerivedClaim001Test.java");
        String accepted = Files.readString(written);
        int calls = asked.size();

        DerivedTests restarted = derived();
        restarted.ensureNow(SEED);
        assertThat(asked).as("a restart asks nothing again").hasSize(calls);

        Files.delete(written);
        restarted.restore();
        assertThat(Files.readString(written)).isEqualTo(accepted);
    }

    @Test
    void aWritersCorrectionMustKeepTheQuotedWordsPassAThrowawayImplementationAndPassAReading() throws Exception {
        obligations("A piece that occurs twice is refused.");
        answer("claims", "[{\"claim\": \"the reason says \\\"occurs 2 times\\\"\", \"source\": \"STATED\", \"words\": \"x\"}]");
        answer("write", test(1, "  // RIGHT occurs 2 times"));
        answer("spike", SPIKE, SPIKE, SPIKE);
        passesWhenContains.put("RIGHT", true);
        DerivedTests derived = derived();
        derived.ensureNow(SEED);
        String path = TEST_DIR + "/DerivedClaim001Test.java";
        String accepted = DerivedTestsFile.read(files).get(0).content();

        assertThat(derived.contest("evolution", path, accepted)).isTrue();
        assertThat(derived.contest("evolution", path, "class DerivedClaim001Test { /* RIGHT */ }"))
                .as("drops the quoted words").isFalse();
        assertThat(derived.drainNotes()).contains("NOT accepted", "occurs 2 times");
        assertThat(derived.contest("evolution", path, "class DerivedClaim001Test { /* occurs 2 times, nowhere */ }"))
                .as("passes on no throwaway implementation").isFalse();

        String corrected = "class DerivedClaim001Test { /* RIGHT, occurs 2 times, other input */ }";
        answer("review", "VERDICT: PASS\nThe count is still checked.");
        assertThat(derived.contest("evolution", path, corrected)).isTrue();
        assertThat(DerivedTestsFile.read(files).get(0).content()).isEqualTo(corrected);
    }

    @Test
    void aTargetMarkingNoLineIsSettledAndNothingIsAsked() throws Exception {
        obligations();
        DerivedTests derived = derived();
        derived.ensureNow(SEED);
        assertThat(derived.settled()).isTrue();
        assertThat(asked).isEmpty();
    }

    @Test
    void theClaimsAreReadFromAListInTheAnswer() {
        assertThat(DerivedTests.jsonList("Sure! [{\"claim\": \"a\", \"source\": \"STATED\"}] Hope this helps [x]"))
                .singleElement().satisfies(m -> assertThat(m).containsEntry("claim", "a"));
        assertThat(DerivedTests.jsonList("no list here")).isEmpty();
    }

    /** An edit form's sources write fenced examples inside their own strings; a header starts at column 0. */
    @Test
    void aThrowawayImplementationsFilesAreItsColumnZeroBlocksOnly() {
        String answer = "```java [src/main/java/org/ink/A.java]\nclass A {\n  String s = \"\"\"\n"
                + "      ```java [src/main/java/org/ink/Fake.java]\n      ```\n      \"\"\";\n}\n```\n"
                + "```java [src/main/java/org/ink/B.java]\nclass B {}\n```\n";
        Map<String, String> got = DerivedTests.filesOf(answer);
        assertThat(got).containsOnlyKeys("src/main/java/org/ink/A.java", "src/main/java/org/ink/B.java");
        assertThat(got.get("src/main/java/org/ink/A.java")).contains("Fake.java").endsWith("}\n");
    }

    @Test
    void aQuotedTextIsFoundThroughTheTestsEscapesAndSpacing() {
        assertThat(DerivedTests.missingQuotes("Names it as \"Type.name(String)\" or \"\\w+\".",
                "assertThat(a).isEqualTo(\"Type.name(String)\"); assertThat(p).isEqualTo(\"\\\\w+\");")).isEmpty();
        assertThat(DerivedTests.missingQuotes("Refused with `no such   file`.", "contains(\"no such file\")")).isEmpty();
        assertThat(DerivedTests.missingQuotes("Refused with \"hunk 2\".", "contains(\"hunk\")")).isPresent();
        assertThat(DerivedTests.missingQuotes("Refused with \"occurs N times\".", "contains(\"occurs 2 times\")"))
                .as("a capital letter standing alone is a placeholder").isEmpty();
        assertThat(DerivedTests.missingQuotes("Answered as \"Map\".", "isEqualTo(\"Mop\")")).isPresent();
    }

    @Test
    void aReviewWithoutAVerdictNeverPasses() {
        assertThat(DerivedTests.verdict("VERDICT: PASS\nok").pass()).isTrue();
        assertThat(DerivedTests.verdict("VERDICT: FAIL\nmissing the Map form").reason()).isEqualTo("missing the Map form");
        assertThat(DerivedTests.verdict("Looks good to me.").pass()).isFalse();
        assertThat(DerivedTests.verdict(null).pass()).isFalse();
    }

    @Test
    void theOutlineKeepsHowTheCodeIsCalledAndLeavesOutWhatItDoes() {
        String outline = DerivedTests.outline("package org.ink;\n/** Counts ink. */\npublic class Quill {\n"
                + "  public Quill(int n) { this.n = n * 2; }\n  private final int n;\n"
                + "  public int ink(String colour) { return n + colour.length(); }\n}\n");
        assertThat(outline).contains("Counts ink.", "public Quill(int n)", "public int ink(String colour)")
                .doesNotContain("n * 2", "colour.length()");
    }

    @Test
    void aBareOrHeadedBlockIsTheTestAndRunsToTheLastFence() {
        var tests = new java.util.TreeMap<String, String>(Map.of(
                "src/test/java/org/ink/QuillTest.java", "package org.ink;\nclass QuillTest {}",
                "src/test/java/org/well/WellTest.java", "package org.well;\nclass WellTest {}"));
        assertThat(DerivedTests.located("```java\npackage org.well;\nclass DerivedClaim001Test {}\n```\n",
                "DerivedClaim001Test", tests)).get()
                .satisfies(f -> assertThat(f.path()).isEqualTo("src/test/java/org/well/DerivedClaim001Test.java"));
        String body = "package org.ink;\nclass DerivedClaim001Test {\n  String a = \"\"\"\n      ```edit [x]\n      ```\n      \"\"\";\n}\n";
        assertThat(DerivedTests.located("```java [src/test/java/org/ink/DerivedClaim001Test.java]\n" + body + "```\n",
                "DerivedClaim001Test", tests)).get().satisfies(f -> assertThat(f.content()).isEqualTo(body));
        assertThat(DerivedTests.located("```java\nclass Other {}\n```", "DerivedClaim001Test", tests)).isEmpty();
        assertThat(DerivedTests.header("DerivedClaim001Test", List.copyOf(tests.keySet())))
                .isEqualTo("```java [src/test/java/org/ink/DerivedClaim001Test.java]");
    }

    /** field108, edit-hunks: 206 claims, 143 distinct -- the same claim under several requirements is written once. */
    @Test
    void aClaimRepeatedUnderAnotherRequirementIsWrittenOnceAndAnswersBoth() throws Exception {
        String other = "A piece that occurs twice leaves the file out.";
        obligations(REQUIREMENT, other);
        answer("claims", "[{\"claim\": \"Two matches are refused.\", \"source\": \"STATED\", \"words\": \"x\"}]",
                "[{\"claim\": \"two matches are refused\", \"source\": \"STATED\", \"words\": \"y\"}]");
        answer("write", test(1, "  // RIGHT occurs 2 times"));
        answer("spike", SPIKE, SPIKE, SPIKE);
        passesWhenContains.put("RIGHT", true);
        DerivedTests derived = derived();

        derived.ensureNow(SEED);

        assertThat(asked.stream().filter(a -> a.startsWith("CLAIM"))).as("written once").hasSize(1);
        assertThat(DerivedTestsFile.read(files)).hasSize(2)
                .allSatisfy(e -> assertThat(e.path()).isEqualTo(TEST_DIR + "/DerivedClaim001Test.java"));
        assertThat(derived.acceptedRequirements()).containsExactlyInAnyOrder(REQUIREMENT, other);
    }

    /** A run of the same case with the same wording and starting tree reuses the writing instead of repeating it. */
    @Test
    void theSameWordingAndStartingTreeReuseAKeptWritingAndAChangedTreeDoesNot(@TempDir Path cache, @TempDir Path base2)
            throws Exception {
        answer("claims", "[{\"claim\": \"refused\", \"source\": \"STATED\", \"words\": \"refused\"}]");
        answer("write", test(1, "  // RIGHT occurs 2 times"));
        answer("spike", SPIKE, SPIKE, SPIKE);
        passesWhenContains.put("RIGHT", true);
        DerivedTests first = derived();
        first.setCacheDir(cache.toString());
        first.ensureNow(SEED);
        int calls = asked.size();

        // The next run: a fresh target directory holding the same seed, obligations and starting tree.
        FileService next = new FileService(base2.toString(), new ObjectMapper());
        for (String f : List.of("bootstrap/seed.txt", SeedObligationsFile.PATH, "output/src/main/java/org/ink/Quill.java",
                "output/" + TEST_DIR + "/QuillTest.java")) {
            next.writeFile(f, files.readFile(f));
        }
        DerivedTests again = new DerivedTests(llm, next);
        again.usePurpose(seeded(next));
        again.setCacheDir(cache.toString());
        again.ensureNow(SEED);
        assertThat(asked).as("nothing asked again").hasSize(calls);
        assertThat(Files.readString(base2.resolve("output/" + TEST_DIR + "/DerivedClaim001Test.java"))).contains("RIGHT");
        assertThat(again.acceptedRequirements()).containsExactly(REQUIREMENT);

        // A different starting tree is a different case: written again.
        Files.delete(base2.resolve("output/" + TEST_DIR + "/DerivedClaim001Test.java"));
        next.writeFile(DerivedTestsFile.PATH, "[]");
        java.nio.file.Files.delete(base2.resolve(DerivedTestsFile.PATH));
        next.writeFile("output/src/main/java/org/ink/Quill.java", "package org.ink;\npublic class Quill { int other; }\n");
        DerivedTests changed = new DerivedTests(llm, next);
        changed.usePurpose(seeded(next));
        changed.setCacheDir(cache.toString());
        changed.ensureNow(SEED);
        assertThat(asked.size()).as("a changed tree is written again").isGreaterThan(calls);
    }

    /** field108/109: the record-component claims failed on every spike -- the spikes share the loop's blind spot. */
    @Test
    void aClaimEverySpikeFailsIsKeptWhenARepairedImplementationPassesItBesideEveryAcceptedTest() throws Exception {
        answer("claims", "[{\"claim\": \"refused\", \"source\": \"STATED\", \"words\": \"a\"},"
                + " {\"claim\": \"the blind form\", \"source\": \"STATED\", \"words\": \"b\"}]");
        answer("write", test(1, "  // RIGHT occurs 2 times"), test(2, "  // BLIND"));
        answer("spike", SPIKE, SPIKE, SPIKE);
        answer("repair", SPIKE, SPIKE);
        passesWhenContains.put("RIGHT", true);
        passesOnRepairOnly.add("BLIND");
        DerivedTests derived = derived();

        derived.ensureNow(SEED);

        List<DerivedTestsFile.Entry> entries = DerivedTestsFile.read(files);
        assertThat(entries).allSatisfy(e -> assertThat(e.accepted()).isTrue());
        assertThat(entries.get(1).reason()).contains("passed on a repaired one beside every accepted test");
        assertThat(derived.acceptedRequirements()).containsExactly(REQUIREMENT);
        assertThat(asked).as("the repair is told the dropped claim, never its test")
                .anyMatch(a -> a.contains("CLAIMS EARLIER IMPLEMENTATIONS DID NOT MEET") && a.contains("the blind form")
                        && !a.contains("BLIND"));
    }

    /** A repair that meets the dropped claim by breaking an accepted one recovers nothing. */
    @Test
    void aRepairThatBreaksAnAcceptedTestRecoversNothing() throws Exception {
        answer("claims", "[{\"claim\": \"refused\", \"source\": \"STATED\", \"words\": \"a\"},"
                + " {\"claim\": \"contradicts it\", \"source\": \"STATED\", \"words\": \"b\"}]");
        answer("write", test(1, "  // RIGHT"), test(2, "  // CONTRADICTS"));
        answer("spike", SPIKE, SPIKE, SPIKE);
        answer("repair", SPIKE, SPIKE, SPIKE, SPIKE, SPIKE, SPIKE);
        passesWhenContains.put("RIGHT", true);
        passesOnRepairOnly.add("CONTRADICTS");
        failsOnRepair.add("RIGHT");

        derived().ensureNow(SEED);

        List<DerivedTestsFile.Entry> entries = DerivedTestsFile.read(files);
        assertThat(entries.get(0).accepted()).isTrue();
        assertThat(entries.get(1).accepted()).isFalse();
    }

    /** 2026-10-27: a correctly dropped bad test no longer makes a well-tested requirement a reading; a quoted input does. */
    @Test
    void aRequirementIsTheGatesWhenAnAcceptedTestUsesEveryTextItQuotes() {
        assertThat(DerivedTests.answeredBy(REQUIREMENT, List.of("assertThat(r).contains(\"occurs 2 times\")"))).isTrue();
        assertThat(DerivedTests.answeredBy(REQUIREMENT, List.of("assertThat(r).isRefused()")))
                .as("no accepted test uses the quoted text").isFalse();
        assertThat(DerivedTests.answeredBy("Hunks are applied in order.", List.of("any test"))).isTrue();
        assertThat(DerivedTests.answeredBy("Hunks are applied in order.", List.of())).as("no accepted test").isFalse();
    }

    /** edit-hunks' "occurs N times": every writing's test of it was broken (no closing fence), never the claim. */
    @Test
    void aTestEverySpikeFailsIsRewrittenOnceFromTheRunnersReportAndKeptWhenItThenPasses() throws Exception {
        answer("claims", "[{\"claim\": \"the reason says occurs 2 times\", \"source\": \"STATED\", \"words\": \"x\"}]");
        answer("write", test(1, "  // BROKEN occurs 2 times"));
        answer("rewrite", test(1, "  // RIGHT occurs 2 times"));
        answer("spike", SPIKE, SPIKE, SPIKE);
        passesWhenContains.put("RIGHT", true);
        DerivedTests derived = derived();

        derived.ensureNow(SEED);

        DerivedTestsFile.Entry e = DerivedTestsFile.read(files).get(0);
        assertThat(e.accepted()).isTrue();
        assertThat(e.content()).contains("RIGHT").doesNotContain("BROKEN");
        assertThat(e.reason()).contains("rewritten once from the runner's report, then passed on 1 of 1");
        assertThat(derived.acceptedRequirements()).containsExactly(REQUIREMENT);
        assertThat(asked).as("the rewrite sees its earlier test and what the run reported")
                .anyMatch(a -> a.contains("YOUR EARLIER TEST") && a.contains("BROKEN") && a.contains("no closing fence"));
    }

    @Test
    void aRewriteThatLeavesOutTheClaimsQuotedWordsIsNotUsed() throws Exception {
        answer("claims", "[{\"claim\": \"the reason says \\\"occurs 2 times\\\"\", \"source\": \"STATED\", \"words\": \"x\"}]");
        answer("write", test(1, "  // BROKEN occurs 2 times"));
        answer("rewrite", test(1, "  // RIGHT but weaker"));
        answer("spike", SPIKE, SPIKE, SPIKE);
        passesWhenContains.put("RIGHT", true);

        derived().ensureNow(SEED);

        DerivedTestsFile.Entry e = DerivedTestsFile.read(files).get(0);
        assertThat(e.accepted()).isFalse();
        assertThat(e.content()).contains("BROKEN");
    }

    /** 2026-10-27: three spike answers wrote no file ("I'll start by exploring..."); the unchanged tree compiled. */
    @Test
    void aThrowawayImplementationThatChangesNoSourceIsAskedAgainThenDropped() throws Exception {
        answer("claims", "[{\"claim\": \"refused\", \"source\": \"STATED\", \"words\": \"refused\"}]");
        answer("write", test(1, "  // RIGHT occurs 2 times"));
        answer("spike", "I'll start by exploring the project structure.", SPIKE);   // no file, then a change
        passesWhenContains.put("RIGHT", true);

        derived().ensureNow(SEED);

        assertThat(DerivedTestsFile.read(files).get(0).reason()).isEqualTo("passed on 1 of 1 throwaway implementation(s)");
        assertThat(asked).anyMatch(a -> a.contains("YOUR ANSWER CHANGED NO SOURCE FILE"));
    }

    @Test
    void aThrowawayImplementationThatChangesNoSourceTwiceIsDropped() throws Exception {
        answer("claims", "[{\"claim\": \"refused\", \"source\": \"STATED\", \"words\": \"refused\"}]");
        answer("write", test(1, "  // RIGHT occurs 2 times"));
        answer("spike", "Let me look first.", "Still looking.");
        passesWhenContains.put("RIGHT", true);

        derived().ensureNow(SEED);

        assertThat(DerivedTestsFile.read(files)).singleElement().satisfies(e -> {
            assertThat(e.accepted()).isFalse();
            assertThat(e.reason()).contains("no throwaway implementation compiled");
        });
    }

    /**
     * T1-GG: at a completion point, the dropped tests of a requirement no kept test answers run on the project's own
     * code, which never saw them; one passing there is accepted and written into the tree, one failing stays dropped,
     * and an answered requirement's dropped tests are not run.
     */
    @Test
    void aDroppedTestOfAnOpenRequirementPassingOnTheProjectsOwnCodeIsAccepted() throws Exception {
        String other = "A refused edit is left out of the result.";
        obligations(REQUIREMENT, other);
        String right = "package org.ink;\nclass DerivedClaim001Test { // RIGHT occurs 2 times\n}\n";
        String wrong = "package org.ink;\nclass DerivedClaim002Test { // WRONG occurs 2 times\n}\n";
        String kept = "package org.ink;\nclass DerivedClaim003Test { // RIGHT left out\n}\n";
        String spare = "package org.ink;\nclass DerivedClaim004Test { // RIGHT spare\n}\n";
        DerivedTestsFile.write(files, List.of(
                new DerivedTestsFile.Entry(REQUIREMENT, "two matches say occurs 2 times", TEST_DIR + "/DerivedClaim001Test.java",
                        right, DerivedTestsFile.Status.UNACCEPTED, "passed on 0 of 3 throwaway implementation(s)"),
                new DerivedTestsFile.Entry(REQUIREMENT, "three matches say occurs 3 times", TEST_DIR + "/DerivedClaim002Test.java",
                        wrong, DerivedTestsFile.Status.UNACCEPTED, "passed on 0 of 3 throwaway implementation(s)"),
                new DerivedTestsFile.Entry(other, "left out", TEST_DIR + "/DerivedClaim003Test.java",
                        kept, DerivedTestsFile.Status.ACCEPTED, "passed on 3 of 3 throwaway implementation(s)"),
                new DerivedTestsFile.Entry(other, "spare", TEST_DIR + "/DerivedClaim004Test.java",
                        spare, DerivedTestsFile.Status.UNACCEPTED, "passed on 0 of 3 throwaway implementation(s)")));
        passesWhenContains.put("RIGHT", true);
        DerivedTests derived = derived();
        assertThat(derived.acceptedRequirements()).containsExactly(other);

        assertThat(derived.acceptOnProjectCode()).containsExactly(TEST_DIR + "/DerivedClaim001Test.java");

        assertThat(ran).as("run on the project's tree only, and only the open requirement's dropped tests")
                .containsOnlyKeys("output");
        assertThat(ran.get("output")).containsExactly(TEST_DIR + "/DerivedClaim001Test.java", TEST_DIR + "/DerivedClaim002Test.java");
        assertThat(derived.acceptedRequirements()).containsExactlyInAnyOrder(REQUIREMENT, other);
        assertThat(DerivedTestsFile.read(files)).filteredOn(e -> e.path().endsWith("DerivedClaim001Test.java")).singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo(DerivedTests.ACCEPTED_ON_PROJECT));
        assertThat(DerivedTestsFile.read(files)).filteredOn(e -> e.path().endsWith("DerivedClaim002Test.java")).singleElement()
                .satisfies(e -> assertThat(e.accepted()).isFalse());
        assertThat(Files.readString(base.resolve("output/" + TEST_DIR + "/DerivedClaim001Test.java"))).isEqualTo(right);
        assertThat(Files.exists(base.resolve("output/" + TEST_DIR + "/DerivedClaim002Test.java"))).isFalse();

        ran.clear();
        assertThat(derived.acceptOnProjectCode()).as("nothing is open any more: nothing runs").isEmpty();
        assertThat(ran).isEmpty();
    }

    /** T1-GG: a dropped test is never run over a file of the project's own under its name -- the bench deletes what it ran. */
    @Test
    void aDroppedTestWhosePathTheProjectAlreadyHoldsIsNotRun() throws Exception {
        String test = "package org.ink;\nclass DerivedClaim001Test { // RIGHT occurs 2 times\n}\n";
        DerivedTestsFile.write(files, List.of(new DerivedTestsFile.Entry(REQUIREMENT, "two matches say occurs 2 times",
                TEST_DIR + "/DerivedClaim001Test.java", test, DerivedTestsFile.Status.UNACCEPTED, "passed on 0 of 3")));
        files.writeFile("output/" + TEST_DIR + "/DerivedClaim001Test.java", "package org.ink;\nclass DerivedClaim001Test {}\n");
        passesWhenContains.put("RIGHT", true);

        assertThat(derived().acceptOnProjectCode()).isEmpty();
        assertThat(ran).isEmpty();
    }
}
