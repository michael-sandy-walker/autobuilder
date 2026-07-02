package com.walpro.autobuilder.protocol.evolution;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EvolutionResponseParser {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```\\w+ \\[(.+?)\\](.+?)```");
    private static final Pattern DELETE_PATTERN = Pattern.compile("#Delete \\[(.+?)]");

    // T4-BU: test sources the model writes under the MAIN source root are un-mergeable — the
    // test-framework symbols are test-scoped, so main compilation hard-fails and the probe
    // rejects the whole mutation (implementation included); the diagnostic feedback then keeps
    // the model re-emitting the same file (2026-07-03 run 2: 240 stagings of one misplaced test,
    // merge rate collapsed). Relocate them to the test source root at mutation birth — the one
    // home every consumer (probe, merge, mutation-dir mirror) reads. Self-gating: the
    // `src/main/java` prefix in the model's own path IS the layout marker, and the content must
    // import a test framework before we touch it. Framework packages are data rows (T4-BM
    // discovery territory), not logic.
    private static final Pattern MISPLACED_TEST_PATH =
            Pattern.compile("^src/main/java/(.+(?:Test|Tests|IT))\\.java$");
    private static final Pattern TEST_FRAMEWORK_IMPORT = Pattern.compile(
            "import\\s+(?:static\\s+)?(?:org\\.junit|org\\.mockito|org\\.testng"
                    + "|org\\.springframework\\.test|org\\.springframework\\.boot\\.test)\\.");

    public EvolutionOutcome parse(String content) {
        // T2-CC: LLM declared the implementation is already complete
        if (content != null && content.lines().anyMatch(line -> line.trim().equals("#NoDiff"))) {
            log.info("Evolution response contains #NoDiff — treating as no-op (implementation already present).");
            return new EvolutionOutcome(List.of(), List.of(), "#NoDiff — implementation appears complete", content, false, true);
        }

        List<FileMutation> changedFiles = new ArrayList<>();
        List<String> deletedFiles = new ArrayList<>();

        Matcher codeMatcher = CODE_BLOCK_PATTERN.matcher(content);
        while (codeMatcher.find()) {
            String path = codeMatcher.group(1).trim();
            String fileContent = codeMatcher.group(2).trim();
            if (!looksLikeFilePath(path)) {
                log.warn("Skipping mutation with directory-like path (no file extension): [{}]", path);
                continue;
            }
            changedFiles.add(new FileMutation(relocateMisplacedTestSource(path, fileContent), fileContent));
        }

        Matcher deleteMatcher = DELETE_PATTERN.matcher(content);
        while (deleteMatcher.find()) {
            deletedFiles.add(deleteMatcher.group(1).trim());
        }

        String summary = "Parsed %d changed file(s), %d deleted file(s)."
                .formatted(changedFiles.size(), deletedFiles.size());

        return new EvolutionOutcome(changedFiles, deletedFiles, summary, content);
    }

    /**
     * T4-BU: a test-suffixed file under the main source root whose content imports a test
     * framework is moved to the test source root (identical package path — both roots map
     * package to directory the same way). Anything that fails either gate is left untouched:
     * a main class that merely ends in "Test" keeps its path.
     */
    private static String relocateMisplacedTestSource(String path, String content) {
        Matcher m = MISPLACED_TEST_PATH.matcher(path);
        if (!m.matches() || content == null || !TEST_FRAMEWORK_IMPORT.matcher(content).find()) {
            return path;
        }
        String relocated = "src/test/java/" + m.group(1) + ".java";
        log.info("T4-BU: relocating misplaced test source {} -> {} (test-framework imports under the main source root)",
                path, relocated);
        return relocated;
    }

    /** Returns false for paths that look like package/directory paths (no file extension in the last segment). */
    private boolean looksLikeFilePath(String path) {
        String lastSegment = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return lastSegment.contains(".");
    }
}
