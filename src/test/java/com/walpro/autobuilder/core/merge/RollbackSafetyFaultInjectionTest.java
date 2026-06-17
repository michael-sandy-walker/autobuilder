package com.walpro.autobuilder.core.merge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.build.inject.FixAttemptLedger;
import com.walpro.autobuilder.core.build.inject.FixProposal;
import com.walpro.autobuilder.core.build.inject.InjectorFixRecord;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.testsupport.OutputFixtureBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T5-AF layer 1 (gate plumbing — deterministic, CI-safe): the two rollback-safety mechanisms that
 * keep a run from wedging on "no POM". Both only fire on a specific bad LLM sequence in the field;
 * this stages that sequence on a real temp {@code output/} (via {@link OutputFixtureBuilder}) so the
 * mechanisms are validated on demand.
 *
 * <ul>
 *   <li><b>T1-FB</b> — a compile-OK-then-evaluation-reject rollback must PRESERVE the injector's
 *       bootstrapped pom (it made the build green and is not the evaluated LLM work).</li>
 *   <li><b>T1-FC</b> — a bootstrap-pom blacklisted after failed re-probes must RE-ENABLE the moment
 *       its target pom is absent on disk, so a rolled-back pom can always be restored.</li>
 * </ul>
 */
class RollbackSafetyFaultInjectionTest {

    @TempDir Path tempDir;

    private FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileService(tempDir.toString(), new ObjectMapper());
    }

    // ---- T1-FB: evaluation-rejection rollback preserves the injector-bootstrapped pom ----

    @Test
    void compileOkThenEvaluationReject_preservesBootstrappedPom() throws IOException {
        // Staged: injector bootstrapped pom (made the build green), plus the LLM's own new file and a
        // modified existing file — the mutation that evaluation then rejects for an unrelated reason.
        OutputFixtureBuilder.builder()
                .file("pom.xml", "<project>bootstrapped</project>")
                .file("src/main/java/com/example/demo/OrderService.java", "class OrderService {}")
                .file("src/main/java/com/example/demo/Existing.java", "MODIFIED")
                .writeTo(fileService, "output");

        Map<String, String> originals = new LinkedHashMap<>();
        originals.put("pom.xml", "");                                          // injector, newly added
        originals.put("src/main/java/com/example/demo/OrderService.java", ""); // LLM, newly added
        originals.put("src/main/java/com/example/demo/Existing.java", "ORIGINAL"); // pre-existing, modified
        Set<String> newlyAdded = new LinkedHashSet<>(Set.of(
                "pom.xml", "src/main/java/com/example/demo/OrderService.java"));

        MergeRollbackService service = new MergeRollbackService();
        MergeRollbackPoint point = service.snapshot("c1", originals, newlyAdded);

        // Evaluation rejected the compile-clean mutation: preserve the injector's pom only.
        service.rollback(point, "output", fileService, Set.of("pom.xml"));

        assertThat(fileService.exists("output/pom.xml"))
                .as("T1-FB: bootstrapped pom survives an evaluation-rejection rollback")
                .isTrue();
        assertThat(fileService.readString("output/pom.xml")).contains("bootstrapped");
        assertThat(fileService.exists("output/src/main/java/com/example/demo/OrderService.java"))
                .as("the rejected LLM mutation's new file is still removed")
                .isFalse();
        assertThat(fileService.readString("output/src/main/java/com/example/demo/Existing.java"))
                .as("a modified existing file is still restored")
                .isEqualTo("ORIGINAL");
    }

    @Test
    void genuineCompileFailure_noPreserve_removesBootstrappedPomToo() throws IOException {
        OutputFixtureBuilder.builder()
                .file("pom.xml", "<project>bootstrapped</project>")
                .file("src/main/java/com/example/demo/New.java", "class New {}")
                .writeTo(fileService, "output");

        Map<String, String> originals = new LinkedHashMap<>();
        originals.put("pom.xml", "");
        originals.put("src/main/java/com/example/demo/New.java", "");
        MergeRollbackService service = new MergeRollbackService();
        MergeRollbackPoint point = service.snapshot("c1", originals,
                new LinkedHashSet<>(Set.of("pom.xml", "src/main/java/com/example/demo/New.java")));

        // Genuine compile-failure rollback (no preserve): the fixes did not achieve a green build.
        service.rollback(point, "output", fileService);

        assertThat(fileService.exists("output/pom.xml")).isFalse();
        assertThat(fileService.exists("output/src/main/java/com/example/demo/New.java")).isFalse();
    }

    // ---- T1-FC: blacklisted bootstrap-pom re-enables when its target pom is absent ----

    @Test
    void blacklistedBootstrapPom_reEnablesAfterPomRolledBack() throws IOException {
        fileService.ensureDefaultFolders();
        FixAttemptLedger ledger = new FixAttemptLedger(fileService);
        FixProposal pom = new FixProposal(
                List.of(new FileMutation("pom.xml", "<project><!-- bootstrapped --></project>")),
                new InjectorFixRecord("bootstrap-pom", Kind.MISSING_POM, "", List.of("pom.xml"), "Bootstrapped pom"));

        // Stage the pom on disk and drive it to the blacklist with two failed re-probes.
        OutputFixtureBuilder.builder().file("pom.xml", "<project/>").writeTo(fileService, "output");
        ledger.recordAttempt(pom, BuildResult.COMPILE_ERROR, "output", "c1");
        ledger.recordAttempt(pom, BuildResult.COMPILE_ERROR, "output", "c2");
        assertThat(ledger.isBlacklisted(pom)).isTrue();
        assertThat(ledger.shouldSkip(pom, "output", "c3")).isTrue();

        // The pom is rolled back / deleted — the build can no longer run.
        fileService.deleteFile("output/pom.xml");

        assertThat(ledger.shouldSkip(pom, "output", "c4"))
                .as("T1-FC: an absent target pom re-enables the bootstrap so it can be restored")
                .isFalse();
    }
}
