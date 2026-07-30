package com.walpro.autobuilder.protocol.coverage;

import com.walpro.autobuilder.analysis.coverage.SeedCoverageReadout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeedCoverageBlockTest {

    private static SeedCoverageReadout.Finding finding(String clause, String... terms) {
        return new SeedCoverageReadout.Finding(clause, List.of(terms));
    }

    @Test
    void says_nothing_when_there_is_nothing_to_say() {
        assertThat(SeedCoverageBlock.render(null, SeedCoverageBlock.Audience.PLANNER)).isNull();
        assertThat(SeedCoverageBlock.render(SeedCoverageReadout.empty(),
                SeedCoverageBlock.Audience.PLANNER)).isNull();
    }

    @Test
    void renders_the_clause_verbatim_with_the_terms_that_selected_it() {
        SeedCoverageReadout readout = new SeedCoverageReadout(
                List.of(finding("404 if either not found;", "either", "found")), List.of());

        String block = SeedCoverageBlock.render(readout, SeedCoverageBlock.Audience.PLANNER);

        assertThat(block).contains("--- SPECIFICATION COVERAGE ---");
        assertThat(block).contains("404 if either not found;");
        assertThat(block).contains("absent from the plan: either, found");
    }

    @Test
    void separates_the_two_findings_so_neither_reads_as_the_other() {
        SeedCoverageReadout readout = new SeedCoverageReadout(
                List.of(finding("404 if either not found;", "either")),
                List.of(finding("409 if already returned", "returned")));

        String block = SeedCoverageBlock.render(readout, SeedCoverageBlock.Audience.ANALYSIS);

        assertThat(block).contains("NOT ARTICULATED BY ANY WORK ITEM");
        assertThat(block).contains("ARTICULATED BUT NO ACCEPTANCE CRITERION WOULD PROVE IT");
        assertThat(block.indexOf("404 if either")).isLessThan(block.indexOf("409 if already"));
    }

    @Test
    void states_that_it_describes_the_plan_rather_than_the_code() {
        // Measured on real runs: about four findings per run name behaviour the artifact does
        // implement inside a broadly worded work item. A reader told otherwise would chase them.
        String block = SeedCoverageBlock.render(
                new SeedCoverageReadout(List.of(finding("isbn required", "isbn")), List.of()),
                SeedCoverageBlock.Audience.EVOLUTION);

        assertThat(block).contains("PLAN, not the code");
    }

    @Test
    void each_audience_gets_its_own_instruction() {
        SeedCoverageReadout readout = new SeedCoverageReadout(
                List.of(finding("isbn required", "isbn")), List.of());

        assertThat(SeedCoverageBlock.render(readout, SeedCoverageBlock.Audience.PLANNER))
                .contains("before decomposing further");
        assertThat(SeedCoverageBlock.render(readout, SeedCoverageBlock.Audience.EVOLUTION))
                .contains("widen a change");
        assertThat(SeedCoverageBlock.render(readout, SeedCoverageBlock.Audience.ANALYSIS))
                .contains("drifted from the specification");
    }

    @Test
    void truncation_is_announced_rather_than_silent() {
        List<SeedCoverageReadout.Finding> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(finding("Clause number " + i + " of a specification long enough to exceed "
                    + "the block's character budget on its own", "term" + i));
        }

        String block = SeedCoverageBlock.render(new SeedCoverageReadout(many, List.of()),
                SeedCoverageBlock.Audience.PLANNER);

        assertThat(block).contains("further clause(s) omitted here");
        assertThat(block.length()).isLessThan(2600);
        // The head of the list survives — findings arrive most-engaged first.
        assertThat(block).contains("Clause number 0 ");
    }

    @Test
    void a_single_oversized_finding_is_still_rendered() {
        // Truncating to nothing would turn "the check ran and found something" into silence,
        // which reads identically to the check never having run.
        String huge = "A specification clause of " + "x".repeat(4000);

        String block = SeedCoverageBlock.render(
                new SeedCoverageReadout(List.of(finding(huge, "xxx")), List.of()),
                SeedCoverageBlock.Audience.PLANNER);

        assertThat(block).contains("A specification clause of");
    }

    @Test
    void the_two_sections_share_one_budget() {
        List<SeedCoverageReadout.Finding> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(finding("Unclaimed clause " + i + " padded out to consume the whole budget "
                    + "before the second section is reached", "term" + i));
        }

        // Long enough that it cannot fit in whatever slack the first section leaves behind.
        String late = "A second-section clause too long to fit the remaining budget " + "y".repeat(400);

        String block = SeedCoverageBlock.render(
                new SeedCoverageReadout(many, List.of(finding(late, "late"))),
                SeedCoverageBlock.Audience.PLANNER);

        assertThat(block).doesNotContain("A second-section clause");
        assertThat(block.length()).isLessThan(2600);
    }
}
