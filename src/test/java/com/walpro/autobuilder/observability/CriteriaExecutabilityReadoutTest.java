package com.walpro.autobuilder.observability;

import com.walpro.autobuilder.goal.GoalEntity;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CriteriaExecutabilityReadoutTest {

    private static GoalEntity goal(String... criteria) {
        GoalEntity g = new GoalEntity();
        g.setDescription("a work item");
        Map<String, Object> meta = new HashMap<>();
        if (criteria.length > 0) meta.put("acceptanceCriteria", List.of((Object[]) criteria));
        g.setMetadata(meta);
        return g;
    }

    @Test
    void separatesTheTwoCausesOfAnUnverifiableCriterion() {
        // The distinction the readout exists for: a criterion that addresses no endpoint is an
        // AUTHORING problem, and one that addresses an endpoint but does not parse is a PARSER
        // problem. Conflating them is the mistake this was written after making.
        var counts = CriteriaExecutabilityReadout.over(List.of(
                goal("POST /loans returns HTTP 404 when bookId does not exist"),   // executable
                goal("GET /books/{id} returns HTTP 200 or HTTP 404 if not found"), // endpoint, two codes
                goal("Book is stored in the in-memory repository")));              // no endpoint at all

        assertThat(counts.authored()).isEqualTo(3);
        assertThat(counts.namingEndpoint()).isEqualTo(2);
        assertThat(counts.executable()).isEqualTo(1);
        assertThat(counts.namedButUnparsed()).isEqualTo(1);
    }

    @Test
    void reproducesTheShapeOfTheRunThatWasDarkOnVerification() {
        // Run 2026-07-30_06-28-19 authored structural criteria where its siblings authored
        // behavioural ones, and nothing in the loop said so for its whole length.
        var structural = CriteriaExecutabilityReadout.over(List.of(
                goal("Book is stored in the in-memory repository and retrievable"),
                goal("BookController.java contains @RequestMapping annotation"),
                goal("LoanService.findLoansByMemberId() correctly retrieves loans")));

        assertThat(structural.executable()).isZero();
        assertThat(structural.executableShare()).isZero();
        assertThat(structural.namedButUnparsed()).isZero(); // the shortfall is authoring, not parsing
    }

    @Test
    void countsTheShareTheProbePathWillActuallySee() {
        var counts = CriteriaExecutabilityReadout.over(List.of(
                goal("POST /books returns HTTP 400 when the title is missing",
                        "POST /books returns HTTP 400 when the isbn is malformed"),
                goal("The service is well structured")));

        assertThat(counts.authored()).isEqualTo(3);
        assertThat(counts.executable()).isEqualTo(2);
        assertThat(counts.executableShare()).isEqualTo(2.0 / 3.0);
    }

    @Test
    void holdsNoOpinionWithoutCriteria() {
        assertThat(CriteriaExecutabilityReadout.over(null).isEmpty()).isTrue();
        assertThat(CriteriaExecutabilityReadout.over(List.of()).isEmpty()).isTrue();
        assertThat(CriteriaExecutabilityReadout.over(List.of(goal())).isEmpty()).isTrue();
        assertThat(CriteriaExecutabilityReadout.over(List.of(new GoalEntity())).isEmpty()).isTrue();
    }

    @Test
    void usesTheParserRatherThanASecondCopyOfItsRules() {
        // "returns 404" with no HTTP marker parses since T4-DB widened the pattern; a readout
        // carrying its own regex would drift from what the probe path sees.
        assertThat(CriteriaExecutabilityReadout.over(List.of(
                goal("POST /loans returns 404 Not Found when book or member does not exist")))
                .executable()).isEqualTo(1);
    }
}
