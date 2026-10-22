package com.walpro.autobuilder.core.merge.session;

import com.walpro.autobuilder.core.merge.ProjectMerge;
import com.walpro.autobuilder.core.service.FileService;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalStatus;
import com.walpro.autobuilder.goal.GoalType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A run made from a session reports its goal tree into it: the root over the parents' trees, new goals, status changes. */
class MergeSessionMirrorTest {

    @TempDir
    Path tmp;

    @Test
    void theRunsGoalTreeGrowsInTheSession() throws IOException {
        MergeSessionService svc = new MergeSessionService(tmp.resolve("sessions").toString());
        svc.create("s", new ProjectMerge.Parent("wick", "r1", project("wick")), new ProjectMerge.Parent("lamp", "r2", project("lamp")), null);
        FileService files = mock(FileService.class);
        when(files.exists(MergeSessionService.SESSION_LINK)).thenReturn(true);
        when(files.readString(MergeSessionService.SESSION_LINK)).thenReturn("{\"sessionId\": \"s\"}");
        MergeSessionMirror mirror = new MergeSessionMirror(files, svc);

        GoalEntity root = goal("root", "Integrate lamp into wick", null);
        GoalEntity a = goal("ca", "Make wick", "root");
        a.getMetadata().put("mergedFrom", "wick");
        a.getMetadata().put("sourceGoalId", "g-wick");
        GoalEntity b = goal("cb", "Make lamp", "root");
        b.getMetadata().put("mergedFrom", "lamp");
        b.getMetadata().put("sourceGoalId", "g-lamp");
        GoalEntity work = goal("w1", "Convert Heat at the boundary", "root");
        work.getMetadata().put("acceptanceCriteria", List.of("Heat converts"));
        List<GoalEntity> goals = List.of(root, a, b, work);

        mirror.observe(goals, "root");
        MergeSessionService.Graph g = svc.graph("s");
        assertThat(g.nodes()).anySatisfy(n -> {
            assertThat(n.id()).isEqualTo("loop:root");
            assertThat(n.kind()).isEqualTo(MergeSession.NodeKind.SUPER);
            assertThat(n.metadata()).containsEntry("by", "loop");
        });
        assertThat(g.edges()).extracting(MergeSession.Edge::id).contains("loop:root->wick:goal:g-wick", "loop:root->lamp:goal:g-lamp",
                "loop:root->loop:goal:w1");
        int after = svc.log("s").size();

        mirror.observe(goals, "root");
        assertThat(svc.log("s")).as("nothing changed, nothing appended").hasSize(after + 1);   // + the criteria of w1, now on its node

        work.setStatus(GoalStatus.COMPLETED);
        mirror.observe(goals, "root");
        assertThat(svc.graph("s").nodes().stream().filter(n -> n.id().equals("loop:goal:w1")).findFirst().orElseThrow().metadata())
                .containsEntry("status", "COMPLETED").containsEntry("criteria", List.of("Heat converts"));
        int settled = svc.log("s").size();
        mirror.observe(goals, "root");
        assertThat(svc.log("s")).hasSize(settled);
    }

    @Test
    void withoutASessionTheMirrorDoesNothing() throws IOException {
        FileService files = mock(FileService.class);
        when(files.exists(MergeSessionService.SESSION_LINK)).thenReturn(false);
        MergeSessionService svc = mock(MergeSessionService.class);
        new MergeSessionMirror(files, svc).observe(List.of(goal("root", "x", null)), "root");
        org.mockito.Mockito.verifyNoInteractions(svc);
    }

    private static GoalEntity goal(String id, String description, String parent) {
        GoalEntity g = new GoalEntity(id, description, "planner", parent == null ? GoalType.GOAL : GoalType.SUBGOAL, parent);
        g.setStatus(GoalStatus.ACTIVE);
        return g;
    }

    private Path project(String name) throws IOException {
        Path dir = tmp.resolve(name);
        Path src = dir.resolve("output/src/main/java/org/" + name + "/Heat.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package org." + name + ";\npublic record Heat(int degrees) { }\n");
        Files.createDirectories(dir.resolve("meta"));
        Files.writeString(dir.resolve("meta/GoalStack.json"), "{\"goalTree\": {\"id\": \"g-" + name + "\", \"description\": \"Make "
                + name + "\", \"status\": \"COMPLETED\", \"metadata\": {}, \"subGoals\": []}}");
        return dir;
    }
}
