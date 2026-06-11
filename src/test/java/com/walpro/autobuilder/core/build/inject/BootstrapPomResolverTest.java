package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;

import java.util.List;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapPomResolverTest {

    private final BootstrapPomResolver resolver = new BootstrapPomResolver();

    @Test
    void resolve_missingPom_returnsDefaultPomFix() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c-1").outputPath("output").build();
        Optional<FixProposal> out = resolver.resolve(cls(Kind.MISSING_POM), null, null, ctx);

        assertThat(out).isPresent();
        FixProposal proposal = out.get();
        assertThat(proposal.mutations()).hasSize(1);
        assertThat(proposal.mutations().get(0).path()).isEqualTo("pom.xml");
        assertThat(proposal.mutations().get(0).content()).contains("spring-boot-starter-parent")
                .contains("<java.version>21</java.version>");
        assertThat(proposal.record().rule()).isEqualTo("bootstrap-pom");
        assertThat(proposal.record().kind()).isEqualTo(Kind.MISSING_POM);
        assertThat(proposal.record().touchedPaths()).containsExactly("pom.xml");
    }

    @Test
    void resolve_nonMissingPomKind_returnsEmpty() {
        CycleContextData ctx = CycleContextData.builder().cycleName("c-1").outputPath("output").build();
        assertThat(resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "jakarta.persistence", null, ctx)).isEmpty();
        assertThat(resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "Foo", null, ctx)).isEmpty();
        assertThat(resolver.resolve(cls(Kind.UNKNOWN), null, null, ctx)).isEmpty();
    }

    /** T4-AN-7: builds a pattern-path Classification from a Kind (category derived via defaultCategoryFor). */
    private static Classification cls(Kind kind) {
        return new Classification(kind, List.of());
    }
}
