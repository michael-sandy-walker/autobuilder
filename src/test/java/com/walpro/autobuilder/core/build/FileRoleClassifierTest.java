package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-AU: role classification + error-class-aware repair-target selection, all driven by the
 * registry data. The three MISSING_POM cases are carried over verbatim from the deleted
 * {@code BuildFixTargetsTest} (this class is that slice's documented promotion).
 */
class FileRoleClassifierTest {

    private final FileRoleClassifier classifier = TestBuildToolRegistry.classifier();

    // --- rolesOf -------------------------------------------------------------------------

    @Test
    void rolesOf_buildDescriptors_atAnyDepth() {
        assertThat(classifier.rolesOf("pom.xml")).contains(FileRole.BUILD_DESCRIPTOR);
        assertThat(classifier.rolesOf("module-a/pom.xml")).contains(FileRole.BUILD_DESCRIPTOR);
        assertThat(classifier.rolesOf("settings.gradle.kts")).contains(FileRole.BUILD_DESCRIPTOR);
    }

    @Test
    void rolesOf_sourceAndTest() {
        assertThat(classifier.rolesOf("src/main/java/com/example/Foo.java"))
                .contains(FileRole.SOURCE)
                .doesNotContain(FileRole.TEST);
        assertThat(classifier.rolesOf("src/test/java/com/example/FooTest.java"))
                .contains(FileRole.SOURCE, FileRole.TEST);
    }

    @Test
    void rolesOf_generatedOutput_rootSegmentOnly() {
        assertThat(classifier.rolesOf("target/classes/com/example/Foo.class"))
                .contains(FileRole.GENERATED_OUTPUT);
        // A package directory sharing an output-dir name must NOT classify as generated.
        assertThat(classifier.rolesOf("src/main/java/com/target/Foo.java"))
                .doesNotContain(FileRole.GENERATED_OUTPUT);
    }

    @Test
    void rolesOf_resource() {
        assertThat(classifier.rolesOf("src/main/resources/application.yml"))
                .contains(FileRole.RESOURCE);
    }

    @Test
    void rolesOf_unknownAndNull() {
        assertThat(classifier.rolesOf("README.md")).containsExactly(FileRole.UNKNOWN);
        assertThat(classifier.rolesOf(null)).containsExactly(FileRole.UNKNOWN);
        assertThat(classifier.rolesOf("  ")).containsExactly(FileRole.UNKNOWN);
    }

    // --- preferredRepairTarget (carried over from BuildFixTargetsTest) --------------------

    @Test
    void missingPom_returnsPrimaryDescriptor_whenNoCandidatesContainIt() {
        // The 2026-05-07 03:53 run case: LLM proposed only Java files, MISSING_POM rolled back.
        String result = classifier.preferredRepairTarget(
                BuildErrorClassifier.Kind.MISSING_POM,
                List.of("src/main/java/com/example/BookController.java",
                        "src/main/java/com/example/Book.java"));
        assertThat(result).isEqualTo("pom.xml");
    }

    @Test
    void missingPom_prefersExistingDescriptorCandidate_whenPresent() {
        String result = classifier.preferredRepairTarget(
                BuildErrorClassifier.Kind.MISSING_POM,
                List.of("src/main/java/Foo.java", "pom.xml"));
        assertThat(result).isEqualTo("pom.xml");
    }

    @Test
    void missingPom_returnsPrimaryDescriptor_whenCandidatesEmpty() {
        String result = classifier.preferredRepairTarget(
                BuildErrorClassifier.Kind.MISSING_POM, List.of());
        assertThat(result).isEqualTo("pom.xml");
    }

    @Test
    void otherKinds_haveNoStructuralPreference() {
        assertThat(classifier.preferredRepairTarget(
                BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL, List.of("pom.xml"))).isNull();
        assertThat(classifier.preferredRepairTarget(null, List.of("pom.xml"))).isNull();
    }

    // --- preferredFallbackTarget ----------------------------------------------------------

    @Test
    void fallback_symbolKind_prefersNonTestSourceOverPositionalOrder() {
        String result = classifier.preferredFallbackTarget(
                BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL,
                List.of("pom.xml",
                        "src/test/java/com/example/FooTest.java",
                        "src/main/java/com/example/Foo.java"));
        assertThat(result).isEqualTo("src/main/java/com/example/Foo.java");
    }

    @Test
    void fallback_symbolKind_acceptsTestSource_whenNoMainSourceCandidate() {
        String result = classifier.preferredFallbackTarget(
                BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL,
                List.of("pom.xml", "src/test/java/com/example/FooTest.java"));
        assertThat(result).isEqualTo("src/test/java/com/example/FooTest.java");
    }

    @Test
    void fallback_symbolKind_firstNonBlank_whenNoSourceCandidate() {
        String result = classifier.preferredFallbackTarget(
                BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL,
                List.of(" ", "pom.xml"));
        assertThat(result).isEqualTo("pom.xml");
    }

    @Test
    void fallback_otherKinds_firstNonBlank() {
        assertThat(classifier.preferredFallbackTarget(
                BuildErrorClassifier.Kind.UNKNOWN,
                List.of("", "src/main/java/A.java", "pom.xml")))
                .isEqualTo("src/main/java/A.java");
        assertThat(classifier.preferredFallbackTarget(null, null)).isNull();
    }
}
