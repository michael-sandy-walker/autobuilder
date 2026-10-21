package com.walpro.autobuilder.core.merge.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The session is the fold of its log: what an act may do, what it may not, and that removing a super node undoes it. */
class MergeSessionTest {

    private static final Instant T = Instant.parse("2026-10-21T00:00:00Z");

    private static MergeSession.Edit e(String op, String by, Map<String, Object> args) {
        return new MergeSession.Edit(op, by, T, args);
    }

    private static List<MergeSession.Edit> base() {
        List<MergeSession.Edit> log = new ArrayList<>();
        for (String p : List.of("wick", "lamp")) {
            log.add(e("addNode", "structure", Map.of("id", p, "kind", "PROJECT", "project", p, "title", p)));
            log.add(e("addNode", "structure", Map.of("id", p + ":type:Flame", "kind", "TYPE", "project", p, "title", "Flame")));
            log.add(e("addEdge", "structure", Map.of("id", p + "->Flame", "from", p, "to", p + ":type:Flame", "kind", "STRUCTURE")));
        }
        return log;
    }

    @Test
    void linksAreDrawnAndRemoved_metadataIsEditedWithItsAuthor_aSuperNodeIsMadeAndUndone() {
        MergeSession s = MergeSession.replay(base());
        s.apply(e("addEdge", "operator", Map.of("id", "l1", "from", "wick:type:Flame", "to", "lamp:type:Flame", "kind", "LINK")));
        s.apply(e("setMeta", "operator", Map.of("target", "l1", "key", "action", "value", "UNIFY")));
        assertThat(s.edges().get("l1").metadata()).containsEntry("action", "UNIFY").containsEntry("lastEditedBy", "operator");

        s.apply(e("addSuper", "model", Map.of("id", "s1", "title", "Flame", "children", List.of("wick:type:Flame", "lamp:type:Flame"))));
        assertThat(s.nodes().get("s1").kind()).isEqualTo(MergeSession.NodeKind.SUPER);
        assertThat(s.edges().values()).filteredOn(x -> x.kind() == MergeSession.EdgeKind.SUPER_OF).hasSize(2);
        assertThat(s.nodes()).containsKeys("wick:type:Flame", "lamp:type:Flame");   // nothing is overwritten

        s.apply(e("removeSuper", "operator", Map.of("id", "s1")));
        assertThat(s.nodes()).doesNotContainKey("s1");
        assertThat(s.edges().values()).noneMatch(x -> x.kind() == MergeSession.EdgeKind.SUPER_OF);

        s.apply(e("removeEdge", "operator", Map.of("id", "l1")));
        assertThat(s.edges()).doesNotContainKey("l1");
    }

    @Test
    void whatDoesNotFitTheSessionIsRefusedWithItsReason() {
        MergeSession s = MergeSession.replay(base());
        assertThat(s.refusal(e("removeEdge", "operator", Map.of("id", "wick->Flame")))).contains("structure stays");
        assertThat(s.refusal(e("addEdge", "operator", Map.of("id", "x", "from", "wick", "to", "nowhere", "kind", "LINK")))).isNotNull();
        assertThat(s.refusal(e("addSuper", "operator", Map.of("id", "s", "children", List.of("wick"))))).contains("at least two");
        assertThat(s.refusal(e("setMeta", "", Map.of("target", "wick", "key", "k", "value", "v")))).contains("who made it");
        s.apply(e("addSuper", "operator", Map.of("id", "s1", "children", List.of("wick:type:Flame", "lamp:type:Flame"))));
        s.apply(e("addSuper", "operator", Map.of("id", "s2", "children", List.of("s1", "wick"))));
        assertThat(s.refusal(e("removeSuper", "operator", Map.of("id", "s1")))).contains("removed after that one");
        assertThatThrownBy(() -> s.apply(e("removeSuper", "operator", Map.of("id", "s1")))).isInstanceOf(IllegalArgumentException.class);
    }
}
