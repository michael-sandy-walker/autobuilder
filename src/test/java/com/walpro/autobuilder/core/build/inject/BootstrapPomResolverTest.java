package com.walpro.autobuilder.core.build.inject;

import com.walpro.autobuilder.core.build.BuildErrorClassifier.Classification;
import com.walpro.autobuilder.core.build.BuildErrorClassifier.Kind;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BootstrapPomResolverTest {

    private final BootstrapPomResolver resolver = new BootstrapPomResolver();

    private static CycleContextData ctx(Map<String, String> sources) {
        return CycleContextData.builder().cycleName("c-1").outputPath("output").previousOutput(sources).build();
    }

    /** T4-AN-7: builds a pattern-path Classification from a Kind (category derived via defaultCategoryFor). */
    private static Classification cls(Kind kind) {
        return new Classification(kind, List.of());
    }

    // ── T4-DI: the bootstrapped build file names no ecosystem ────────────────

    @Test
    void theBootstrappedPomNamesNoFrameworkTheTargetDidNotAskFor() {
        // It used to write a spring-boot-starter-parent pom with starter-web into ANY target. On a
        // 3D/OpenGL target that one firing set the rest of the run's agenda: repackage failed for a
        // missing main class, and the loop spent cycles satisfying a packaging step nothing asked for.
        Map<String, String> sources = Map.of(
                "src/main/java/com/flightsim/Main.java",
                "package com.flightsim;\npublic class Main { public static void main(String[] args) {} }\n");

        String pom = resolver.resolve(cls(Kind.MISSING_POM), null, null, ctx(sources))
                .orElseThrow().mutations().get(0).content();

        assertThat(pom).doesNotContain("spring").doesNotContain("Spring")
                       .doesNotContain("starter").doesNotContain("lombok")
                       .doesNotContain("jakarta").doesNotContain("com.example");
    }

    @Test
    void coordinatesComeFromThePackageTheSourcesAlreadyDeclare() {
        Map<String, String> sources = Map.of(
                "src/main/java/com/flightsim/render/OpenGL.java", "package com.flightsim.render;\npublic class OpenGL {}\n",
                "src/main/java/com/flightsim/scene/Scene.java", "package com.flightsim.scene;\npublic class Scene {}\n");

        String pom = resolver.resolve(cls(Kind.MISSING_POM), null, null, ctx(sources))
                .orElseThrow().mutations().get(0).content();

        assertThat(pom).contains("<groupId>com.flightsim</groupId>");
        assertThat(pom).contains("<artifactId>flightsim</artifactId>");
    }

    @Test
    void aSingleEntryPointBecomesTheJarManifestMainClass() {
        // This is what makes the artifact runnable for a CLI, a desktop app or a service alike —
        // and it is derived, not assumed from a framework convention.
        Map<String, String> sources = Map.of(
                "src/main/java/com/flightsim/Main.java",
                "package com.flightsim;\npublic class Main { public static void main(String[] args) {} }\n",
                "src/main/java/com/flightsim/Other.java", "package com.flightsim;\npublic class Other {}\n");

        String pom = resolver.resolve(cls(Kind.MISSING_POM), null, null, ctx(sources))
                .orElseThrow().mutations().get(0).content();

        assertThat(pom).contains("<mainClass>com.flightsim.Main</mainClass>");
        assertThat(pom).contains("maven-jar-plugin");
    }

    @Test
    void severalEntryPointsAreAmbiguous_soNoneIsDeclared() {
        Map<String, String> sources = Map.of(
                "src/main/java/a/A.java", "package a;\npublic class A { public static void main(String[] args) {} }\n",
                "src/main/java/a/B.java", "package a;\npublic class B { public static void main(String[] args) {} }\n");

        String pom = resolver.resolve(cls(Kind.MISSING_POM), null, null, ctx(sources))
                .orElseThrow().mutations().get(0).content();

        assertThat(pom).doesNotContain("mainClass");
    }

    @Test
    void noDependenciesAreInvented_becauseTheSourcesDecideThose() {
        // A missing package raises PACKAGE_DOES_NOT_EXIST, which the discovery chain and T4-DH's
        // repository interface own. Bootstrapping makes a VALID build file, not an opinionated one.
        Map<String, String> sources = Map.of(
                "src/main/java/com/flightsim/Main.java",
                "package com.flightsim;\nimport org.lwjgl.opengl.GL11;\npublic class Main {}\n");

        String pom = resolver.resolve(cls(Kind.MISSING_POM), null, null, ctx(sources))
                .orElseThrow().mutations().get(0).content();

        assertThat(pom).doesNotContain("<dependency>");
        assertThat(pom).doesNotContain("lwjgl");   // not even the one it can see, on purpose
    }

    @Test
    void theCompilerReleaseIsTheRunningJvm_anEnvironmentalFactNotAnOpinion() {
        String pom = resolver.resolve(cls(Kind.MISSING_POM), null, null, ctx(Map.of()))
                .orElseThrow().mutations().get(0).content();

        assertThat(pom).contains("<maven.compiler.release>" + Runtime.version().feature()
                + "</maven.compiler.release>");
    }

    @Test
    void withNoSourcesAtAll_itStillWritesAValidNeutralPom() {
        String pom = resolver.resolve(cls(Kind.MISSING_POM), null, null, ctx(Map.of()))
                .orElseThrow().mutations().get(0).content();

        assertThat(pom).contains("<modelVersion>4.0.0</modelVersion>").contains("</project>");
        assertThat(pom).doesNotContain("spring");
    }

    // ── unchanged contract ───────────────────────────────────────────────────

    @Test
    void resolve_missingPom_writesPomXmlWithInjectorProvenance() {
        Optional<FixProposal> out = resolver.resolve(cls(Kind.MISSING_POM), null, null, ctx(Map.of()));

        assertThat(out).isPresent();
        FixProposal proposal = out.get();
        assertThat(proposal.mutations()).hasSize(1);
        assertThat(proposal.mutations().get(0).path()).isEqualTo("pom.xml");
        assertThat(proposal.record().rule()).isEqualTo("bootstrap-pom");
        assertThat(proposal.record().kind()).isEqualTo(Kind.MISSING_POM);
        assertThat(proposal.record().touchedPaths()).containsExactly("pom.xml");
    }

    @Test
    void resolve_nonMissingPomKind_returnsEmpty() {
        CycleContextData c = ctx(Map.of());
        assertThat(resolver.resolve(cls(Kind.PACKAGE_DOES_NOT_EXIST), "jakarta.persistence", null, c)).isEmpty();
        assertThat(resolver.resolve(cls(Kind.CANNOT_FIND_SYMBOL), "Foo", null, c)).isEmpty();
        assertThat(resolver.resolve(cls(Kind.UNKNOWN), null, null, c)).isEmpty();
    }
}
