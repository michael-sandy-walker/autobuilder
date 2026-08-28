package com.walpro.autobuilder.protocol.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-FG: the evolution contract's worked examples demonstrate FORMAT and must prescribe no
 * ecosystem.
 *
 * <p>They used to render a web-framework controller in {@code com.example.demo} and a
 * {@code spring-boot-starter-parent} pom. That is a worked answer to a different question on a
 * target that is not a web service, and it was copied verbatim: run {@code 2026-08-28_03-51-21}
 * produced a 3D/OpenGL artifact whose pom carried {@code <artifactId>demo</artifactId>} and a
 * Spring Boot build plugin, after which the loop spent cycles trying to satisfy a packaging step
 * the target never asked for.
 */
class EvolutionContractNeutralityTest {

    @ParameterizedTest(name = "the contract names no \"{0}\"")
    @ValueSource(strings = {
            "spring", "Spring", "springframework",
            "RestController", "GetMapping", "RequestMapping", "SpringBootApplication",
            "starter-web", "starter-parent", "spring-boot-maven-plugin",
            "jakarta", "javax.persistence", "hibernate",
            "HelloController", "com.example.demo"
    })
    void theRenderedContractNamesNoFrameworkTheTargetDidNotAskFor(String forbidden) {
        assertThat(EvolutionProtocolContract.asPromptBlock())
                .as("evolution contract must not prescribe an ecosystem")
                .doesNotContain(forbidden);
    }

    @Test
    void theExamplesStillDemonstrateTheFormatTheyExistToTeach() {
        // Neutrality is worthless if it cost the examples their job: the fenced block with a
        // bracketed path is the whole point of the format rules.
        String block = EvolutionProtocolContract.asPromptBlock();

        assertThat(block).contains("```java [src/main/java/");
        assertThat(block).contains("```xml [pom.xml]");
        assertThat(block).contains("#Delete [");
    }

    @Test
    void theProjectsOwnPackageIsInterpolatedElsewhere_notGuessedInTheExamples() {
        // PACKAGE_COHERENCE interpolates establishedBasePackage() at render time; the examples
        // must not compete with it by prescribing a package of their own beyond the reserved
        // documentation namespace.
        String block = EvolutionProtocolContract.asPromptBlock();

        assertThat(block).doesNotContain("package com.example.demo;");
        assertThat(block).contains("com.example");   // reserved placeholder namespace only
    }
}
