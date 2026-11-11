package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.core.coldread.entry.EntryKind;
import com.walpro.autobuilder.core.coldread.entry.EntryPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-EK slice (c): a line someone states about a project that has code is put to the code before it is work. */
class StatedLineTest {

    @Test
    void theQuestionAsksWhetherTheCodeDoesItAndTakesNoForAnAnswer() {
        var ways = Map.of(EntryKind.LIBRARY_STYLE, new ClaimProposer.Way(
                List.of(new EntryPoint(EntryKind.LIBRARY_STYLE, "Counter.next()", "Counter", "p.Counter", "next")), "TEST:\n<a test>\nEND"));
        String q = ClaimProposer.statementQuestion("a refused entry uses up no number", List.of(), null, ways, null);

        assertThat(q).contains("a refused entry uses up no number")
                .contains("do not take the statement as true")
                .contains("the single line NONE")
                .contains("Counter.next()").contains("A claim of this kind is written:")
                .contains("Answer with up to " + ClaimProposer.MAX_CLAIMS + " claims");
    }

    @Test
    void anAnswerOfNoneIsNoClaims() {
        var p = ClaimProposer.parse("NONE\nThe code counts every entry, refused or not.", ClaimProposer.STATED, 5, List.of(),
                java.util.Set.of(EntryKind.LIBRARY_STYLE));
        assertThat(p.claims()).isEmpty();
        assertThat(p.capability()).isEqualTo(ClaimProposer.STATED);
    }

    @Test
    void aLineHoldsWhenAClaimForItWasKept() {
        var kept = new ClaimProposer.Claim("k9", ClaimProposer.STATED, "x", EntryKind.LIBRARY_STYLE, List.of(), 0, "assertTrue(true);", null);
        assertThat(new ReverseEngineeringService.StatedLine("a", "Counter", List.of(kept), 2, null).holds()).isTrue();
        assertThat(new ReverseEngineeringService.StatedLine("b", null, List.of(), 2, "no claim for it was borne out").holds()).isFalse();
    }
}
