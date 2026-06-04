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

    private static FixProposal proposal(String rule, String token, List<String> paths) {
        java.util.List<FileMutation> mutations = new java.util.ArrayList<>(paths.size());
        for (String p : paths) mutations.add(new FileMutation(p, "stable-content for " + p));
        return new FixProposal(
                mutations,
                new InjectorFixRecord(rule, Kind.CANNOT_FIND_SYMBOL, token, paths, rule + " fix"));
    }

    private static FixProposal proposalWithContent(String rule, String token, String path, String content) {
        return new FixProposal(
                List.of(new FileMutation(path, content)),
                new InjectorFixRecord(rule, Kind.CANNOT_FIND_SYMBOL, token, List.of(path), rule + " fix"));
    }
}
