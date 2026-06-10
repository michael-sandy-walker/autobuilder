package com.walpro.autobuilder.graph.evaluation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CriterionResultEntityTest {

    @Test
    void keyOf_isStableAcrossCosmeticRewording() {
        // T4-BP step 2: casing, punctuation, and whitespace differences map to the same key.
        String a = CriterionResultEntity.keyOf("Returns HTTP 404 when the loan is missing.");
        String b = CriterionResultEntity.keyOf("returns http 404, when the loan is missing");
        String c = CriterionResultEntity.keyOf("  Returns   HTTP 404 when the loan is missing ");

        assertThat(a).isEqualTo(b).isEqualTo(c).isNotBlank();
    }

    @Test
    void keyOf_distinguishesDifferentRequirements() {
        assertThat(CriterionResultEntity.keyOf("Returns 404 when loan missing"))
                .isNotEqualTo(CriterionResultEntity.keyOf("Returns 409 when loan already returned"));
    }

    @Test
    void keyOf_nullAndBlank_returnEmpty() {
        assertThat(CriterionResultEntity.keyOf(null)).isEmpty();
        assertThat(CriterionResultEntity.keyOf("   ")).isEmpty();
    }

    @Test
    void criterionKey_derivesFromText() {
        CriterionResultEntity e = new CriterionResultEntity("a-1", 1, "Some criterion", true, "c-1", "sg-1");
        assertThat(e.criterionKey()).isEqualTo(CriterionResultEntity.keyOf("Some criterion"));
    }
}
