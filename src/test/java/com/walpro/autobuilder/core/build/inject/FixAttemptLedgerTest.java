package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.observability.behaviour.BehaviourLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FixAttemptLedgerTest {

    @TempDir Path tempDir;

    private FileService fileService;
    private BehaviourLogger behaviourLogger;
    private FixAttemptLedger ledger;

    @BeforeEach
    void setUp() throws IOException {
        fileService = new FileService(tempDir.toString(), new ObjectMapper().findAndRegisterModules());
        fileService.ensureDefaultFolders();
        behaviourLogger = mock(BehaviourLogger.class);
        ledger = new FixAttemptLedger(fileService);
        ledger.setBehaviourLogger(behaviourLogger);
    }

    @Test
    void shouldSkip_firstAttempt_returnsFalse() {
        FixProposal proposal = proposal("symbol-import:spring-web", "RestController",
                List.of("src/main/java/Foo.java", "pom.xml"));
        assertThat(ledger.shouldSkip(proposal, "output", "cycle-1")).isFalse();
    }

    @Test
    void shouldSkip_hardCap_holdsEvenWhenTargetContentChanges() throws IOException {
        // 2026-06-10: a fix touching pom.xml had its blacklist re-cleared every cycle by the
        // digest-invalidation rule (pom churns as other fixes land) and re-fired 4x. Past
        // MAX_TOTAL_ATTEMPTS the proposal must stay skipped no matter what changed on disk.
        FixProposal proposal = proposal("symbol-import:spring-data-rest-webmvc", "BookNotFoundException",
                List.of("src/main/java/LoanService.java", "pom.xml"));
        Path svc = tempDir.resolve("output/src/main/java/LoanService.java");
        Files.createDirectories(svc.getParent());

        for (int i = 1; i <= FixAttemptLedger.MAX_TOTAL_ATTEMPTS; i++) {
            // Hot files: content differs on every attempt, which used to reset the blacklist.
            Files.writeString(svc, "class LoanService { /* v" + i + " */ }");
            Files.writeString(tempDir.resolve("output/pom.xml"), "<project><!-- v" + i + " --></project>");
            ledger.recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "cycle-" + i);
        }
        // Content changes again after the cap — must still skip.
        Files.writeString(svc, "class LoanService { /* post-cap */ }");

        assertThat(ledger.shouldSkip(proposal, "output", "cycle-next")).isTrue();
    }

    @Test
    void shouldSkip_afterTwoFailures_blacklists_andEmitsSkipEvent() throws IOException {
        FixProposal proposal = proposal("symbol-import:spring-web", "RestController",
                List.of("src/main/java/Foo.java", "pom.xml"));
        // Create the touched files so content-digest sampling has something stable.
        Path fooPath = tempDir.resolve("output/src/main/java/Foo.java");
        Files.createDirectories(fooPath.getParent());
        Files.writeString(fooPath, "class Foo {}");
        Files.writeString(tempDir.resolve("output/pom.xml"), "<project/>");

        ledger.recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "cycle-1");
        ledger.recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "cycle-2");

        assertThat(ledger.isBlacklisted(proposal)).isTrue();
        assertThat(ledger.shouldSkip(proposal, "output", "cycle-3")).isTrue();
        verify(behaviourLogger).fixProposalSkippedIdempotent(eq("cycle-3"),
                eq("symbol-import:spring-web"), eq("RestController"),
                eq(List.of("src/main/java/Foo.java", "pom.xml")), anyInt(), anyInt());
    }

    @Test
    void shouldSkip_clearsBlacklistAfterTargetFileContentChange() throws IOException {
        FixProposal proposal = proposal("symbol-import:spring-web", "RestController",
                List.of("src/main/java/Foo.java"));
        Path fooPath = tempDir.resolve("output/src/main/java/Foo.java");
        Files.createDirectories(fooPath.getParent());
        Files.writeString(fooPath, "class Foo {}");

        ledger.recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "cycle-1");
        ledger.recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "cycle-2");
        assertThat(ledger.isBlacklisted(proposal)).isTrue();

        // Change the file content — simulates a merge or LLM edit that genuinely moves the tree.
        Files.writeString(fooPath, "class Foo { Bar field; }");

        // First lookup after the content change clears the blacklist; subsequent lookup still
        // returns false because there have been no new failed attempts.
        assertThat(ledger.shouldSkip(proposal, "output", "cycle-3")).isFalse();
        assertThat(ledger.isBlacklisted(proposal)).isFalse();
    }

    @Test
    void shouldSkip_sameContentRewrite_keepsBlacklist() throws IOException {
        // Regression: the failed-re-probe rollback rewrites the touched file with its ORIGINAL
        // bytes. That bumps mtime but not content, so an mtime-keyed blacklist would self-clear on
        // the very rollback the resolver provoked. A content digest must survive a same-bytes rewrite.
        FixProposal proposal = proposal("symbol-import:spring-web", "RestController",
                List.of("src/main/java/Foo.java"));
        Path fooPath = tempDir.resolve("output/src/main/java/Foo.java");
        Files.createDirectories(fooPath.getParent());
        Files.writeString(fooPath, "class Foo {}");

        ledger.recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "cycle-1");
        ledger.recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "cycle-2");
        assertThat(ledger.isBlacklisted(proposal)).isTrue();

        // Rewrite with identical bytes — the rollback restoring the original content.
        Files.delete(fooPath);
        Files.writeString(fooPath, "class Foo {}");

        assertThat(ledger.shouldSkip(proposal, "output", "cycle-3")).isTrue();
        assertThat(ledger.isBlacklisted(proposal)).isTrue();
    }

    @Test
    void recordAttempt_compileOkClearsBlacklist() throws IOException {
        FixProposal proposal = proposal("symbol-import:local", "Book",
                List.of("src/main/java/Foo.java"));
        Path fooPath = tempDir.resolve("output/src/main/java/Foo.java");
        Files.createDirectories(fooPath.getParent());
        Files.writeString(fooPath, "class Foo {}");

        ledger.recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "cycle-1");
        ledger.recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "cycle-2");
        assertThat(ledger.isBlacklisted(proposal)).isTrue();

        ledger.recordAttempt(proposal, BuildResult.COMPILE_OK, "output", "cycle-3");

        assertThat(ledger.isBlacklisted(proposal)).isFalse();
        assertThat(ledger.attempts(proposal)).isEqualTo(3);
    }

    @Test
    void recordAttempt_singleFailureDoesNotBlacklist() throws IOException {
        FixProposal proposal = proposal("symbol-import:local", "Book",
                List.of("src/main/java/Foo.java"));
        Path fooPath = tempDir.resolve("output/src/main/java/Foo.java");
        Files.createDirectories(fooPath.getParent());
        Files.writeString(fooPath, "class Foo {}");

        ledger.recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "cycle-1");

        assertThat(ledger.isBlacklisted(proposal)).isFalse();
        assertThat(ledger.shouldSkip(proposal, "output", "cycle-2")).isFalse();
        verify(behaviourLogger, never()).fixProposalSkippedIdempotent(
                anyString(), anyString(), anyString(), anyList(), anyInt(), anyInt());
    }

    @Test
    void shouldSkip_infrastructureBootstrap_reEnablesWhenTargetAbsent() throws IOException {
        // T1-FC: a bootstrap-pom blacklisted after failing re-probe must re-enable the moment its
        // target pom.xml is absent on disk (a rolled-back pom) — otherwise the run wedges on "no POM".
        FixProposal pom = bootstrapPomProposal();
        Path pomPath = tempDir.resolve("output/pom.xml");
        Files.writeString(pomPath, "<project/>");

        ledger.recordAttempt(pom, BuildResult.COMPILE_ERROR, "output", "cycle-1");
        ledger.recordAttempt(pom, BuildResult.COMPILE_ERROR, "output", "cycle-2");
        assertThat(ledger.isBlacklisted(pom)).isTrue();

        // The pom vanishes (rolled back). Content-digest invalidation can't see this (a deleted file
        // keeps the same ABSENT digest), but the target-absent rule must re-enable the bootstrap.
        Files.delete(pomPath);

        assertThat(ledger.shouldSkip(pom, "output", "cycle-3")).isFalse();
        assertThat(ledger.attempts(pom)).isZero(); // entry dropped — fresh budget
    }

    @Test
    void shouldSkip_infrastructureBootstrap_reEnablesPastHardCap() throws IOException {
        // T1-FC: even past MAX_TOTAL_ATTEMPTS (the branch that returns before any disk read), an
        // absent pom re-enables the bootstrap — the build literally cannot run without it.
        FixProposal pom = bootstrapPomProposal();
        Path pomPath = tempDir.resolve("output/pom.xml");
        for (int i = 1; i <= FixAttemptLedger.MAX_TOTAL_ATTEMPTS; i++) {
            Files.writeString(pomPath, "<project><!-- v" + i + " --></project>");
            ledger.recordAttempt(pom, BuildResult.COMPILE_ERROR, "output", "cycle-" + i);
        }
        Files.delete(pomPath);

        assertThat(ledger.shouldSkip(pom, "output", "cycle-next")).isFalse();
    }

    @Test
    void shouldSkip_nonBootstrapFix_pastHardCap_doesNotReEnableWhenTargetAbsent() throws IOException {
        // T1-FC scoping: the hard-cap override is for infrastructure-bootstrap fixes ONLY. A
        // symbol-import fix that has burned MAX_TOTAL_ATTEMPTS stays skipped even if its touched source
        // vanished — re-firing a hallucinated import forever is the hot loop the cap exists to bound.
        // (Below the cap, the existing content-digest rule already re-enables any fix on a real edit,
        // including a delete — that path is unchanged and not what T1-FC adds.)
        FixProposal proposal = proposal("symbol-import:spring-web", "RestController",
                List.of("src/main/java/Foo.java"));
        Path fooPath = tempDir.resolve("output/src/main/java/Foo.java");
        Files.createDirectories(fooPath.getParent());
        for (int i = 1; i <= FixAttemptLedger.MAX_TOTAL_ATTEMPTS; i++) {
            Files.writeString(fooPath, "class Foo { /* v" + i + " */ }");
            ledger.recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "cycle-" + i);
        }
        Files.delete(fooPath);

        assertThat(ledger.shouldSkip(proposal, "output", "cycle-next")).isTrue();
    }

    @Test
    void proposalHash_stable_acrossIdenticalProposals() {
        FixProposal a = proposal("symbol-import:spring-web", "RestController",
                List.of("src/main/java/Foo.java", "pom.xml"));
        FixProposal b = proposal("symbol-import:spring-web", "RestController",
                List.of("pom.xml", "src/main/java/Foo.java"));  // different path order
        assertThat(a.proposalHash()).isEqualTo(b.proposalHash());
    }

    @Test
    void proposalHash_changes_whenRuleChanges() {
        FixProposal a = proposal("symbol-import:spring-web", "RestController",
                List.of("src/main/java/Foo.java"));
        FixProposal b = proposal("symbol-import:other", "RestController",
                List.of("src/main/java/Foo.java"));
        assertThat(a.proposalHash()).isNotEqualTo(b.proposalHash());
    }

    @Test
    void proposalHash_changes_whenContentChanges() {
        FixProposal a = proposalWithContent("rule", "Token", "src/main/java/Foo.java", "content-A");
        FixProposal b = proposalWithContent("rule", "Token", "src/main/java/Foo.java", "content-B");
        assertThat(a.proposalHash()).isNotEqualTo(b.proposalHash());
    }

    // -------------------------------------------------------------------------------------
    // T2-EN: run-level re-fire cap — decoupled from proposal hashes and the progress-aware
    // per-proposal assessment (the 2026-07-16 skeleton-repository escape).
    // -------------------------------------------------------------------------------------

    /** A skeleton regenerated from fresh usage sites: same (rule, token), NEW content each cycle. */
    private static FixProposal regeneratedSkeleton(String token, int version) {
        return new FixProposal(
                List.of(new FileMutation("src/main/java/" + token + ".java",
                        "public interface " + token + " { /* derived v" + version + " */ }")),
                new InjectorFixRecord("skeleton-repository", Kind.CANNOT_FIND_SYMBOL, token,
                        List.of("src/main/java/" + token + ".java"), "skeleton for " + token));
    }

    @Test
    void runLevelCap_catchesContentVaryingRecurrence_thePerProposalLedgerCannotSee() {
        // The observed run: 12+ cycles of skeleton-repository for the same token, each a fresh
        // content hash (so no per-proposal entry ever accrued), each raw re-probe COMPILE_ERROR.
        boolean capped = false;
        int i = 1;
        for (; i <= FixAttemptLedger.MAX_RUN_LEVEL_FIRES; i++) {
            FixProposal p = regeneratedSkeleton("BookRepository", i);
            assertThat(ledger.shouldSkip(p, "output", "cycle-" + i)).as("cycle %d pre-cap", i).isFalse();
            capped = ledger.recordRunLevelFire(p, BuildResult.COMPILE_ERROR, "cycle-" + i);
        }
        assertThat(capped).as("cap crossing reported exactly at the threshold").isTrue();

        // The NEXT derivation — again fresh content — is skipped and the skip event carries the span.
        FixProposal next = regeneratedSkeleton("BookRepository", i);
        assertThat(ledger.shouldSkip(next, "output", "cycle-" + i)).isTrue();
        verify(behaviourLogger).fixProposalSkippedIdempotent(eq("cycle-" + i), eq("skeleton-repository"),
                eq("BookRepository"), anyList(), eq(FixAttemptLedger.MAX_RUN_LEVEL_FIRES),
                eq(FixAttemptLedger.MAX_RUN_LEVEL_FIRES));
    }

    @Test
    void runLevelCap_onlyOneFirePerCycle_severalDiagnosticsAreOneRecurrence() {
        FixProposal p = regeneratedSkeleton("MemberRepository", 1);
        assertThat(ledger.recordRunLevelFire(p, BuildResult.COMPILE_ERROR, "cycle-1")).isFalse();
        assertThat(ledger.recordRunLevelFire(p, BuildResult.COMPILE_ERROR, "cycle-1")).isFalse();
        assertThat(ledger.runLevelFires(p)).isEqualTo(1);
    }

    @Test
    void runLevelCap_rawCompileOkResetsTheTally_aLandedFixIsNotARecurrence() {
        FixProposal p = regeneratedSkeleton("LoanRepository", 1);
        for (int i = 1; i < FixAttemptLedger.MAX_RUN_LEVEL_FIRES; i++) {
            ledger.recordRunLevelFire(regeneratedSkeleton("LoanRepository", i), BuildResult.COMPILE_ERROR, "cycle-" + i);
        }
        ledger.recordRunLevelFire(p, BuildResult.COMPILE_OK, "cycle-ok");
        assertThat(ledger.runLevelFires(p)).isZero();
        assertThat(ledger.shouldSkip(regeneratedSkeleton("LoanRepository", 99), "output", "cycle-next")).isFalse();
    }

    @Test
    void runLevelCap_neverCapsTheInfrastructureBootstrap() {
        // T1-FC discipline: the build cannot run without the pom — re-creating it is always right.
        FixProposal pom = bootstrapPomProposal();
        for (int i = 1; i <= FixAttemptLedger.MAX_RUN_LEVEL_FIRES + 2; i++) {
            assertThat(ledger.recordRunLevelFire(pom, BuildResult.COMPILE_ERROR, "cycle-" + i)).isFalse();
        }
        assertThat(ledger.runLevelFires(pom)).isZero();
    }

    @Test
    void runLevelCap_isPerRuleTokenPair_notGlobal() {
        for (int i = 1; i <= FixAttemptLedger.MAX_RUN_LEVEL_FIRES; i++) {
            ledger.recordRunLevelFire(regeneratedSkeleton("BookRepository", i), BuildResult.COMPILE_ERROR, "cycle-" + i);
        }
        // A different token on the same rule stays live.
        assertThat(ledger.shouldSkip(regeneratedSkeleton("MemberRepository", 1), "output", "cycle-x")).isFalse();
    }

    private static FixProposal proposal(String rule, String token, List<String> paths) {
        java.util.List<FileMutation> mutations = new java.util.ArrayList<>(paths.size());
        for (String p : paths) mutations.add(new FileMutation(p, "stable-content for " + p));
        return new FixProposal(
                mutations,
                new InjectorFixRecord(rule, Kind.CANNOT_FIND_SYMBOL, token, paths, rule + " fix"));
    }

    private static FixProposal bootstrapPomProposal() {
        return new FixProposal(
                List.of(new FileMutation("pom.xml", "<project><!-- bootstrapped --></project>")),
                new InjectorFixRecord("bootstrap-pom", Kind.MISSING_POM, "",
                        List.of("pom.xml"), "Bootstrapped pom.xml"));
    }

    private static FixProposal proposalWithContent(String rule, String token, String path, String content) {
        return new FixProposal(
                List.of(new FileMutation(path, content)),
                new InjectorFixRecord(rule, Kind.CANNOT_FIND_SYMBOL, token, List.of(path), rule + " fix"));
    }
}
