package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Category;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.build.BuildErrorClassifierAgent;
import com.walpro.autobuilder.core.build.BuildResult;
import com.walpro.autobuilder.core.grounding.SymbolGroundingService;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompileFixInjectorTest {

    private static final String MISSING_POM_STDERR =
            "[ERROR] there is no POM in this directory (/x). Please verify you invoked Maven";

    private static final String MISSING_PACKAGE_STDERR =
            "[ERROR] /src/Loan.java:[3,1] package jakarta.persistence does not exist";

    private static final String UNKNOWN_STDERR =
            "[INFO] BUILD FAILURE -- something the classifier does not recognise";

    private CycleContextData ctx() {
        return CycleContextData.builder().cycleName("c-1").outputPath("output").build();
    }

    @Test
    void inject_blankStderr_returnsEmpty() {
        CompileFixInjector injector = new CompileFixInjector(List.of(), noopLedger(), staticAgent(), mockLookup(), mockRegistry());
        assertThat(injector.inject("", ctx())).isEmpty();
        assertThat(injector.inject(null, ctx())).isEmpty();
    }

    @Test
    void inject_unknownErrorKind_returnsEmpty() {
        CompileFixInjector injector = new CompileFixInjector(List.of(alwaysMatch()), noopLedger(), staticAgent(), mockLookup(), mockRegistry());
        assertThat(injector.inject(UNKNOWN_STDERR, ctx())).isEmpty();
    }

    @Test
    void inject_firstResolverWins() {
        CompileFixResolver first = (cl, token, err, c) -> Optional.of(stubProposal("first", cl.kind()));
        CompileFixResolver second = (cl, token, err, c) -> Optional.of(stubProposal("second", cl.kind()));
        CompileFixInjector injector = new CompileFixInjector(List.of(first, second), noopLedger(), staticAgent(), mockLookup(), mockRegistry());

        Optional<FixProposal> out = injector.inject(MISSING_POM_STDERR, ctx());
        assertThat(out).isPresent();
        assertThat(out.get().record().rule()).isEqualTo("first");
    }

    @Test
    void inject_walksResolversUntilMatch() {
        CompileFixResolver skip = (cl, token, err, c) -> Optional.empty();
        CompileFixResolver hit = (cl, token, err, c) -> Optional.of(stubProposal("hit", cl.kind()));
        CompileFixInjector injector = new CompileFixInjector(List.of(skip, skip, hit), noopLedger(), staticAgent(), mockLookup(), mockRegistry());

        Optional<FixProposal> out = injector.inject(MISSING_PACKAGE_STDERR, ctx());
        assertThat(out).isPresent();
        assertThat(out.get().record().rule()).isEqualTo("hit");
        assertThat(out.get().record().kind()).isEqualTo(Kind.PACKAGE_DOES_NOT_EXIST);
    }

    @Test
    void inject_noResolverMatches_returnsEmpty() {
        CompileFixResolver skip = (cl, token, err, c) -> Optional.empty();
        CompileFixInjector injector = new CompileFixInjector(List.of(skip, skip), noopLedger(), staticAgent(), mockLookup(), mockRegistry());
        assertThat(injector.inject(MISSING_POM_STDERR, ctx())).isEmpty();
    }

    @Test
    void inject_threadsMissingTokenIntoResolver() {
        // Verifies extractToken is being passed in for PACKAGE_DOES_NOT_EXIST kind.
        String[] captured = new String[1];
        CompileFixResolver captor = (cl, token, err, c) -> {
            captured[0] = token;
            return Optional.of(stubProposal("captor", cl.kind()));
        };
        new CompileFixInjector(List.of(captor), noopLedger(), staticAgent(), mockLookup(), mockRegistry()).inject(MISSING_PACKAGE_STDERR, ctx());
        assertThat(captured[0]).isEqualTo("jakarta.persistence");
    }

    // ── T4-AN-7: the wiring proof — category from the adaptive classifier drives a different
    //    output than the static path could produce ─────────────────────────────────────────

    @Test
    void adaptiveCategory_routesUnknownKindStderrToSourceResolver() {
        // The static matcher classifies UNKNOWN_STDERR as UNKNOWN → no resolver runs. The adaptive
        // classifier tags it SOURCE_FIXABLE + missingSymbol="BookRepository" (a state the regex path
        // cannot produce), so the real IdiomSkeletonResolver now fires. This is the divergence that
        // separates the real ticket from the reverted cosmetic rename.
        BuildErrorClassifierAgent agent = mock(BuildErrorClassifierAgent.class);
        when(agent.classify(UNKNOWN_STDERR)).thenReturn(new Classification(
                Kind.UNKNOWN, List.of(), Category.SOURCE_FIXABLE,
                "missing repository type", null, "BookRepository", null));

        CompileFixInjector injector = new CompileFixInjector(
                List.of(realSkeletonResolver()), noopLedger(), agent, mockLookup(), mockRegistry());

        Optional<FixProposal> out = injector.inject(UNKNOWN_STDERR, ctx());
        assertThat(out).as("adaptive SOURCE_FIXABLE category should route to the skeleton resolver").isPresent();
        assertThat(out.get().mutations().get(0).path()).endsWith("repository/BookRepository.java");
        assertThat(out.get().record().rule()).isEqualTo("skeleton-repository");
    }

    @Test
    void staticPath_leavesUnknownKindStderrToTheLlm() {
        // Same resolver + same stderr, but the static classifier (adaptive off) → UNKNOWN category →
        // the injector defers. Proves the previous test's behaviour comes from the adaptive category,
        // not from the resolver or the stderr.
        CompileFixInjector injector = new CompileFixInjector(
                List.of(realSkeletonResolver()), noopLedger(), staticAgent(), mockLookup(), mockRegistry());
        assertThat(injector.inject(UNKNOWN_STDERR, ctx())).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static SymbolImportLookupService mockLookup() {
        return mock(SymbolImportLookupService.class);
    }

    private static WellKnownPackageRegistry mockRegistry() {
        return mock(WellKnownPackageRegistry.class);
    }

    /** Agent stub standing in for adaptive-off behaviour: delegates to the static pattern matcher. */
    private static BuildErrorClassifierAgent staticAgent() {
        BuildErrorClassifierAgent agent = mock(BuildErrorClassifierAgent.class);
        when(agent.classify(anyString())).thenAnswer(inv -> BuildErrorClassifier.classify(inv.getArgument(0)));
        return agent;
    }

    private static CompileFixResolver realSkeletonResolver() {
        SymbolGroundingService grounding = mock(SymbolGroundingService.class);
        when(grounding.definedSymbolsInOutput()).thenReturn(Set.of());
        SymbolImportLookupService lookup = mock(SymbolImportLookupService.class); // lookup() → Optional.empty() by default
        return new IdiomSkeletonResolver(grounding, new IdiomSkeletonConfigProperties(), lookup,
                mock(com.walpro.autobuilder.core.service.FileService.class));
    }

    /** T2-DY: ledger that never trips skip — keeps the existing tests focused on chain semantics. */
    private static FixAttemptLedger noopLedger() {
        return new FixAttemptLedger(null) {
            @Override
            public boolean shouldSkip(FixProposal proposal, String outputPath, String cycleId) {
                return false;
            }
            @Override
            public void recordAttempt(FixProposal proposal, com.walpro.autobuilder.core.build.BuildResult r,
                                      String outputPath, String cycleId) {
                // no-op
            }
        };
    }

    private static CompileFixResolver alwaysMatch() {
        return (cl, token, err, c) -> Optional.of(stubProposal("any", cl.kind()));
    }

    private static FixProposal stubProposal(String rule, Kind kind) {
        return new FixProposal(
                List.of(new FileMutation("pom.xml", "<project/>")),
                new InjectorFixRecord(rule, kind == null ? Kind.UNKNOWN : kind, "", List.of("pom.xml"), "stub"));
    }

    @Test
    void recordReProbeOutcome_targetedDiagnosticGone_recordsProgressNotFailure() {
        // Multi-error builds: a fix that removed its own diagnostic must not be blacklisted
        // just because OTHER error classes keep the build red.
        FixAttemptLedger ledger = mock(FixAttemptLedger.class);
        CompileFixInjector injector = new CompileFixInjector(
                List.of(), ledger, staticAgent(), mockLookup(), mockRegistry());
        FixProposal proposal = proposalWithToken("skeleton-service", "BookService");

        injector.recordReProbeOutcome(proposal, BuildResult.COMPILE_ERROR,
                "[ERROR] cannot find symbol\n  symbol:   class LoanNotFoundException", "output", "c-1");

        verify(ledger).recordAttempt(proposal, BuildResult.COMPILE_OK, "output", "c-1");
    }

    @Test
    void recordReProbeOutcome_symbolImportStillFailing_propagatesToLookupAndRegistry() {
        FixAttemptLedger ledger = mock(FixAttemptLedger.class);
        SymbolImportLookupService lookup = mockLookup();
        WellKnownPackageRegistry registry = mockRegistry();
        CompileFixInjector injector = new CompileFixInjector(
                List.of(), ledger, staticAgent(), lookup, registry);
        FixProposal proposal = proposalWithToken("symbol-import:spring-data-rest-webmvc", "BookNotFoundException");

        injector.recordReProbeOutcome(proposal, BuildResult.COMPILE_ERROR,
                "[ERROR] cannot find symbol\n  symbol:   class BookNotFoundException", "output", "c-1");

        verify(ledger).recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "c-1");
        verify(lookup).markResolutionFailed("BookNotFoundException");
        verify(registry).markResolutionFailed("BookNotFoundException");
    }

    @Test
    void recordReProbeOutcome_nonSymbolImportFailure_doesNotTouchLookup() {
        FixAttemptLedger ledger = mock(FixAttemptLedger.class);
        SymbolImportLookupService lookup = mockLookup();
        CompileFixInjector injector = new CompileFixInjector(
                List.of(), ledger, staticAgent(), lookup, mockRegistry());
        FixProposal proposal = proposalWithToken("skeleton-service", "BookService");

        injector.recordReProbeOutcome(proposal, BuildResult.COMPILE_ERROR,
                "[ERROR] cannot find symbol\n  symbol:   class BookService", "output", "c-1");

        verify(ledger).recordAttempt(proposal, BuildResult.COMPILE_ERROR, "output", "c-1");
        verify(lookup, org.mockito.Mockito.never()).markResolutionFailed(anyString());
    }

    private static FixProposal proposalWithToken(String rule, String token) {
        return new FixProposal(
                List.of(new com.walpro.autobuilder.agent.evolution.model.FileMutation("X.java", "x")),
                new InjectorFixRecord(rule, Kind.CANNOT_FIND_SYMBOL, token, List.of("X.java"), "desc"));
    }
}
