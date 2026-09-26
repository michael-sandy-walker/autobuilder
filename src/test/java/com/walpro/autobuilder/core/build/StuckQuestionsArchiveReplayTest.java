package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * T4-DZ's corpus: every archived grind, re-derived through the REAL {@link GrindDetector#signaturesOf}
 * from the run's own compiler output, and put to the REAL {@link StuckQuestions} against the tree
 * that run left behind. Writes one TSV row per signature: answered, ambiguous or unanswered.
 *
 * <p>Off unless {@code -Dt4dz.archive=<run-archive dir>} is set, with {@code -Dt4dz.trees=<dir of kept
 * trees>} and {@code -Dt4dz.out=<tsv>}. A signature counts when its old-format projection (symbol
 * dropped, as the detector recorded it before 2026-09-26) was a {@code grind_detected} signature of
 * that run and it occurs in at least three compiler blocks.
 */
@EnabledIfSystemProperty(named = "t4dz.archive", matches = ".+")
class StuckQuestionsArchiveReplayTest {

    private static final Pattern ENTRY = Pattern.compile("^(?:\\[ERROR] )?/\\S+\\.java:\\[\\d+,\\d+]");
    private static final Pattern GRIND = Pattern.compile("\"signature\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void replay() throws Exception {
        Path archive = Path.of(System.getProperty("t4dz.archive"));
        Path trees = Path.of(System.getProperty("t4dz.trees"));
        Path out = Path.of(System.getProperty("t4dz.out"));
        List<String> rows = new ArrayList<>();
        rows.add("run\ttarget\tsignature\tblocks\tstatus\tkind\tdeclared");
        try (Stream<Path> runs = Files.list(archive).sorted()) {
            for (Path run : (Iterable<Path>) runs::iterator) {
                Path behaviour = run.resolve("behaviour.ndjson.gz");
                if (!Files.exists(behaviour)) continue;
                Set<String> grinds = new LinkedHashSet<>();
                String text = gunzip(behaviour);
                for (String chunk : text.split("\"event\"")) {
                    if (!chunk.contains("grind_detected")) continue;
                    Matcher m = GRIND.matcher(chunk);
                    if (m.find()) grinds.add(m.group(1).replace("\\\"", "\""));
                }
                if (grinds.isEmpty()) continue;
                String target = Files.readString(run.resolve("target.txt")).strip().replace("target=", "");
                Map<String, Integer> blocks = signatureBlocks(gunzip(run.resolve("autobuilder.log.gz")));
                Map<String, String> tree = treeOf(trees, target, run.getFileName().toString());
                for (Map.Entry<String, Integer> s : blocks.entrySet()) {
                    if (s.getValue() < 3 || !grinds.contains(oldFormat(s.getKey()))) continue;
                    Optional<StuckQuestions.Answer> a = StuckQuestions.answer(s.getKey(), tree);
                    String status = a.isEmpty() ? "UNANSWERED"
                            : a.get().kind() == StuckQuestions.Kind.SYMBOL && a.get().declared().size() > 1 ? "AMBIGUOUS"
                            : "ANSWERED";
                    rows.add(String.join("\t", run.getFileName().toString(), target, s.getKey(), String.valueOf(s.getValue()),
                            status, a.map(x -> x.kind().name()).orElse("-"),
                            a.map(x -> String.join(" | ", x.declared())).orElse("-")));
                }
                // grind signatures that are not compiler signatures (e.g. refused writes) pass through as-is
                for (String g : grinds) {
                    if (g.startsWith("the build fails:")) continue;
                    Optional<StuckQuestions.Answer> a = StuckQuestions.answer(g, tree);
                    rows.add(String.join("\t", run.getFileName().toString(), target, g, "-",
                            a.isEmpty() ? "UNANSWERED" : "ANSWERED", a.map(x -> x.kind().name()).orElse("-"),
                            a.map(x -> String.join(" | ", x.declared())).orElse("-")));
                }
            }
        }
        Files.write(out, rows);
    }

    /** The signature as the detector wrote it before it kept the symbol: "F.java: cannot find symbol". */
    static String oldFormat(String signature) {
        return signature.replaceAll("(: cannot find symbol) .*$", "$1");
    }

    /** Distinct signatures per maximal block of compiler error lines, counted in how many blocks they occur. */
    static Map<String, Integer> signatureBlocks(String log) {
        Map<String, Integer> count = new LinkedHashMap<>();
        List<String> block = new ArrayList<>();
        for (String line : log.split("\\R")) {
            boolean entry = ENTRY.matcher(line).find();
            boolean continuation = !block.isEmpty() && (line.startsWith(" ") || line.matches("^\\[ERROR]\\s{2,}.*"));
            if (entry || continuation) {
                block.add(line);
                continue;
            }
            flush(block, count);
        }
        flush(block, count);
        return count;
    }

    private static void flush(List<String> block, Map<String, Integer> count) {
        if (block.isEmpty()) return;
        String stderr = String.join("\n", block);
        block.clear();
        for (String s : GrindDetector.signaturesOf(new BuildOutcome(BuildResult.COMPILE_ERROR, stderr, 1L, "mvn", "replay"))) {
            count.merge(s, 1, Integer::sum);
        }
    }

    /** The run's kept tree (output/ plus the adoption baseline); every kept tree of the target when none names the run. */
    static Map<String, String> treeOf(Path trees, String target, String run) throws Exception {
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> s = Files.list(trees)) {
            s.filter(p -> p.getFileName().toString().startsWith(target + "-after-")).sorted().forEach(dirs::add);
        }
        List<Path> named = dirs.stream().filter(p -> p.getFileName().toString().contains(run)).toList();
        Map<String, String> files = new LinkedHashMap<>();
        for (Path d : named.isEmpty() ? dirs : named) {
            for (Path sub : List.of(d.resolve("meta/adoption-baseline"), d.resolve("output"))) {
                if (!Files.isDirectory(sub)) continue;
                try (Stream<Path> w = Files.walk(sub)) {
                    for (Path f : (Iterable<Path>) w.filter(p -> p.toString().endsWith(".java"))::iterator) {
                        files.putIfAbsent(sub.relativize(f).toString(), Files.readString(f));
                    }
                }
            }
        }
        return files;
    }

    private static String gunzip(Path gz) throws Exception {
        try (var in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(gz)), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) sb.append(buf, 0, n);
            return sb.toString();
        }
    }
}
