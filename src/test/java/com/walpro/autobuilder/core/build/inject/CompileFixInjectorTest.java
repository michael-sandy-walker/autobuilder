package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
        CompileFixInjector injector = new CompileFixInjector(List.of());
        assertThat(injector.inject("", ctx())).isEmpty();
        assertThat(injector.inject(null, ctx())).isEmpty();
    }

    @Test
    void inject_unknownErrorKind_returnsEmpty() {
        CompileFixInjector injector = new CompileFixInjector(List.of(alwaysMatch()));
        assertThat(injector.inject(UNKNOWN_STDERR, ctx())).isEmpty();
    }

    @Test
    void inject_firstResolverWins() {
        CompileFixResolver first = (kind, token, c) -> Optional.of(stubProposal("first", kind));
        CompileFixResolver second = (kind, token, c) -> Optional.of(stubProposal("second", kind));
        CompileFixInjector injector = new CompileFixInjector(List.of(first, second));

        Optional<FixProposal> out = injector.inject(MISSING_POM_STDERR, ctx());
        assertThat(out).isPresent();
        assertThat(out.get().record().rule()).isEqualTo("first");
    }

    @Test
    void inject_walksResolversUntilMatch() {
        CompileFixResolver skip = (kind, token, c) -> Optional.empty();
        CompileFixResolver hit = (kind, token, c) -> Optional.of(stubProposal("hit", kind));
        CompileFixInjector injector = new CompileFixInjector(List.of(skip, skip, hit));

        Optional<FixProposal> out = injector.inject(MISSING_PACKAGE_STDERR, ctx());
        assertThat(out).isPresent();
        assertThat(out.get().record().rule()).isEqualTo("hit");
        assertThat(out.get().record().kind()).isEqualTo(Kind.PACKAGE_DOES_NOT_EXIST);
    }

    @Test
    void inject_noResolverMatches_returnsEmpty() {
        CompileFixResolver skip = (kind, token, c) -> Optional.empty();
        CompileFixInjector injector = new CompileFixInjector(List.of(skip, skip));
        assertThat(injector.inject(MISSING_POM_STDERR, ctx())).isEmpty();
    }

    @Test
    void inject_threadsMissingTokenIntoResolver() {
        // Verifies extractToken is being passed in for PACKAGE_DOES_NOT_EXIST kind.
        String[] captured = new String[1];
        CompileFixResolver captor = (kind, token, c) -> {
            captured[0] = token;
            return Optional.of(stubProposal("captor", kind));
        };
        new CompileFixInjector(List.of(captor)).inject(MISSING_PACKAGE_STDERR, ctx());
        assertThat(captured[0]).isEqualTo("jakarta.persistence");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static CompileFixResolver alwaysMatch() {
        return (kind, token, c) -> Optional.of(stubProposal("any", kind));
    }

    private static FixProposal stubProposal(String rule, Kind kind) {
        return new FixProposal(
                List.of(new FileMutation("pom.xml", "<project/>")),
                new InjectorFixRecord(rule, kind == null ? Kind.UNKNOWN : kind, "", List.of("pom.xml"), "stub"));
    }
}
