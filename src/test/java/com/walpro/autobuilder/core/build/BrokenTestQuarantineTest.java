package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4-BN-3 slice 2: the quarantine acts only from the second consecutive blocked closure visit
 * (the injected work item gets one repair round), moves — never deletes — only real files under
 * the working dir's src/test tree, and preserves them under quarantine/ for inspection.
 */
class BrokenTestQuarantineTest {

    @TempDir Path tmp;

    private static final String BROKEN_TEST_REL =
            "src/test/java/com/example/demo/controller/LoanControllerTest.java";

    private BuildExecutor buildExecutor;
    private BrokenTestQuarantine quarantine;
    private Path output;

    @BeforeEach
    void setUp() throws IOException {
        buildExecutor = mock(BuildExecutor.class);
        output = tmp.resolve("output");
        when(buildExecutor.resolveWorkingDir(tmp.toString())).thenReturn(output.toString());
        quarantine = new BrokenTestQuarantine(true, buildExecutor);
        ReflectionTestUtils.setField(quarantine, "fileBasePath", tmp.toString());
        Files.createDirectories(output.resolve(BROKEN_TEST_REL).getParent());
        Files.writeString(output.resolve(BROKEN_TEST_REL), "class LoanControllerTest {}");
    }

    private String failureNaming(String relPath) {
        // The shape a package-gate summary carries: absolute diagnostic path + error text.
        return "[ERROR] " + output.resolve(relPath) + ":[51,66] cannot find symbol";
    }

    @Test
    void firstBlockedVisit_isTheRepairRound_movesNothing() {
        List<String> moved = quarantine.recordBlockedVisit("root", failureNaming(BROKEN_TEST_REL), "cycle-1");

        assertThat(moved).isEmpty();
        assertThat(output.resolve(BROKEN_TEST_REL)).exists();
    }

    @Test
    void secondBlockedVisit_movesTheNamedFile_preservedUnderQuarantine() {
        quarantine.recordBlockedVisit("root", failureNaming(BROKEN_TEST_REL), "cycle-1");
        List<String> moved = quarantine.recordBlockedVisit("root", failureNaming(BROKEN_TEST_REL), "cycle-2");

        assertThat(moved).containsExactly(BROKEN_TEST_REL);
        assertThat(output.resolve(BROKEN_TEST_REL)).doesNotExist();
        Path preserved = tmp.resolve("quarantine").resolve("cycle-2").resolve(BROKEN_TEST_REL);
        assertThat(preserved).exists();
        assertThat(preserved).hasContent("class LoanControllerTest {}");
    }

    @Test
    void resetRestoresTheRepairRound() {
        quarantine.recordBlockedVisit("root", failureNaming(BROKEN_TEST_REL), "cycle-1");
        quarantine.reset("root");
        List<String> moved = quarantine.recordBlockedVisit("root", failureNaming(BROKEN_TEST_REL), "cycle-2");

        assertThat(moved).isEmpty();
        assertThat(output.resolve(BROKEN_TEST_REL)).exists();
    }

    @Test
    void pathsOutsideTheWorkingDirAreNeverTouched() throws IOException {
        Path foreign = tmp.resolve("elsewhere/src/test/java/Foo.java");
        Files.createDirectories(foreign.getParent());
        Files.writeString(foreign, "class Foo {}");
        String failure = "[ERROR] " + foreign + ":[1,1] cannot find symbol";

        quarantine.recordBlockedVisit("root", failure, "cycle-1");
        List<String> moved = quarantine.recordBlockedVisit("root", failure, "cycle-2");

        assertThat(moved).isEmpty();
        assertThat(foreign).exists();
    }

    @Test
    void mainSourcesAreNeverTouched() throws IOException {
        String mainRel = "src/main/java/com/example/demo/LoanService.java";
        Files.createDirectories(output.resolve(mainRel).getParent());
        Files.writeString(output.resolve(mainRel), "class LoanService {}");
        String failure = "[ERROR] " + output.resolve(mainRel) + ":[1,1] cannot find symbol";

        quarantine.recordBlockedVisit("root", failure, "cycle-1");
        List<String> moved = quarantine.recordBlockedVisit("root", failure, "cycle-2");

        assertThat(moved).isEmpty();
        assertThat(output.resolve(mainRel)).exists();
    }

    @Test
    void disabledComponentMovesNothingAndCountsNothing() {
        BrokenTestQuarantine off = new BrokenTestQuarantine(false, buildExecutor);
        ReflectionTestUtils.setField(off, "fileBasePath", tmp.toString());

        assertThat(off.recordBlockedVisit("root", failureNaming(BROKEN_TEST_REL), "c1")).isEmpty();
        assertThat(off.recordBlockedVisit("root", failureNaming(BROKEN_TEST_REL), "c2")).isEmpty();
        assertThat(output.resolve(BROKEN_TEST_REL)).exists();
    }

    @Test
    void testSourcePathsIn_extractsDistinctTestPathsOnly() {
        String text = "[ERROR] /a/output/src/test/java/FooTest.java:[5,1] x | "
                + "[ERROR] /a/output/src/main/java/Foo.java:[9,1] y | "
                + "[ERROR] /a/output/src/test/java/FooTest.java:[7,2] z";

        assertThat(BrokenTestQuarantine.testSourcePathsIn(text))
                .containsExactly("/a/output/src/test/java/FooTest.java");
        assertThat(BrokenTestQuarantine.testSourcePathsIn(null)).isEmpty();
        assertThat(BrokenTestQuarantine.testSourcePathsIn("no paths here")).isEmpty();
    }
}
