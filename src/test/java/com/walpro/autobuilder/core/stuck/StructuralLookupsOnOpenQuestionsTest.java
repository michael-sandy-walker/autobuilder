package com.walpro.autobuilder.core.stuck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.build.BuildOutcome;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.GrindDetector;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DY: the two structural lookups the user chose over wiring the terminal agent (2026-09-26), measured
 * on the six stuck questions the router had left open (src/test/resources/t4dz, bar in its README,
 * committed before these lookups were built). Each question goes through the REAL detector and layer:
 * the run's compiler block, the failed change's files, the adopted tree. What counts is the FIRST answer
 * the ladder delivers for the signature -- the last question shows the declaring file whole, which
 * would contain every one of these facts and prove nothing about the lookups.
 */
class StructuralLookupsOnOpenQuestionsTest {

    private static final Path FIXTURES = Path.of("src/test/resources/t4dz");

    @TempDir
    Path root;

    private static Map<String, String> files(Path dir) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> w = Files.walk(dir)) {
            for (Path f : (Iterable<Path>) w.filter(p -> p.toString().endsWith(".java"))::iterator) {
                out.put(dir.relativize(f).toString().replace('\\', '/'), Files.readString(f));
            }
        }
        return out;
    }

    /** rung of the first answer delivered, and that answer. */
    private String[] firstAnswer(String question) throws Exception {
        Path q = FIXTURES.resolve(question);
        JsonNode meta = new ObjectMapper().readTree(q.resolve("question.json").toFile());
        Map<String, String> adopted = files(q.resolve("tree"));
        for (var e : adopted.entrySet()) {
            Path b = root.resolve("meta/adoption-baseline/" + e.getKey());
            Files.createDirectories(b.getParent());
            Files.writeString(b, e.getValue());
        }
        StuckLayer layer = new StuckLayer();
        ReflectionTestUtils.setField(layer, "fileService", new FileService(root.toString(), new ObjectMapper()));
        GrindDetector detector = new GrindDetector();
        ReflectionTestUtils.setField(detector, "stuckLayer", layer);
        BuildOutcome failed = new BuildOutcome(BuildResult.COMPILE_ERROR, Files.readString(q.resolve("COMPILER_OUTPUT.txt")),
                1L, "mvn", "replay");
        Map<String, String> candidate = files(q.resolve("candidate"));
        for (String c : new String[]{"c1", "c2", "c3"}) {
            layer.beginCycle(c, "sg");
            detector.beginCycle(c);
            detector.observeBuild(c, failed, candidate);
        }
        layer.beginCycle("c4", "sg");
        layer.promptBlock(CycleContextData.builder().cycleName("c4").currentSubGoalId("sg").previousOutput(adopted).build());
        for (StuckLedger.Entry e : layer.ledger().entries()) {
            if (!e.signature().equals(meta.get("signature").asText())) continue;
            for (StuckLedger.Question asked : e.asked()) {
                if (asked.answer() != null) return new String[]{String.valueOf(asked.rung()), asked.answer()};
            }
            return new String[]{"-", null};
        }
        throw new AssertionError("the detector did not produce the fixture's signature: " + meta.get("signature").asText()
                + " -- got " + layer.ledger().entries().stream().map(StuckLedger.Entry::signature).toList());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "q2-list-to-int             | 1 | declared as adopted as: TickResult(ParticipationWindows windows, List<ParticipationWindow> expired)",
            "q3-inference-bounds        | 1 | declared as adopted as: TickResult(ParticipationWindows windows, List<ParticipationWindow> expired)",
            "q4-list-ambiguous-class    | 0 | imports java.util.List as adopted -- that is the one meant",
            "q5-list-ambiguous-variable | 0 | imports java.util.List as adopted -- that is the one meant",
            "q6-list-ambiguous-record   | 0 | imports java.util.List as adopted -- that is the one meant"})
    void theFirstAnswerStatesTheFact(String question, String rung, String fact) throws Exception {
        String[] first = firstAnswer(question);
        System.out.println("T4-DY lookups | " + question + " | rung " + first[0] + " | " + first[1]);
        assertThat(first[0]).isEqualTo(rung);
        assertThat(first[1]).contains(fact);
        if (question.startsWith("q2") || question.startsWith("q3")) {
            assertThat(first[1]).as("the change's differing declaration beside it").contains("The change redeclared it as: TickResult(");
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("q1-indexOf-on-map")
    void neitherLookupCoversAMethodCalledOnTheWrongKindOfCollection(String question) throws Exception {
        String[] first = firstAnswer(question);
        System.out.println("T4-DY lookups | " + question + " | rung " + first[0] + " | "
                + (first[1] == null ? null : first[1].substring(0, Math.min(120, first[1].length()))));
        assertThat(first[0]).as("only the whole declaring file, the last question").isEqualTo("2");
    }
}
