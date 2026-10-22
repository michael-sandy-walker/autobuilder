package com.walpro.autobuilder.core.merge.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a session decided, in words an integration goal carries: confirmed decisions only. */
class SessionDecisionsTest {

    private static MergeSession.Edit e(String op, Map<String, Object> args) {
        return new MergeSession.Edit(op, "operator", Instant.parse("2026-10-22T00:00:00Z"), args);
    }

    @Test
    void confirmedLinksAndSuperNodesBecomeObligations_proposalsDoNot() {
        List<MergeSession.Edit> log = new ArrayList<>();
        for (String p : List.of("wick", "lamp")) {
            log.add(e("addNode", Map.of("id", p, "kind", "PROJECT", "project", p, "title", p)));
            log.add(e("addNode", Map.of("id", p + ":Heat", "kind", "TYPE", "project", p, "title", "Heat")));
            log.add(e("addNode", Map.of("id", p + ":Span", "kind", "TYPE", "project", p, "title", "Span")));
        }
        log.add(e("addEdge", Map.of("id", "l1", "from", "wick:Span", "to", "lamp:Span", "kind", "LINK",
                "metadata", Map.of("status", "confirmed", "action", "UNIFY"))));
        log.add(e("addEdge", Map.of("id", "l2", "from", "wick:Heat", "to", "lamp:Heat", "kind", "LINK",
                "metadata", Map.of("status", "confirmed", "action", "ADAPT_ONE", "unit", "kelvin", "note", "convert at the boundary"))));
        log.add(e("addEdge", Map.of("id", "l3", "from", "wick", "to", "lamp", "kind", "LINK", "metadata", Map.of("status", "proposed"))));
        log.add(e("addSuper", Map.of("id", "s1", "title", "Warmth", "children", List.of("wick:Heat", "lamp:Heat"),
                "metadata", Map.of("status", "confirmed", "description", "one measure of heat", "criteria", List.of("in kelvin")))));
        log.add(e("addSuper", Map.of("id", "s2", "title", "Draft", "children", List.of("wick:Span", "lamp:Span"),
                "metadata", Map.of("status", "proposed"))));
        log.add(e("addSuper", Map.of("id", "s3", "title", "Spans", "children", List.of("wick:Span", "lamp:Span"))));   // the operator's own

        List<String> out = SessionDecisions.obligations(MergeSession.replay(log));

        assertThat(out).containsExactly(
                "Span (wick) and Span (lamp) are the same thing: one of them serves for both",
                "Heat (wick) and Heat (lamp) are one concept in two shapes: keep both, with a conversion where they meet (unit: kelvin) -- convert at the boundary",
                "Heat (wick) and Heat (lamp) are merged as Warmth: one measure of heat",
                "Warmth: in kelvin",
                "Span (wick) and Span (lamp) are merged as Spans");
    }
}
