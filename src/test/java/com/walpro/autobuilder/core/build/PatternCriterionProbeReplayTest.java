package com.walpro.autobuilder.core.build;

import com.walpro.autobuilder.client.LLMClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.Mockito.mock;

/**
 * T3-CB replay: the labelled pattern criteria (seed-status/pattern-criteria.tsv, exported from the
 * graph by scripts/pattern_criteria_corpus.py) probed the way the closure edge probes them, against
 * a booted artifact that is known to be correct. Reports, per criterion, whether a probe was built
 * and what the artifact answered; a probed criterion whose answer differs from its assertion is a
 * CONTRADICTION -- against a correct artifact, a false accusation.
 *
 * <p>An instrument, not a gate: it runs only with {@code -Dt3cb.replay.tree=<artifact tree>} (the
 * tree holding {@code target/*.jar} and the sources the synthesizer derives bodies from), and prints
 * its table. Compare two builds by running it on each.
 */
class PatternCriterionProbeReplayTest {

    @Test
    void replayThePatternCriteriaAgainstACorrectArtifact() throws Exception {
        String treeProp = System.getProperty("t3cb.replay.tree");
        assumeThat(treeProp).as("set -Dt3cb.replay.tree to run the replay").isNotBlank();
        Path tree = Path.of(treeProp);
        Path jar;
        try (var s = Files.list(tree.resolve("target"))) {
            jar = s.filter(p -> p.toString().endsWith(".jar")).findFirst().orElseThrow();
        }
        String seed = resource("library-seed.txt");
        int port = 18000 + (int) (ProcessHandle.current().pid() % 1000);
        // One boot serves every criterion, as at the closure edge; with -Dt3cb.replay.fresh=true each
        // criterion gets its own boot, so a setup refused by state an EARLIER probe left behind (a
        // uniqueness rule answering 409 to the same derived body) cannot hide the criterion's answer.
        boolean fresh = Boolean.getBoolean("t3cb.replay.fresh");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        CriterionProbeSynthesizer synthesizer = new CriterionProbeSynthesizer(mock(LLMClient.class));
        Map<String, Integer> tally = new TreeMap<>();
        Process app = null;
        try {
            for (String line : resource("pattern-criteria.tsv").lines().filter(l -> !l.isBlank()).toList()) {
                String[] f = line.split("\t", 4);
                String label = f[0] + " " + f[1];
                String outcome;
                Optional<BehaviouralCriterionParser.Assertion> a = BehaviouralCriterionParser.parse(f[3]);
                if (a.isEmpty()) {
                    outcome = "unparsed";
                } else {
                    Optional<List<CriterionProbeSynthesizer.ProbeRequest>> seq = synthesizer.synthesize(seed, a.get(), tree);
                    if (seq.isEmpty()) {
                        outcome = "abstained";
                    } else {
                        if (app == null || fresh) {
                            if (app != null) { app.destroy(); app.waitFor(); }
                            app = boot(jar, port);
                            waitUntilServing(client, port);
                        }
                        List<String> trace = new ArrayList<>();
                        Integer got = execute(client, port, seq.get(), trace);
                        outcome = got == null ? "unobserved(" + String.join(" ; ", trace) + ")"
                                : got == a.get().expectedStatus() ? "confirmed" : "CONTRADICTED(" + got + ")";
                    }
                }
                tally.merge(label + " -> " + outcome.replaceAll("\\(.*", ""), 1, Integer::sum);
                System.out.println("T3CB-REPLAY\t" + label + "\t" + outcome + "\t" + f[3]);
            }
            tally.forEach((k, v) -> System.out.println("T3CB-TALLY\t" + k + "\t" + v));
        } finally {
            if (app != null) app.destroy();
        }
    }

    private static Process boot(Path jar, int port) throws IOException {
        return new ProcessBuilder("java", "-jar", jar.toString(), "--server.port=" + port)
                .redirectErrorStream(true)
                .redirectOutput(Path.of(System.getProperty("java.io.tmpdir"), "t3cb-replay-app.log").toFile())
                .start();
    }

    /** The boot gate's own sequence semantics: $N references resolved, a refused setup is unobserved. */
    private static Integer execute(HttpClient client, int port, List<CriterionProbeSynthesizer.ProbeRequest> sequence,
                                   List<String> trace) throws InterruptedException {
        List<String> bodies = new ArrayList<>();
        int status = -1;
        for (int i = 0; i < sequence.size(); i++) {
            var resolved = BootGate.resolveReferences(sequence.get(i), bodies);
            if (resolved.isEmpty()) {
                trace.add(sequence.get(i) + " unresolved");
                return null;
            }
            var r = resolved.get();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + r.path()))
                    .timeout(Duration.ofSeconds(5));
            if (r.body() == null || r.body().isBlank()) b.method(r.method(), HttpRequest.BodyPublishers.noBody());
            else b.header("Content-Type", "application/json")
                    .method(r.method(), HttpRequest.BodyPublishers.ofString(r.body(), StandardCharsets.UTF_8));
            try {
                HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                status = resp.statusCode();
                bodies.add(resp.body());
                trace.add(r + " -> " + status);
            } catch (IOException e) {
                return null;
            }
            if (i < sequence.size() - 1 && status >= 400) return null;
        }
        return status < 0 ? null : status;
    }

    private static void waitUntilServing(HttpClient client, int port) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            try {
                client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
                        HttpResponse.BodyHandlers.discarding());
                return;
            } catch (IOException e) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("the artifact did not serve on port " + port);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = PatternCriterionProbeReplayTest.class.getResourceAsStream("/seed-status/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
