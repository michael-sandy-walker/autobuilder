package com.walpro.autobuilder.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * A real run's tree, frozen so a fix can be re-checked against the exact state that defeated it.
 *
 * <p><b>Why this exists.</b> A run that goes wrong is expensive to reproduce and impossible to
 * reproduce exactly: the model is sampled, the wall clock moves, and the next reset clears the
 * per-cycle copies. So the answer to "does the fix handle the case that beat us?" kept being a
 * fresh forty-five minute run whose failure, if it came, was a different one. A scenario replaces
 * that with a file: the tree as it actually stood, replayed against the deterministic machinery in
 * milliseconds, as many times as a fix needs.
 *
 * <p><b>What it can and cannot answer.</b> It answers everything the loop decides WITHOUT the
 * model: what the structural analyzers see, what the gates conclude, what a repair would plan,
 * which work a rule would raise. It does not answer what the model would write next. That is the
 * deliberate boundary — those are the decisions worth regression-testing, because they are the
 * ones that are supposed to be the same every time.
 *
 * <p>Freeze one with {@code scripts/freeze_scenario.sh}, whose usage text names the three sources
 * worth freezing from. The per-cycle archive is the interesting one: it is the only tree that
 * outlives the reset, so a cycle can still be frozen after the next run has begun.
 */
public final class Scenario {

    private static final String ROOT = "scenarios";

    private final String name;
    private final Map<String, String> files;
    private final Properties manifest;

    private Scenario(String name, Map<String, String> files, Properties manifest) {
        this.name = name;
        this.files = files;
        this.manifest = manifest;
    }

    /** Loads the named scenario, or fails the test with a message naming what is missing. */
    public static Scenario load(String name) {
        Path base = resourceDir(ROOT + "/" + name);
        Path tree = base.resolve("tree");
        if (!Files.isDirectory(tree)) {
            throw new AssertionError("scenario '" + name + "' has no tree/ directory at " + tree
                    + " — freeze one with scripts/freeze_scenario.sh");
        }
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(tree)) {
            walk.filter(Files::isRegularFile).sorted().forEach(p -> {
                try {
                    files.put(tree.relativize(p).toString().replace('\\', '/'), Files.readString(p));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Properties manifest = new Properties();
        Path manifestPath = base.resolve("manifest.properties");
        if (Files.isRegularFile(manifestPath)) {
            try (var in = Files.newInputStream(manifestPath)) {
                manifest.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Scenario(name, Map.copyOf(files), manifest);
    }

    /**
     * The tree as the analyzers take it: relative path to content. This is the same shape
     * {@code FileService.readFolderContentsResolved} hands the loop at merge time, so a rule reads
     * a scenario exactly as it reads the live tree.
     */
    public Map<String, String> files() {
        return files;
    }

    /** Just the Java sources, for the rules that only ever look at those. */
    public Map<String, String> javaFiles() {
        Map<String, String> out = new LinkedHashMap<>();
        files.forEach((path, content) -> {
            if (path.endsWith(".java")) out.put(path, content);
        });
        return out;
    }

    public String name() {
        return name;
    }

    /** What the freeze recorded about where this came from and what went wrong. */
    public String manifest(String key) {
        return manifest.getProperty(key);
    }

    /** The provenance line a failing assertion should carry, so the reader can find the run. */
    public String describe() {
        String note = manifest.getProperty("note");
        String run = manifest.getProperty("run_dir");
        return "scenario '" + name + "'"
                + (run != null ? " from " + run : "")
                + (note != null ? " — " + note : "");
    }

    private static Path resourceDir(String path) {
        URL url = Scenario.class.getClassLoader().getResource(path);
        if (url == null) {
            throw new AssertionError("no scenario resources at " + path
                    + " — freeze one with scripts/freeze_scenario.sh");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError("scenario path is not a readable directory: " + url, e);
        }
    }
}
