package com.walpro.autobuilder.goal;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.core.service.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-CQ: the obligations file is generated with its markers, and never over a usable file.
 * Invented wording; the answer shapes are the ones the local model gave on 2026-09-24.
 */
class SeedObligationsGeneratorTest {

    private static final String SEED = "OBJECTIVE: every widget route is covered by a test, and the suite passes.";

    @Test
    void checkIsDroppedRuleKeptAndABareGateLineCarriesTheMarkersOwnClaim() {
        List<String> lines = SeedObligationsGenerator.parse("""
                check: Every widget route is covered by a test.
                rule: Work inside the project as it is.
                gate: tests
                gate: The whole widget suite passes.
                """);

        assertThat(lines).containsExactly(
                "Every widget route is covered by a test.",
                "rule: Work inside the project as it is.",
                "gate: tests " + SeedObligationsGenerator.TEST_GATE_CLAIM,
                "gate: tests The whole widget suite passes.");
    }

    /** An unknown prefix can only add a question at closure, never remove one. */
    @Test
    void aLineWithNoKnownPrefixStaysAnObligation() {
        assertThat(SeedObligationsGenerator.parse("- must: The widget total is shown.\n\n1. rule: Be kind."))
                .containsExactly("must: The widget total is shown.", "rule: Be kind.");
    }

    @Test
    void writesAMarkedFileTheReadersUnderstand() throws Exception {
        FileService files = mock(FileService.class);
        when(files.exists(SeedObligationsFile.PATH)).thenReturn(false);
        var generator = new SeedObligationsGenerator(answering("""
                check: Every widget route is covered by a test.
                rule: Work inside the project as it is.
                gate: tests The whole suite passes.
                """), files);

        generator.ensureFor(SEED);

        var written = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(files).writeFile(eq(SeedObligationsFile.PATH), written.capture());
        assertThat(SeedObligationsFile.read(written.getValue(), SEED).state())
                .isEqualTo(SeedObligationsFile.State.PRISTINE);
        assertThat(SeedRequirements.verifiableObligationsOrSplit(SEED, written.getValue()))
                .containsExactly("Every widget route is covered by a test.");
        assertThat(SeedRequirements.answeredByGate(SEED, written.getValue(), SeedRequirements.GATE_TESTS))
                .containsExactly("The whole suite passes.");
    }

    @Test
    void aHandEditedFileIsNeverRegenerated() throws Exception {
        String edited = SeedObligationsFile.render(SEED, List.of("Every widget route is covered by a test."))
                + "rule: An operator's own line.\n";
        FileService files = mock(FileService.class);
        when(files.exists(SeedObligationsFile.PATH)).thenReturn(true);
        when(files.readFile(SeedObligationsFile.PATH)).thenReturn(edited);
        LLMClient llm = mock(LLMClient.class);

        new SeedObligationsGenerator(llm, files).ensureFor(SEED);

        verify(llm, never()).ask(any());
        verify(files, never()).writeFile(anyString(), anyString());
    }

    /** Closure would have nothing to ask: the derived split is the safer answer. */
    @Test
    void aSplitWithNoCheckableLineIsNotWritten() throws Exception {
        FileService files = mock(FileService.class);
        when(files.exists(SeedObligationsFile.PATH)).thenReturn(false);

        new SeedObligationsGenerator(answering("rule: Work carefully.\ngate: tests"), files).ensureFor(SEED);

        verify(files, never()).writeFile(anyString(), anyString());
    }

    private static LLMClient answering(String content) {
        ChatResponse body = mock(ChatResponse.class);
        when(body.getFirstContent()).thenReturn(content);
        LLMClient llm = mock(LLMClient.class);
        when(llm.ask(any())).thenReturn(ResponseEntity.ok(body));
        return llm;
    }

    /** A split without the objective is asked for once more, and the better answer is written. */
    @Test
    void aSplitWithoutTheObjectiveIsAskedAgain() throws Exception {
        FileService files = mock(FileService.class);
        when(files.exists(SeedObligationsFile.PATH)).thenReturn(false);
        LLMClient llm = answeringInTurn(
                "check: The widget page loads.",
                "check: Every widget route is covered by a test.\ngate: tests The suite passes.");

        new SeedObligationsGenerator(llm, files).ensureFor(SEED);

        verify(llm, org.mockito.Mockito.times(2)).ask(any());
        var written = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(files).writeFile(eq(SeedObligationsFile.PATH), written.capture());
        assertThat(written.getValue()).contains("Every widget route is covered by a test.")
                .doesNotContain("The widget page loads.");
    }

    /** Still missing after the retry: the seed's own words are added as an ordinary check. */
    @Test
    void anObjectiveTheModelKeepsDroppingIsAddedInTheSeedsOwnWords() throws Exception {
        FileService files = mock(FileService.class);
        when(files.exists(SeedObligationsFile.PATH)).thenReturn(false);
        LLMClient llm = answeringInTurn("check: The widget page loads.", "check: The widget page loads.");

        new SeedObligationsGenerator(llm, files).ensureFor(SEED);

        var written = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(files).writeFile(eq(SeedObligationsFile.PATH), written.capture());
        assertThat(SeedObligationsFile.obligationsFrom(SeedObligationsFile.read(written.getValue(), SEED)).orElseThrow())
                .containsExactly("The widget page loads.", "every widget route is covered by a test", "the suite passes");
    }

    private static LLMClient answeringInTurn(String first, String second) {
        ChatResponse a = mock(ChatResponse.class);
        when(a.getFirstContent()).thenReturn(first);
        ChatResponse b = mock(ChatResponse.class);
        when(b.getFirstContent()).thenReturn(second);
        LLMClient llm = mock(LLMClient.class);
        when(llm.ask(any())).thenReturn(ResponseEntity.ok(a), ResponseEntity.ok(b));
        return llm;
    }
}
