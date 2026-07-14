package com.walpro.autobuilder.analysis.problem;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemContextTest {

    @Test
    void upsertOpen_newEntry_addsToOpen() {
        ProblemContext pc = ProblemContext.empty();

        ProblemEntry e = new ProblemEntry(
                "p1",
                ProblemStatus.OPEN,
                ProblemSeverity.WARNING,
                "t",
                "d",
                Set.of("k1"),
                "g",
                "s",
                "c1",
                "c1",
                null,
                null,
                List.of("c1"),
                List.of("n1")
        );

        ProblemContext out = pc.upsertOpen(e);

        assertThat(out.open()).hasSize(1);
        assertThat(out.resolved()).isEmpty();
        assertThat(out.open().get(0).id()).isEqualTo("p1");
        assertThat(out.open().get(0).status()).isEqualTo(ProblemStatus.OPEN);
    }

    @Test
    void resolveById_movesEntryFromOpenToResolved_andSetsResolvedStatus() {
        Instant t = Instant.now();

        ProblemEntry e = new ProblemEntry(
                "p1",
                ProblemStatus.OPEN,
                ProblemSeverity.WARNING,
                "t",
                "d",
                Set.of("k1"),
                "g",
                "s",
                "c1",
                "c1",
                t,
                t,
                List.of(),
                List.of()
        );

        ProblemContext pc = new ProblemContext(List.of(e), List.of());

        ProblemContext out = pc.resolveById("p1", "fixed");

        assertThat(out.open()).isEmpty();
        assertThat(out.resolved()).hasSize(1);
        assertThat(out.resolved().get(0).id()).isEqualTo("p1");
        assertThat(out.resolved().get(0).status()).isEqualTo(ProblemStatus.RESOLVED);
        assertThat(out.resolved().get(0).observationNotes()).contains("fixed");
    }

    @Test
    void reopenById_movesResolvedBackToOpen() {
        Instant t = Instant.now();

        ProblemEntry resolved = new ProblemEntry(
                "p1",
                ProblemStatus.RESOLVED,
                ProblemSeverity.WARNING,
                "t",
                "d",
                Set.of("k1"),
                "g",
                "s",
                "c1",
                "c2",
                t,
                t,
                List.of(),
                List.of()
        );

        ProblemContext pc = new ProblemContext(List.of(), List.of(resolved));

        ProblemContext out = pc.reopenById("p1", "regressed");

        assertThat(out.open()).hasSize(1);
        assertThat(out.resolved()).isEmpty();
        assertThat(out.open().get(0).id()).isEqualTo("p1");
        assertThat(out.open().get(0).status()).isEqualTo(ProblemStatus.OPEN);
        assertThat(out.open().get(0).observationNotes()).contains("regressed");
    }

    @Test
    void hasBlockingProblemsFor_respectsScopeRules() {
        ProblemEntry scopedBlocker = new ProblemEntry(
                "p1",
                ProblemStatus.OPEN,
                ProblemSeverity.BLOCKER,
                "t",
                "d",
                Set.of(),
                "goal-1",
                "sub-1",
                "c1",
                "c1",
                null,
                null,
                List.of(),
                List.of()
        );

        ProblemContext pc = new ProblemContext(List.of(scopedBlocker), List.of());

        assertThat(pc.hasBlockingProblemsFor("goal-1", "sub-1")).isTrue();
        assertThat(pc.hasBlockingProblemsFor("goal-1", "sub-2")).isFalse();
        assertThat(pc.hasBlockingProblemsFor("goal-2", "sub-1")).isFalse();
    }
}