package com.walpro.autobuilder.analysis.coverage;

import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeedCoverageAnalyzerTest {

    private final SeedCoverageAnalyzer analyzer = new SeedCoverageAnalyzer();

    /**
     * The real seed. Kept verbatim so the coverage findings pinned below are the ones a live run
     * produces, not the ones an invented specification would ([[feedback-llm-probe-realistic-context]]).
     */
    private static final String REAL_SEED = """
            Evolve the Java 21 Spring Boot application into a library book-lending REST API with three resource types.

            Books: POST /books (create; title required, max 120 chars; isbn required, must match \\d{13}), GET /books (list all),
            GET /books/{id} (single; 404 if absent).

            Members: POST /members (create; name required; email required, must be unique — 409 on duplicate), GET /members/{id}
            (404 if absent).

            Loans: POST /loans (lend a book; body contains bookId and memberId; 404 if either not found; 409 if book is already
            on loan; a member may hold at most 3 active loans — 422 if exceeded), POST /loans/{id}/return (mark returned; 404 if
            loan absent; 409 if already returned), GET /loans?memberId={id} (list all loans for a member, active and returned).

            All data stored in in-memory collections (no database). Invalid input returns HTTP 400 with JSON body {"message":
            "<reason>"}. Business-rule violations use the codes above with the same JSON shape.
            """;

    private static GoalEntity goal(String description, GoalStatus status, String... criteria) {
        GoalEntity g = new GoalEntity();
        g.setDescription(description);
        g.setStatus(status);
        Map<String, Object> meta = new HashMap<>();
        if (criteria.length > 0) meta.put("acceptanceCriteria", List.of((Object[]) criteria));
        g.setMetadata(meta);
        return g;
    }

    /**
     * The plan shape the frozen run {@code 2026-07-30_08-00-00} actually ended with: endpoints
     * named, the stated rules around them mostly not.
     */
    private static List<GoalEntity> realisticPlan() {
        List<GoalEntity> goals = new ArrayList<>();
        goals.add(goal("Implement the Book domain model with id, title, isbn fields", GoalStatus.COMPLETED));
        goals.add(goal("Implement POST /books returning 201 with the created book", GoalStatus.SUBSUMED));
        goals.add(goal("Implement GET /books returning list of all books", GoalStatus.SUBSUMED));
        goals.add(goal("Implement GET /books/{id} returning single book", GoalStatus.COMPLETED));
        goals.add(goal("Implement GET /members/{id} endpoint", GoalStatus.ACTIVE));
        goals.add(goal("Create the LoanController class with endpoints for POST /loans, "
                + "POST /loans/{id}/return, and GET /loans", GoalStatus.ACTIVE));
        goals.add(goal("The endpoint returns HTTP 404 when POST /loans has non-existent bookId",
                GoalStatus.SUBSUMED));
        goals.add(goal("The endpoint returns HTTP 409 when POST /loans attempts to loan an already-loaned book",
                GoalStatus.ACTIVE));
        goals.add(goal("Implement a runnable application entry point", GoalStatus.COMPLETED));
        return goals;
    }

    private static boolean reports(List<SeedCoverageReadout.Finding> findings, String fragment) {
        return findings.stream().anyMatch(f -> f.clause().contains(fragment));
    }

    @Test
    void reports_the_requirements_that_failed_in_every_frozen_run() {
        SeedCoverageReadout readout = analyzer.analyze(REAL_SEED, realisticPlan());

        // Smoke checks [14]/[15] — POST /loans with an unknown book or member must answer 404.
        // Failed in all three frozen runs; no work item states the rule.
        assertThat(reports(readout.unclaimed(), "404 if either not found")).isTrue();
        // Smoke check [19] — the fourth concurrent loan must answer 422. Same story.
        assertThat(reports(readout.unclaimed(), "at most 3 active loans")).isTrue();
    }

    @Test
    void names_the_absent_terms_so_a_reader_can_see_why_a_clause_was_selected() {
        SeedCoverageReadout readout = analyzer.analyze(REAL_SEED, realisticPlan());

        SeedCoverageReadout.Finding uniqueEmail = readout.unclaimed().stream()
                .filter(f -> f.clause().contains("must be unique"))
                .findFirst().orElseThrow();
        assertThat(uniqueEmail.missingTerms()).contains("email", "unique", "duplicate");
    }

    @Test
    void a_clause_the_plan_articulates_is_not_reported_as_unclaimed() {
        List<GoalEntity> plan = realisticPlan();
        plan.add(goal("Validate that isbn is required and must match the \\d{13} pattern", GoalStatus.ACTIVE));

        SeedCoverageReadout readout = analyzer.analyze(REAL_SEED, plan);

        assertThat(reports(readout.unclaimed(), "must match")).isFalse();
    }

    @Test
    void an_articulated_clause_with_no_criterion_behind_it_is_reported_as_unprovable() {
        List<GoalEntity> plan = realisticPlan();
        // Every term of the "409 if already returned" clause is now in a description, but nothing
        // declares a criterion that would prove it.
        plan.add(goal("Return 409 if the loan was already returned, and support GET /loans?memberId={id} "
                + "listing all loans for a member, active and returned", GoalStatus.ACTIVE));

        SeedCoverageReadout readout = analyzer.analyze(REAL_SEED, plan);

        assertThat(reports(readout.unclaimed(), "already returned")).isFalse();
        assertThat(reports(readout.unprovable(), "already returned")).isTrue();
    }

    @Test
    void a_criterion_covering_the_clause_clears_it_from_both_sections() {
        List<GoalEntity> plan = realisticPlan();
        plan.add(goal("Return 409 if the loan was already returned, and support GET /loans?memberId={id} "
                        + "listing all loans for a member, active and returned", GoalStatus.ACTIVE,
                "POST /loans/{id}/return answers 409 when the loan was already returned",
                "GET /loans?memberId={id} lists all active and returned loans for that member"));

        SeedCoverageReadout readout = analyzer.analyze(REAL_SEED, plan);

        assertThat(reports(readout.unclaimed(), "already returned")).isFalse();
        assertThat(reports(readout.unprovable(), "already returned")).isFalse();
    }

    @Test
    void terminal_goals_count_as_articulating_their_clause() {
        // A requirement addressed by work that has since been given up is still articulated by the
        // plan; reporting it as unclaimed would re-propose abandoned work every interval.
        List<GoalEntity> plan = realisticPlan();
        assertThat(reports(analyzer.analyze(REAL_SEED, plan).unclaimed(), "must match")).isTrue();

        plan.add(goal("Validate that isbn is required and must match the \\d{13} pattern",
                GoalStatus.GIVEN_UP));

        assertThat(reports(analyzer.analyze(REAL_SEED, plan).unclaimed(), "must match")).isFalse();
    }

    @Test
    void findings_are_stable_across_repeated_analysis_of_the_same_state() {
        // The readout reaches prompts and may reach work items; a finding whose text moves between
        // firings defeats every dedup layer that keys on it (run 2026-07-30_04-56-38 minted twins
        // from a finding carrying a timestamp, a pid and an ephemeral port).
        SeedCoverageReadout first = analyzer.analyze(REAL_SEED, realisticPlan());
        SeedCoverageReadout second = analyzer.analyze(REAL_SEED, realisticPlan());

        assertThat(second.unclaimed()).isEqualTo(first.unclaimed());
        assertThat(second.unprovable()).isEqualTo(first.unprovable());
    }

    @Test
    void ordering_of_the_plan_does_not_change_the_findings() {
        List<GoalEntity> forward = realisticPlan();
        List<GoalEntity> reversed = new ArrayList<>(forward);
        java.util.Collections.reverse(reversed);

        assertThat(analyzer.analyze(REAL_SEED, reversed).unclaimed())
                .isEqualTo(analyzer.analyze(REAL_SEED, forward).unclaimed());
    }

    @Test
    void holds_no_opinion_when_there_is_nothing_to_compare() {
        assertThat(analyzer.analyze(null, realisticPlan()).isEmpty()).isTrue();
        assertThat(analyzer.analyze("   ", realisticPlan()).isEmpty()).isTrue();
        assertThat(analyzer.analyze(REAL_SEED, List.of()).isEmpty()).isTrue();
        assertThat(analyzer.analyze(REAL_SEED, null).isEmpty()).isTrue();
        // A plan whose goals carry no description at all cannot ground a comparison.
        assertThat(analyzer.analyze(REAL_SEED, List.of(new GoalEntity())).isEmpty()).isTrue();
    }

    @Test
    void a_specification_it_cannot_split_into_clauses_yields_no_opinion() {
        assertThat(analyzer.analyze("Do it.", realisticPlan()).isEmpty()).isTrue();
    }

    @Test
    void carries_no_domain_vocabulary_of_its_own() {
        // A specification from an unrelated domain must produce findings in ITS words — the check
        // holds no opinion about endpoints, status codes or resources.
        String seed = "Build a command line tool that reads a CSV file and writes a sorted TSV file. "
                + "Rows with a malformed timestamp are skipped and counted. "
                + "The exit status is 2 when any row was skipped.";
        List<GoalEntity> plan = List.of(
                goal("Implement reading of the CSV file", GoalStatus.ACTIVE),
                goal("Implement writing the sorted TSV file", GoalStatus.ACTIVE));

        SeedCoverageReadout readout = analyzer.analyze(seed, plan);

        assertThat(reports(readout.unclaimed(), "malformed timestamp")).isTrue();
        assertThat(reports(readout.unclaimed(), "exit status")).isTrue();
    }

    @Test
    void reports_every_finding_and_leaves_the_budget_to_the_consumer() {
        // Capping the analyzer at eight removed one of the two always-failing requirements, in a
        // different run each time. It reports what it found; SeedCoverageBlock pays for the prompt.
        SeedCoverageReadout readout = analyzer.analyze(REAL_SEED, realisticPlan());

        assertThat(reports(readout.unclaimed(), "404 if either not found")).isTrue();
        assertThat(reports(readout.unclaimed(), "at most 3 active loans")).isTrue();
        assertThat(readout.unclaimed().size()).isGreaterThan(8);
    }

    @Test
    void orders_findings_by_how_far_the_plan_already_engages_with_the_clause() {
        // A half-articulated requirement outranks framing prose the plan will never mirror, so a
        // consumer that must truncate drops the least useful line first.
        SeedCoverageReadout readout = analyzer.analyze(REAL_SEED, realisticPlan());

        List<String> clauses = readout.unclaimed().stream()
                .map(SeedCoverageReadout.Finding::clause).toList();
        int framing = indexOfContaining(clauses, "Evolve the Java");
        int notFound = indexOfContaining(clauses, "404 if either not found");
        assertThat(notFound).isLessThan(framing);
    }

    private static int indexOfContaining(List<String> clauses, String fragment) {
        for (int i = 0; i < clauses.size(); i++) {
            if (clauses.get(i).contains(fragment)) return i;
        }
        throw new AssertionError("no clause containing: " + fragment);
    }
}
