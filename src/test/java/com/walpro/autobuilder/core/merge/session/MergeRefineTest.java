package com.walpro.autobuilder.core.merge.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A prompt operation: what the model is asked about a selection, and how strictly its answer is read. */
class MergeRefineTest {

    private static MergeSession session() {
        Instant t = Instant.parse("2026-10-21T00:00:00Z");
        List<MergeSession.Edit> log = new ArrayList<>();
        for (String p : List.of("wick", "lamp")) {
            log.add(new MergeSession.Edit("addNode", "structure", t, Map.of("id", p, "kind", "PROJECT", "project", p, "title", p)));
            log.add(new MergeSession.Edit("addNode", "structure", t, Map.of("id", p + ":type:Heat", "kind", "TYPE", "project", p,
                    "title", "Heat", "metadata", Map.of("members", List.of("degrees()")))));
            log.add(new MergeSession.Edit("addNode", "structure", t, Map.of("id", p + ":op:Heat.raise()", "kind", "OPERATION",
                    "project", p, "title", "Heat.raise()")));
            log.add(new MergeSession.Edit("addEdge", "structure", t, Map.of("id", p + ":e", "from", p + ":type:Heat",
                    "to", p + ":op:Heat.raise()", "kind", "STRUCTURE")));
        }
        return MergeSession.replay(log);
    }

    @Test
    void theQuestionCarriesTheRequestAndWhatTheSessionHoldsAboutEachSelectedNode() {
        String q = MergeRefine.question(session(), List.of("wick:type:Heat", "lamp:type:Heat"), "make one Heat");
        assertThat(q).contains("The operator's request: make one Heat", "TYPE of project wick: Heat", "TYPE of project lamp: Heat",
                "members: [degrees()]", "operation Heat.raise()", "TITLE:", "ACTION: UNIFY|ADAPT_ONE|KEEP_BOTH");
    }

    @Test
    void aWellFormedAnswerIsReadWithItsCriteria_oneWithoutATitleOrActionIsNotAProposal() {
        MergeRefine.Proposal p = MergeRefine.read("""
                Here is my proposal.
                TITLE: "Heat"
                DESCRIPTION: One measure of heat for both projects.
                CRITERIA:
                - Heat is measured in degrees
                - raise() increases it by one
                ACTION: UNIFY
                REASON: Both define the same record.""");
        assertThat(p.title()).isEqualTo("Heat");
        assertThat(p.criteria()).containsExactly("Heat is measured in degrees", "raise() increases it by one");
        assertThat(p.action()).isEqualTo("UNIFY");
        assertThat(MergeRefine.read("TITLE: Heat\nREASON: none")).isNull();
        assertThat(MergeRefine.read("ACTION: UNIFY")).isNull();
        assertThat(MergeRefine.read(null)).isNull();
    }
}
