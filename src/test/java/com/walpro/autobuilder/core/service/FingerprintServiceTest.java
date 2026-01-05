package com.walpro.autobuilder.core.service;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
import com.walpro.autobuilder.agent.evolution.model.FileMutation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintServiceTest {

    private final FingerprintService sut = new FingerprintService();

    @Test
    void computeFingerprint_isOrderIndependent() {
        String fp1 = sut.computeFingerprint(List.of(
                new FileMutation("b.txt", "B"),
                new FileMutation("a.txt", "A")
        ));
        String fp2 = sut.computeFingerprint(List.of(
                new FileMutation("a.txt", "A"),
                new FileMutation("b.txt", "B")
        ));

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    void computeFingerprint_ignoresWhitespaceOnlyChanges() {
        String fp1 = sut.computeFingerprint(List.of(
                new FileMutation("a.txt", "class A {  \n  }")
        ));
        String fp2 = sut.computeFingerprint(List.of(
                new FileMutation("a.txt", "class A{\n}")
        ));

        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    void computeFingerprint_changesWhenContentChanges() {
        String fp1 = sut.computeFingerprint(List.of(
                new FileMutation("a.txt", "A")
        ));
        String fp2 = sut.computeFingerprint(List.of(
                new FileMutation("a.txt", "B")
        ));

        assertThat(fp1).isNotEqualTo(fp2);
    }

    @Test
    void computeEvolutionFingerprint_includesDeletedFiles() {
        EvolutionOutcome o1 = new EvolutionOutcome(
                List.of(new FileMutation("a.txt", "A")),
                List.of("old.txt"),
                "summary",
                "raw"
        );
        EvolutionOutcome o2 = new EvolutionOutcome(
                List.of(new FileMutation("a.txt", "A")),
                List.of("other.txt"),
                "summary",
                "raw"
        );

        String fp1 = sut.computeEvolutionFingerprint(o1);
        String fp2 = sut.computeEvolutionFingerprint(o2);

        assertThat(fp1).isNotEqualTo(fp2);
    }
}
