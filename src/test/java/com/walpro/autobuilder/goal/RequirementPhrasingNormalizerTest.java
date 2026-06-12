package com.walpro.autobuilder.goal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementPhrasingNormalizerTest {

    @Test
    void defectStatement_mainClauseNegation_rewrittenToModalRequirement() {
        // The exact description that blocked root closure in the 2026-06-12 01:44 run.
        assertThat(RequirementPhrasingNormalizer.normalize(
                "GET /loans?memberId={id} endpoint does not list all loans for a member including returned ones"))
                .isEqualTo("GET /loans?memberId={id} endpoint must list all loans for a member including returned ones");
    }

    @Test
    void pluralAndCopulaNegations_rewritten() {
        assertThat(RequirementPhrasingNormalizer.normalize("Returned loans are not included in the response"))
                .isEqualTo("Returned loans must be included in the response");
        assertThat(RequirementPhrasingNormalizer.normalize("The validation rule is not applied on update"))
                .isEqualTo("The validation rule must be applied on update");
        assertThat(RequirementPhrasingNormalizer.normalize("The endpoint fails to reject duplicate identifiers"))
                .isEqualTo("The endpoint must reject duplicate identifiers");
    }

    @Test
    void subordinateClauseNegation_isARequirement_unchanged() {
        String requirement = "GET /books/{id} returns HTTP 404 when book does not exist";
        assertThat(RequirementPhrasingNormalizer.normalize(requirement)).isEqualTo(requirement);

        String relative = "Requests that do not carry a body are rejected with HTTP 400";
        assertThat(RequirementPhrasingNormalizer.normalize(relative)).isEqualTo(relative);
    }

    @Test
    void constraintAndModalPhrasings_alreadyRequirements_unchanged() {
        String constraint = "A member cannot borrow more than 3 books";
        assertThat(RequirementPhrasingNormalizer.normalize(constraint)).isEqualTo(constraint);

        String modal = "The response should not expose internal identifiers";
        assertThat(RequirementPhrasingNormalizer.normalize(modal)).isEqualTo(modal);
    }

    @Test
    void imperativeTaskDescriptions_unchanged() {
        String task = "Fix the endpoint so it does not drop returned loans";
        assertThat(RequirementPhrasingNormalizer.normalize(task)).isEqualTo(task);

        String buildFix = "[BUILD_FIX] Resolve missing symbol Foo referenced by Bar";
        assertThat(RequirementPhrasingNormalizer.normalize(buildFix)).isEqualTo(buildFix);
    }

    @Test
    void bracketPrefixPreserved_whenBodyIsRewritten() {
        assertThat(RequirementPhrasingNormalizer.normalize("[ANALYSIS] The endpoint does not echo the stored value"))
                .isEqualTo("[ANALYSIS] The endpoint must echo the stored value");
    }

    @Test
    void noNegation_nullAndBlank_unchanged() {
        String plain = "The endpoint returns all matching records";
        assertThat(RequirementPhrasingNormalizer.normalize(plain)).isEqualTo(plain);
        assertThat(RequirementPhrasingNormalizer.normalize(null)).isNull();
        assertThat(RequirementPhrasingNormalizer.normalize("  ")).isEqualTo("  ");
    }
}
