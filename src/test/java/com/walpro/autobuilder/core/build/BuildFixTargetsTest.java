package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-AU initial slice: error-class-aware repair-target selection. */
class BuildFixTargetsTest {

    @Test
    void missingPom_returnsLiteralPomXml_whenNoCandidatesContainIt() {
        // The 2026-05-07 03:53 run case: LLM proposed only Java files, MISSING_POM rolled back.
        // Without T4-AU, the BUILD_FIX subgoal named a Java file. With T4-AU, it names pom.xml.
        String result = BuildFixTargets.preferredRepairTarget(
                BuildErrorClassifier.Kind.MISSING_POM,
                List.of("src/main/java/com/example/BookController.java",
                        "src/main/java/com/example/Book.java"));
        assertThat(result).isEqualTo("pom.xml");
    }

    @Test
    void missingPom_prefersExistingPomXmlCandidate_whenPresent() {
        // When the LLM did include pom.xml in its mutation, return THAT candidate so any
        // path-prefix the merge layer added is preserved.
        String result = BuildFixTargets.preferredRepairTarget(
                BuildErrorClassifier.Kind.MISSING_POM,
                List.of("src/main/java/Foo.java", "pom.xml"));
        assertThat(result).isEqualTo("pom.xml");
    }

    @Test
    void missingPom_returnsLiteralPomXml_whenCandidatesEmpty() {
        String result = BuildFixTargets.preferredRepairTarget(
                BuildErrorClassifier.Kind.MISSING_POM, List.of());
        assertThat(result).isEqualTo("pom.xml");
    }

    @Test
    void missingPom_returnsLiteralPomXml_whenCandidatesNull() {
        String result = BuildFixTargets.preferredRepairTarget(
                BuildErrorClassifier.Kind.MISSING_POM, null);
        assertThat(result).isEqualTo("pom.xml");
    }

    @Test
    void otherKinds_returnNull_soCallerFallsBack() {
        // CANNOT_FIND_SYMBOL, PACKAGE_DOES_NOT_EXIST, UNKNOWN: no class-aware preference today.
        // Caller falls back to its own logic (stderr file-coord prefix, then first candidate).
        assertThat(BuildFixTargets.preferredRepairTarget(
                BuildErrorClassifier.Kind.CANNOT_FIND_SYMBOL,
                List.of("Foo.java"))).isNull();
        assertThat(BuildFixTargets.preferredRepairTarget(
                BuildErrorClassifier.Kind.PACKAGE_DOES_NOT_EXIST,
                List.of("Foo.java"))).isNull();
        assertThat(BuildFixTargets.preferredRepairTarget(
                BuildErrorClassifier.Kind.UNKNOWN,
                List.of("Foo.java"))).isNull();
    }

    @Test
    void nullKind_returnsNull() {
        assertThat(BuildFixTargets.preferredRepairTarget(null, List.of("Foo.java"))).isNull();
    }

    @Test
    void missingPom_basenameMatch_isExactNotSubstring() {
        // A file ending in "pom.xml" but with extra characters before the extension must
        // NOT match — basename equality only.
        String result = BuildFixTargets.preferredRepairTarget(
                BuildErrorClassifier.Kind.MISSING_POM,
                List.of("src/main/parent-pom.xml", "src/main/java/Foo.java"));
        // "parent-pom.xml" basename != "pom.xml" → fall through to literal "pom.xml"
        assertThat(result).isEqualTo("pom.xml");
    }
}
