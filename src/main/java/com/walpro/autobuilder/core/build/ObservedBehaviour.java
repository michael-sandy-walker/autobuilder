package com.walpro.autobuilder.core.build;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * T4-CQ: what the project's code was OBSERVED to return when a test asserted something else --
 * {@code expected: <CLASS> but was: <FILE>} -- kept for the run and shown to every evolution prompt.
 *
 * <p><b>Why a ledger.</b> The failure lines already reach the next prompt (the repair item and the
 * staged-test rejection relay them), but only the next one. structdiff run 2026-09-25_03-54-52
 * learned at 03:57 that the parser's root is a FILE, not a CLASS, and asserted CLASS again at 04:08
 * and at 04:13: an observed fact about the code, lost after one cycle and paid for again. Each
 * {@code but was} line is the cheapest authority there is on what the code does.
 *
 * <p><b>Facts expire with the code they describe.</b> Each entry carries a digest of the sources
 * under {@code src/main} at the moment it was observed, and is shown only while that digest is
 * current. On an adopted project whose library code is protected the facts hold for the whole run;
 * on a project the loop is still writing, an observation of code that has since changed is dropped
 * rather than shown as if it were still true.
 */
@Slf4j
@Component
@com.walpro.autobuilder.core.checkpoint.StateHolder(
        value = com.walpro.autobuilder.core.checkpoint.StateBucket.DERIVED_DO_NOT_RESTORE,
        note = "per-run observations, each keyed to the src/main digest it describes; a restored run observes afresh")
public class ObservedBehaviour {

    private static final int MAX_SHOWN = 12;
    private static final int MAX_MESSAGE = 200;

    /** message -> (where it was seen, the src/main digest it describes). Insertion order = first seen. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    record Entry(String where, String mainDigest) {}

    /** Record the value observations among {@code failing}, as of the code whose digest is {@code mainDigest}. */
    public synchronized void observe(List<TestRunReport.Failure> failing, String mainDigest) {
        if (failing == null || mainDigest == null) return;
        for (TestRunReport.Failure f : failing) {
            String msg = f.message() == null ? null : f.message().strip();
            if (msg == null || !msg.contains("but was")) continue;    // a value the code returned, nothing else
            if (msg.length() > MAX_MESSAGE) msg = msg.substring(0, MAX_MESSAGE) + "...";
            String cls = f.className() == null ? "" : f.className().substring(f.className().lastIndexOf('.') + 1);
            String where = cls + "." + f.method();
            Entry prior = entries.get(msg);
            if (prior == null || !prior.mainDigest().equals(mainDigest)) {
                entries.put(msg, new Entry(where, mainDigest));
                log.info("T4-CQ: observed behaviour -- {}: {}", where, msg);
            }
        }
    }

    /** Record one value observed directly (the value-observation probe), as of {@code mainDigest}. */
    public synchronized void observeValue(String where, String observation, String mainDigest) {
        if (observation == null || mainDigest == null) return;
        String msg = observation.length() > MAX_MESSAGE * 3 ? observation.substring(0, MAX_MESSAGE * 3) + "..." : observation;
        entries.put(msg, new Entry(where, mainDigest));
        log.info("T4-CQ: observed behaviour -- {}: {}", where, msg);
    }

    /** The block for an evolution prompt, or null when nothing observed still describes the code. */
    public synchronized String promptBlock(String currentMainDigest) {
        if (currentMainDigest == null) return null;
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (e.getValue().mainDigest().equals(currentMainDigest)) {
                lines.add("  - " + e.getValue().where() + ": " + e.getKey());
            }
        }
        if (lines.isEmpty()) return null;
        if (lines.size() > MAX_SHOWN) lines = lines.subList(lines.size() - MAX_SHOWN, lines.size());
        return "--- OBSERVED BEHAVIOUR (this project's tests, run against the code as it is now) ---\n"
                + "Each line is a value the code actually returned where a test expected another. These are\n"
                + "facts about the current code: assert the value after 'but was', never the expected one again.\n"
                + String.join("\n", lines);
    }

    /** Digest of every source under {@code src/main} in the project tree on disk. */
    public static String mainDigestOnDisk(Path projectRoot) {
        TreeMap<String, String> main = new TreeMap<>();
        Path src = projectRoot.resolve("src/main");
        if (!Files.isDirectory(src)) return digest(main);
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                main.put(projectRoot.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        } catch (Exception e) {
            return null;
        }
        return digest(main);
    }

    private static String digest(TreeMap<String, String> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : files.entrySet()) {
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.substring(0, 16);
        } catch (Exception e) {
            return null;
        }
    }
}
