package com.walpro.autobuilder.core.merge;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T4-EC slice 5: the answers read as the local model really gave them in the offline replay. */
class InputComparisonTest {

    private static InputComparison answering(String answer) {
        LLMClient llm = mock(LLMClient.class);
        ChatResponse body = mock(ChatResponse.class);
        when(body.getFirstContent()).thenReturn(answer);
        when(llm.ask(any(), any())).thenReturn(ResponseEntity.ok(body));
        return new InputComparison(llm);
    }

    private static final InputComparison.Difference D = new InputComparison.Difference("the title limit", "120", "200");

    @Test
    void sameConceptTakesTheTypeNameFromAPath_andNoneIsNothing() {
        assertThat(answering("SAME: service/LoanService").sameConcept("s", List.of(), "x")).containsExactly("LoanService");
        assertThat(answering("SAME: Book\nSAME: Book").sameConcept("s", List.of(), "x")).containsExactly("Book");
        assertThat(answering("NONE").sameConcept("s", List.of(), "x")).isEmpty();
        assertThat(answering("SAME: NONE").sameConcept("s", List.of(), "x")).isEmpty();
    }

    /** The replay's real answers came bare, without the prefix. */
    @Test
    void typesToUseTakesOnlyNamesOnTheList() {
        List<String> types = List.of("Isbn", "CatalogueEntry", "LendingPolicy");
        assertThat(answering("Isbn, LendingPolicy").typesToUse("kit", "i", types)).containsExactly("Isbn", "LendingPolicy");
        assertThat(answering("TYPES: Isbn").typesToUse("kit", "i", types)).containsExactly("Isbn");
        assertThat(answering("NONE").typesToUse("kit", "i", types)).isEmpty();
        assertThat(answering("Invented").typesToUse("kit", "i", types)).isEmpty();
        assertThat(answering("").typesToUse("kit", "i", types)).isEmpty();
    }

    @Test
    void differencesAreReadLineByLine_andNoneIsNone() {
        var out = answering("""
                DIFFERENCE: maximum title length | RECEIVING: title must be at most 120 characters | OTHER: title must be at most 200 characters
                DIFFERENCE: ISBN check-digit validation | RECEIVING: ISBN need only be 13 digits | OTHER: the 13th digit must be the check digit
                """).differences("s", "a", "b");
        assertThat(out).hasSize(2);
        assertThat(out.get(0).what()).isEqualTo("maximum title length");
        assertThat(out.get(0).other()).isEqualTo("title must be at most 200 characters");
        assertThat(answering("NONE").differences("s", "a", "b")).isEmpty();
    }

    @Test
    void anUnusableAnswerAnswersNothing() {
        InputComparison c = answering("");
        assertThat(c.sameConcept("s", List.of(), "x")).isEmpty();
        assertThat(c.differences("s", "a", "b")).isEmpty();
        assertThat(c.settledByIntent("i", D)).isFalse();
        assertThat(c.contradicts("s", "i", "c")).isFalse();
        assertThat(c.stating(List.of("a"), D)).isEmpty();
    }

    @Test
    void settledAndContradictsAreReadFromTheirVerdicts() {
        assertThat(answering("SETTLED").settledByIntent("i", D)).isTrue();
        assertThat(answering("OPEN").settledByIntent("i", D)).isFalse();
        assertThat(answering("VERDICT: CONTRADICTS\nREASON: it serves HTTP").contradicts("s", "i", "c")).isTrue();
        assertThat(answering("VERDICT: APPLIES\nREASON: it is about the parser").contradicts("s", "i", "c")).isFalse();
    }

    @Test
    void statingReadsTheNumbersItWasGiven_andIgnoresOthers() {
        List<String> lines = List.of("one", "two", "three");
        assertThat(answering("LINES: 2").stating(lines, D)).containsExactly("two");
        assertThat(answering("4").stating(lines, D)).isEmpty();
        assertThat(answering("LINES: 1, 3").stating(lines, D)).containsExactly("one", "three");
        assertThat(answering("NONE").stating(lines, D)).isEmpty();
    }
}
