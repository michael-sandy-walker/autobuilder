package com.walpro.autobuilder.core.build;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * T4-EK: the tests that show what a project the loop did not build was found to do -- kept beside its criteria in
 * {@code meta/claim-tests/}, not in the project's own code.
 *
 * <p>Reverse engineering adds context to a project; it does not add to its code (the user's principle, 2026-11-10).
 * A library's kept claims are tests, and a test has to sit in the tree to be run -- so each is put there for the
 * length of one test run and taken out again ({@link #place}). The project's sources read the same before and after.
 *
 * <p>Each file is kept under the path it takes below the tree's test sources ({@code org/x/EarnedK1Test.java}).
 */
@Slf4j
@Component
public class ClaimTests {

    static final String TEST_SOURCES = "src/test/java";

    private final Path dir;

    public ClaimTests(@Value("${file.base-path}") String fileBasePath) {
        this.dir = Path.of(fileBasePath, "meta", "claim-tests");
    }

    /** Keeps one test under {@code relativePath} (below the test sources), replacing an earlier one of that path. */
    public void write(String relativePath, String source) throws IOException {
        Path file = dir.resolve(relativePath).normalize();
        if (!file.startsWith(dir)) throw new IllegalArgumentException(relativePath + " is not a path below the test sources");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    /** The kept tests: path below the test sources -> source. Empty when the project has none. */
    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.put(dir.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        } catch (IOException e) {
            log.warn("T4-EK: the claim tests in {} could not be read -- {}", dir, e.getMessage());
        }
        return out;
    }

    /**
     * Puts every kept test into {@code tree} for one test run; closing takes them out again, with the directories that
     * were made for them. A file of the project's own at a test's path is left as it is and that test is not placed
     * (said in the log) -- the project's code is never overwritten. A test left behind by a run that was cut short is
     * the same text at the same path, so it is taken as placed and removed with the rest.
     */
    public Placed place(Path tree) {
        Map<String, String> tests = all();
        List<Path> files = new ArrayList<>(), made = new ArrayList<>();
        Map<String, String> byClass = new LinkedHashMap<>();
        for (Map.Entry<String, String> t : tests.entrySet()) {
            Path target = tree.resolve(TEST_SOURCES).resolve(t.getKey());
            try {
                if (Files.exists(target) && !Files.readString(target).equals(t.getValue())) {
                    log.warn("T4-EK: {} is the project's own file -- the claim test of that path is not run", target);
                    continue;
                }
                for (Path d = target.getParent(); d != null && !Files.exists(d); d = d.getParent()) made.add(d);
                Files.createDirectories(target.getParent());
                Files.writeString(target, t.getValue());
                files.add(target);
                String name = target.getFileName().toString();
                byClass.put(name.endsWith(".java") ? name.substring(0, name.length() - 5) : name, t.getValue());
            } catch (IOException e) {
                log.warn("T4-EK: claim test {} could not be placed -- {}", target, e.getMessage());
            }
        }
        return new Placed(files, made, byClass);
    }

    /** The tests placed for one run. */
    public static final class Placed implements AutoCloseable {
        static final Placed NONE = new Placed(List.of(), List.of(), Map.of());

        private final List<Path> files;
        private final List<Path> made;
        private final Map<String, String> byClass;

        private Placed(List<Path> files, List<Path> made, Map<String, String> byClass) {
            this.files = files;
            this.made = made;
            this.byClass = byClass;
        }

        public static Placed none() { return NONE; }

        /** The source of the placed test whose class is named {@code simpleName}; null when none is. */
        public String source(String simpleName) {
            return byClass.get(simpleName);
        }

        public boolean isEmpty() { return files.isEmpty(); }

        @Override
        public void close() {
            for (Path f : files) {
                try {
                    Files.deleteIfExists(f);
                } catch (IOException e) {
                    log.warn("T4-EK: claim test {} could not be taken out of the tree -- {}", f, e.getMessage());
                }
            }
            // Deepest first; a directory something else has written into since stays.
            made.stream().sorted(Comparator.comparingInt(Path::getNameCount).reversed()).forEach(d -> {
                try (Stream<Path> inside = Files.list(d)) {
                    if (inside.findAny().isEmpty()) Files.delete(d);
                } catch (IOException e) {
                    // not empty or already gone: left
                }
            });
        }
    }
}
