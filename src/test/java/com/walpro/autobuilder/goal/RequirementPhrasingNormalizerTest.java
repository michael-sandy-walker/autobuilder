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

    @Test
    void structuralPlaceholder_recognizedVerbatimAndWithModelNoise() {
        // The exact sentinel the planner contract prompts, as it appeared in every one of
        // run 4's seven T4-BP-3 closure blocks (2026-07-03).
        assertThat(RequirementPhrasingNormalizer.isStructuralCriterionPlaceholder(
                RequirementPhrasingNormalizer.STRUCTURAL_CRITERION_PLACEHOLDER)).isTrue();

        // Round-tripping through the model adds bullet prefixes, case drift and dash variants.
        assertThat(RequirementPhrasingNormalizer.isStructuralCriterionPlaceholder(
                "* (subgoal is structural — no observable output criterion)")).isTrue();
        assertThat(RequirementPhrasingNormalizer.isStructuralCriterionPlaceholder(
                "  (Subgoal is structural - no observable output criterion) ")).isTrue();
        assertThat(RequirementPhrasingNormalizer.isStructuralCriterionPlaceholder(
                "(subgoal is structural -- no  observable output criterion)")).isTrue();
    }

    @Test
    void structuralPlaceholder_realCriteriaAndNullNotMatched() {
        assertThat(RequirementPhrasingNormalizer.isStructuralCriterionPlaceholder(
                "POST /books returns HTTP 201 with the stored book")).isFalse();
        assertThat(RequirementPhrasingNormalizer.isStructuralCriterionPlaceholder(
                "The subgoal is structural and reorganizes packages")).isFalse();
        assertThat(RequirementPhrasingNormalizer.isStructuralCriterionPlaceholder(null)).isFalse();
        assertThat(RequirementPhrasingNormalizer.isStructuralCriterionPlaceholder("  ")).isFalse();
    }

    // ── T4-DJ: an invented file path is not a requirement ────────────────────

    @Test
    void anInventedFilePathIsStripped_theFileRequirementSurvives() {
        // Verbatim from run 2026-08-28_05-27-49, where a CORRECT file was scored 0.42 and
        // rejected for sitting where the loop's own package constraint had put it.
        assertThat(RequirementPhrasingNormalizer.stripInventedFilePath(
                "CockpitHUD.java exists at src/main/java/com/flightsim/ui/CockpitHUD.java"))
                .isEqualTo("CockpitHUD.java exists");
    }

    @Test
    void aBehaviouralPathIsNotAFileLayoutClaim() {
        String behavioural = "The application writes its output to /var/log/app.log";
        assertThat(RequirementPhrasingNormalizer.stripInventedFilePath(behavioural)).isEqualTo(behavioural);
    }

    @Test
    void aPathNamingADifferentFileIsLeftAlone() {
        // Conservative: only a path naming the SAME file the criterion is about is layout noise.
        String other = "Config.java exists at src/main/java/com/other/Different.java";
        assertThat(RequirementPhrasingNormalizer.stripInventedFilePath(other)).isEqualTo(other);
    }

    @Test
    void criteriaThatSayNothingAboutFiles_areUntouched() {
        String behaviour = "GET /books returns HTTP 200 with a JSON array";
        assertThat(RequirementPhrasingNormalizer.stripInventedFilePath(behaviour)).isEqualTo(behaviour);
        assertThat(RequirementPhrasingNormalizer.stripInventedFilePath(null)).isNull();
    }
}
