package com.walpro.autobuilder.goal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * T3-BZ: what a relocation must do to text the loop has already banked.
 *
 * <p>The criterion in {@link #theFieldCriterionFollowsTheTypeItDescribes()} is verbatim from run
 * {@code 2026-08-31_15-02-34}, where the placement mover relocated {@code Book} and this sentence
 * went on asserting the vacated path for the rest of the run.
 */
class RequirementPhrasingNormalizerRelocationTest {

    private static final String OLD = "src/main/java/com/example/library/domain/Book.java";
    private static final String NEW = "src/main/java/com/example/library/domain/model/Book.java";

    @Test
    void theFieldCriterionFollowsTheTypeItDescribes() {
        String banked = "A Java class `Book` exists at `src/main/java/com/example/library/domain/Book.java`.";

        String rewritten = RequirementPhrasingNormalizer.rewriteRelocatedReferences(banked, OLD, NEW);

        assertThat(rewritten).isEqualTo(
                "A Java class `Book` exists at `src/main/java/com/example/library/domain/model/Book.java`.");
    }

    @Test
    void theFullyQualifiedNameIsRewrittenToo() {
        String banked = "The class com.example.library.domain.Book is returned by the controller.";

        String rewritten = RequirementPhrasingNormalizer.rewriteRelocatedReferences(banked, OLD, NEW);

        assertThat(rewritten).contains("com.example.library.domain.model.Book");
        assertThat(rewritten).doesNotContain("com.example.library.domain.Book ");
    }

    @Test
    void aTypeWhoseNameMerelyStartsWithTheMovedOneIsNotDraggedAlong() {
        // The boundary the mover itself guards in source must hold in prose too.
        String banked = "com.example.library.domain.BookRepository stores books.";

        assertThat(RequirementPhrasingNormalizer.rewriteRelocatedReferences(banked, OLD, NEW))
                .isEqualTo(banked);
    }

    @Test
    void textAboutAnotherFileIsUntouched() {
        String banked = "A Java class `Member` exists at `src/main/java/com/example/library/domain/Member.java`.";

        assertThat(RequirementPhrasingNormalizer.rewriteRelocatedReferences(banked, OLD, NEW))
                .isEqualTo(banked);
    }

    @Test
    void rewritingTwiceChangesNothingTheSecondTime() {
        String banked = "A Java class `Book` exists at `" + OLD + "`.";

        String once = RequirementPhrasingNormalizer.rewriteRelocatedReferences(banked, OLD, NEW);
        String twice = RequirementPhrasingNormalizer.rewriteRelocatedReferences(once, OLD, NEW);

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(RequirementPhrasingNormalizer.rewriteRelocatedReferences(null, OLD, NEW)).isNull();
        assertThat(RequirementPhrasingNormalizer.rewriteRelocatedReferences("  ", OLD, NEW)).isEqualTo("  ");
    }
}
