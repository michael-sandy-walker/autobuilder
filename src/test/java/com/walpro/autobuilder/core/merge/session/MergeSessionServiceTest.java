package com.walpro.autobuilder.core.merge.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walpro.autobuilder.core.merge.ProjectMerge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A session made from two projects and a match list, edited by a scripted operator, and the same again by replay. */
class MergeSessionServiceTest {

    @TempDir
    Path tmp;

    @Test
    void aScriptedOperatorSessionEndsInTheGraphItsReplayGives() throws IOException {
        Path a = project("wick", "Flame", "public record Flame(int heat) { public Flame hotter() { return this; } }", "A flame burns");
        Path b = project("lamp", "Glow", "public record Glow(int heat) { public Glow hotter() { return this; } }", "A glow lights");
        Path matches = tmp.resolve("MATCHES.json");
        Files.writeString(matches, """
                {"matches": [{"kind": "TYPE", "a": "Flame", "b": "Glow", "level": "CANDIDATE", "verdict": "RELATED",
                  "action": "ADAPT_ONE", "source": "model", "reason": "both carry heat"}]}""");
        MergeSessionService svc = new MergeSessionService(tmp.resolve("sessions").toString());

        MergeSessionService.Graph g = svc.create("s1", new ProjectMerge.Parent("wick", "r1", a), new ProjectMerge.Parent("lamp", "r2", b), matches);
        assertThat(g.nodes()).extracting(MergeSession.Node::id).contains("wick", "lamp", "wick:type:Flame", "lamp:type:Glow",
                "wick:op:Flame.hotter()");
        MergeSession.Edge proposed = g.edges().stream().filter(x -> x.kind() == MergeSession.EdgeKind.LINK).findFirst().orElseThrow();
        assertThat(proposed.metadata()).containsEntry("status", "proposed").containsEntry("action", "ADAPT_ONE").containsEntry("by", "model");

        svc.apply("s1", edit("setMeta", Map.of("target", proposed.id(), "key", "status", "value", "confirmed")));
        svc.apply("s1", edit("setMeta", Map.of("target", proposed.id(), "key", "unit", "value", "kelvin")));
        String goalA = g.nodes().stream().filter(n -> n.kind() == MergeSession.NodeKind.GOAL && n.project().equals("wick")).findFirst().orElseThrow().id();
        String goalB = g.nodes().stream().filter(n -> n.kind() == MergeSession.NodeKind.GOAL && n.project().equals("lamp")).findFirst().orElseThrow().id();
        svc.apply("s1", edit("addEdge", Map.of("id", "link:op:1", "from", goalA, "to", goalB, "kind", "LINK")));
        assertThatThrownBy(() -> svc.apply("s1", edit("addEdge", Map.of("id", "link:op:9", "from", goalB, "to", goalA, "kind", "LINK"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already linked");
        svc.apply("s1", edit("addSuper", Map.of("id", "super:1", "title", "Light", "children", List.of("wick:type:Flame", "lamp:type:Glow"))));
        svc.apply("s1", edit("removeEdge", Map.of("id", "link:op:1")));
        assertThatThrownBy(() -> svc.apply("s1", edit("removeEdge", Map.of("id", "wick->wick:type:Flame"))))
                .isInstanceOf(IllegalArgumentException.class);

        MergeSessionService.Graph end = svc.graph("s1");
        assertThat(end.edges()).extracting(MergeSession.Edge::id).doesNotContain("link:op:1");
        assertThat(end.nodes()).extracting(MergeSession.Node::id).contains("super:1");
        assertThat(end.edges().stream().filter(x -> x.id().equals(proposed.id())).findFirst().orElseThrow().metadata())
                .containsEntry("status", "confirmed").containsEntry("unit", "kelvin").containsEntry("lastEditedBy", "operator");

        ObjectMapper m = new ObjectMapper().findAndRegisterModules();
        String published = Files.readString(tmp.resolve("sessions/s1/graph.json"));
        MergeSession replayed = MergeSession.replay(svc.log("s1"));
        String again = m.writerWithDefaultPrettyPrinter().writeValueAsString(new MergeSessionService.Graph("s1", replayed.applied(),
                List.copyOf(replayed.nodes().values()), List.copyOf(replayed.edges().values())));
        assertThat(again).isEqualTo(published);
    }

    private static MergeSession.Edit edit(String op, Map<String, Object> args) {
        return new MergeSession.Edit(op, "operator", null, args);
    }

    private Path project(String name, String type, String source, String criterion) throws IOException {
        Path dir = tmp.resolve(name);
        Path src = dir.resolve("output/src/main/java/org/" + name + "/" + type + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package org." + name + ";\n" + source + "\n");
        Files.createDirectories(dir.resolve("meta"));
        Files.writeString(dir.resolve("meta/GoalStack.json"), "{\"goalTree\": {\"id\": \"g-" + name + "\", \"description\": \"Make "
                + name + "\", \"status\": \"COMPLETED\", \"metadata\": {\"acceptanceCriteria\": [\"" + criterion + "\", \"" + criterion
                + "\"]}, \"subGoals\": []}}");
        return dir;
    }

    /** Two constructors: Spring must be told which one is its -- unmarked, the whole application failed to boot. */
    @Test
    void springBuildsTheService() {
        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getSystemProperties().put("autobuilder.merge.sessions-dir", tmp.resolve("spring").toString());
            ctx.register(MergeSessionService.class);
            ctx.refresh();
            assertThat(ctx.getBean(MergeSessionService.class)).isNotNull();
        }
    }

    /** A prompt operation records the model's super node, proposed; an answer that is not a proposal records nothing. */
    @Test
    void aRefineRecordsTheModelsProposalAndAMalformedAnswerRecordsNothing() throws IOException {
        Path a = project("wick", "Flame", "public record Flame(int heat) { }", "A flame burns");
        Path b = project("lamp", "Glow", "public record Glow(int heat) { }", "A glow lights");
        MergeSessionService svc = new MergeSessionService(tmp.resolve("sessions").toString());
        svc.create("r", new ProjectMerge.Parent("wick", "r1", a), new ProjectMerge.Parent("lamp", "r2", b), null);
        List<String> sel = List.of("wick:type:Flame", "lamp:type:Glow");

        MergeSessionService.Refined r = svc.refine("r", sel, "merge these", q -> {
            assertThat(q).contains("merge these", "Flame", "Glow");
            return "TITLE: Light\nDESCRIPTION: what gives light\nCRITERIA:\n- carries heat\nACTION: ADAPT_ONE\nREASON: two shapes";
        });
        MergeSession.Node n = r.graph().nodes().stream().filter(x -> x.id().equals(r.nodeId())).findFirst().orElseThrow();
        assertThat(n.kind()).isEqualTo(MergeSession.NodeKind.SUPER);
        assertThat(n.title()).isEqualTo("Light");
        assertThat(n.metadata()).containsEntry("by", "model").containsEntry("status", "proposed").containsEntry("action", "ADAPT_ONE")
                .containsEntry("request", "merge these").containsEntry("criteria", List.of("carries heat"));

        int before = svc.log("r").size();
        assertThatThrownBy(() -> svc.refine("r", sel, "again", q -> "I would merge them.")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing recorded");
        assertThat(svc.log("r")).hasSize(before);
        assertThatThrownBy(() -> svc.refine("r", List.of("wick:type:Flame"), "x", q -> "")).isInstanceOf(IllegalArgumentException.class);
    }
}
