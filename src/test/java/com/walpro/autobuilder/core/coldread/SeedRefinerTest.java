package com.walpro.autobuilder.core.coldread;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.core.coldread.ClaimProposer.Claim;
import com.walpro.autobuilder.core.coldread.ClaimProposer.CommandRun;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Account;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Feature;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.FeatureVerdict;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.JudgedClaim;
import com.walpro.autobuilder.core.coldread.ReverseEngineeringService.Observation;
import com.walpro.autobuilder.core.coldread.entry.EntryKind;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4-EQ stage 1: the composed seed is arranged by how its claims exercised the project and reworded from what was
 * observed -- and no line gains or loses a claim on the way. A program with one entry type and invented command
 * words stands for the kind.
 */
class SeedRefinerTest {

    private static JudgedClaim run(String id, int exit, String output, String... args) {
        Claim k = new Claim(id, "c1", "claim " + id, EntryKind.COMMAND_STYLE, List.of(), exit, null,
                new CommandRun(Map.of(), List.of(args), null));
        return new JudgedClaim(k, "anchored", List.of(), List.of(new Observation("as-is", exit, null, output, true)),
                new FeatureVerdict("Tool", "anchored", List.of(), "Tool.main"));
    }

    private final Account account = new Account("src", "copy", null, "command-style",
            List.of(new Feature("Tool", "command-style", List.of("program Tool"), List.of("Tool.main"), List.of(), List.of(),
                    List.of("k1", "k2", "k3", "k4", "k5", "k6"))),
            List.of(), List.of(), List.of(), List.of(),
            List.of(run("k1", 0, "3\n", "tally", "a.txt"), run("k2", 1, "no such file: gone.txt\n", "tally", "gone.txt"),
                    run("k3", 0, "", "audit", "a.txt"), run("k4", 2, "row 2: short\n", "audit", "b.txt"),
                    run("k5", 3, "unknown command: frob\n", "frob"), run("k6", 3, "unknown command\n")),
            Map.of(), 0);

    private static final String COMPOSED = """
            What it is: A tool for text files [r1]

            Tool:
            - Tallying a file prints its row count [k1]
            - Tallying a file that is not there prints an error message and exits 1 [k2]
            - Auditing a well-formed file exits 0 [k3]
            - Auditing a file with a short row reports it [k4]
            - An unrecognised first argument exits 3 [k5]
            - No arguments exits 3 [k6]
            """;

    /** The first answer is to the wording question, the second to the question for rules; the last one repeats. */
    private static LLMClient answering(String... texts) {
        LLMClient llm = mock(LLMClient.class);
        var stub = when(llm.ask(anyList()));
        for (String text : texts) {
            ChatResponse r = new ChatResponse();
            ChatResponse.Choice c = new ChatResponse.Choice();
            c.setMessage(new Message("assistant", text));
            r.setChoices(List.of(c));
            stub = stub.thenReturn(ResponseEntity.ok(r));
        }
        return llm;
    }

    @Test
    void linesStandUnderTheUseTheirClaimsWentThrough_andAUseWithOneLineHasNoSectionOfItsOwn() {
        var sections = SeedRefiner.sections(account, COMPOSED);

        assertThat(sections.keySet()).containsExactly("tally", "audit", SeedRefiner.OTHER);
        assertThat(sections.get("tally")).extracting(SeedRefiner.Cited::cites).containsExactly(List.of("k1"), List.of("k2"));
        assertThat(sections.get(SeedRefiner.OTHER)).extracting(SeedRefiner.Cited::cites).containsExactly(List.of("k5"), List.of("k6"));
    }

    @Test
    void theContextLineIsReadFromStructure() {
        assertThat(SeedRefiner.contextLine(account, SeedRefiner.sections(account, COMPOSED)))
                .isEqualTo("How it is used: command-style -- " + EntryKind.COMMAND_STYLE.definition()
                        + ". Exercised here through: tally, audit.");
    }

    @Test
    void thePromptSetsEachLineBesideHowItWasExercisedAndWhatWasAnswered() {
        String prompt = SeedRefiner.prompt(account, SeedRefiner.sections(account, COMPOSED));

        assertThat(prompt).contains("k2 exercised: run tally gone.txt -> exit 1")
                .contains("k2 answered: exit 1, output: no such file: gone.txt")
                .contains("k3 answered: exit 0, output: (no output)");
    }

    @Test
    void aRewordedLineIsTakenOnlyWithExactlyItsClaims_everyOtherLineKeepsItsComposedWording() {
        String answer = """
                tally:
                - tally <file> prints the number of rows and exits 0 [k1]
                - tally <file> on a missing file prints "no such file: <path>" and exits 1 [k2, k1]
                audit:
                - audit <file> exits 0 with no output when every row is well formed [k3]
                - Files are audited quickly
                - audit exits 2 for anything odd [k4, k9]
                Other uses:
                - Any unknown command exits 3 [k5, k6]
                """;

        String refined = new SeedRefiner(answering(answer, "no rule found"), true).refine(account, COMPOSED);

        assertThat(refined).startsWith("What it is: A tool for text files [r1]\nHow it is used: command-style");
        assertThat(refined).contains("- tally <file> prints the number of rows and exits 0 [k1]")
                .contains("- Tallying a file that is not there prints an error message and exits 1 [k2]")
                .contains("- audit <file> exits 0 with no output when every row is well formed [k3]")
                .contains("- Auditing a file with a short row reports it [k4]")
                .contains("- An unrecognised first argument exits 3 [k5]").contains("- No arguments exits 3 [k6]")
                .doesNotContain("quickly").doesNotContain("anything odd").doesNotContain("Any unknown command");
        // every kept claim the composed seed cited is cited once, as before
        for (String id : List.of("k1", "k2", "k3", "k4", "k5", "k6")) {
            assertThat(refined.split("\\[" + id + "]", -1)).hasSize(2);
        }
    }

    @Test
    void afterTheWording_theRulesAreAskedFor_andARuleTakesThePlaceOfItsLines() {
        String refined = new SeedRefiner(answering("nothing usable",
                "- Unknown command: anything that is not a command exits 3. [k5, k6]", "1: yes\n2: yes"), true).refine(account, COMPOSED);

        assertThat(refined).contains("\nOther uses:\n- Unknown command: anything that is not a command exits 3. [k5, k6]\n")
                .doesNotContain("No arguments exits 3").contains("- Tallying a file prints its row count [k1]");
    }

    @Test
    void theRefinedSeedIsReadBackWithNothingOfItAsTheOperatorsOwnLine_andEachLineFindsItsFeature() {
        String refined = new SeedRefiner(answering("nothing usable"), true).refine(account, COMPOSED);

        var lines = SeedComposer.lines(refined, Set.of("Tool"));

        assertThat(lines).extracting(SeedComposer.Line::kind).containsExactly(
                SeedComposer.Line.PURPOSE, SeedComposer.Line.CONTEXT,
                SeedComposer.Line.SECTION, SeedComposer.Line.CITED, SeedComposer.Line.CITED,
                SeedComposer.Line.SECTION, SeedComposer.Line.CITED, SeedComposer.Line.CITED,
                SeedComposer.Line.SECTION, SeedComposer.Line.CITED, SeedComposer.Line.CITED);
        assertThat(ReverseEngineeringConfirmation.stated(account, refined)).isEmpty();
        assertThat(lines.stream().filter(l -> l.kind().equals(SeedComposer.Line.CITED))
                .map(l -> ReverseEngineeringConfirmation.featureOf(l, account))).containsOnly("Tool");
        String kept = ReverseEngineeringConfirmation.seedText(lines);
        assertThat(kept).contains("How it is used: command-style").contains("\ntally:\n- Tallying a file prints its row count\n")
                .doesNotContain("[k1]");
    }

    // --- stage 2 and 3: the word for what several lines describe ---

    private static final Account WIDE = wide();

    private static Account wide() {
        return new Account("src", "copy", null, "command-style",
                List.of(new Feature("Tool", "command-style", List.of("program Tool"), List.of("Tool.main"), List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of(), List.of(),
                List.of(run("k1", 5, "", "tally"), run("k2", 5, "", "tally", "a", "b"),
                        run("k3", 5, "", "audit"), run("k4", 5, "", "audit", "a", "b"),
                        run("k5", 5, "", "pick", "a"), run("k6", 5, "", "pick", "a", "b", "c"),
                        run("k7", 1, "no such file: x\n", "tally", "x"), run("k8", 1, "no such file: x\n", "audit", "x"),
                        run("k9", 0, "2\n", "tally", "a"), run("k10", 0, "", "audit", "a"), run("k11", 0, "v\n", "pick", "a", "n"),
                        run("k12", 4, "no such column: n\n", "pick", "a", "n")),
                Map.of(), 0);
    }

    private static final String WIDE_SEED = """
            Tool:
            - tally with no file exits 5 [k1]
            - tally with two files exits 5 [k2]
            - audit with no file exits 5 [k3]
            - audit with two files exits 5 [k4]
            - pick with only a file exits 5 [k5]
            - pick with two extra arguments exits 5 [k6]
            - tally on a missing file prints "no such file: <path>" and exits 1 [k7]
            - audit on a missing file prints "no such file: <path>" and exits 1 [k8]
            - tally prints the row count and exits 0 [k9]
            - audit of a well-formed file exits 0 [k10]
            - pick prints the column and exits 0 [k11]
            - pick of a column that is not there exits 4 [k12]
            """;

    @Test
    void aRuleStandsUnderTheUsesItsClaimsWentThrough_allOfThemOrTheOnesNamed() {
        var sections = SeedRefiner.sections(WIDE, WIDE_SEED);
        String answer = """
                - Wrong number of arguments: a command given fewer or more arguments than it takes exits 5. [k1, k2, k3, k4, k5, k6]
                - Missing file: a file that does not exist is answered with "no such file: <path>" and exit 1. [k7, k8]
                """;
        int[] rules = new int[1];

        var merged = SeedRefiner.merged(WIDE, sections, answer, rules);

        assertThat(rules[0]).isEqualTo(2);
        assertThat(merged.keySet()).containsExactly("tally", "audit", "pick", "tally, audit", SeedRefiner.ALL);
        assertThat(merged.get(SeedRefiner.ALL)).extracting(SeedRefiner.Cited::cites)
                .containsExactly(List.of("k1", "k2", "k3", "k4", "k5", "k6"));
        assertThat(merged.get("tally, audit").get(0).text()).startsWith("Missing file:");
        assertThat(merged.get("tally")).extracting(SeedRefiner.Cited::cites).containsExactly(List.of("k9"));
        assertThat(merged.get("pick")).extracting(SeedRefiner.Cited::cites).containsExactly(List.of("k11"), List.of("k12"));
    }

    @Test
    void aRuleIsRefused_whenItSplitsALine_namesAnUnknownClaim_replacesOneLine_orJoinsDifferentStatuses() {
        var sections = SeedRefiner.sections(WIDE, WIDE_SEED);
        String answer = """
                - Everything works: every command exits 0 or 4. [k11, k12]
                - Renamed: tally with no file exits 5. [k1]
                - Invented: something. [k1, k2, k99]
                - Success: each command exits 0 on good input. [k9, k10, k11]
                - Second use of a line: again. [k9, k10]
                """;
        int[] rules = new int[1];

        var merged = SeedRefiner.merged(WIDE, sections, answer, rules);

        assertThat(rules[0]).isEqualTo(1);
        assertThat(merged.get(SeedRefiner.ALL)).extracting(SeedRefiner.Cited::cites).containsExactly(List.of("k9", "k10", "k11"));
        assertThat(merged.get("tally")).extracting(SeedRefiner.Cited::cites)
                .containsExactly(List.of("k1"), List.of("k2"), List.of("k7"));
        assertThat(merged.get("pick")).extracting(SeedRefiner.Cited::cites)
                .containsExactly(List.of("k5"), List.of("k6"), List.of("k12"));
        // nothing lost: every claim is cited once
        var cited = merged.values().stream().flatMap(List::stream).flatMap(l -> l.cites().stream()).toList();
        assertThat(cited).containsExactlyInAnyOrder("k1", "k2", "k3", "k4", "k5", "k6", "k7", "k8", "k9", "k10", "k11", "k12");
    }

    @Test
    void aReplacedLineItsRuleDoesNotStateInFullComesBackAsItsOwnLine_andTheRuleStandsForTheRest() {
        String rules = "- Wrong number of arguments: too few or too many arguments exit 5. [k1, k2, k3, k4, k5, k6]";
        // pairs are numbered in the seed's order: k1..k6 = 1..6; the third is not confirmed, the fifth is denied
        String verdicts = "1: yes\n2: yes\n4: yes\n5: no -- the line says which argument is missing\n6: yes";

        String refined = new SeedRefiner(answering("nothing usable", rules, verdicts), true).refine(WIDE, WIDE_SEED);

        assertThat(refined).contains("- Wrong number of arguments: too few or too many arguments exit 5. [k1, k2, k4, k6]")
                .contains("- audit with no file exits 5 [k3]").contains("- pick with only a file exits 5 [k5]")
                .doesNotContain("tally with no file exits 5");
    }

    @Test
    void withNoVerdictAtAll_everyReplacedLineComesBack() {
        String rules = "- Missing file: answered with \"no such file: <path>\" and exit 1. [k7, k8]";

        String refined = new SeedRefiner(answering("nothing usable", rules, "I cannot tell."), true).refine(WIDE, WIDE_SEED);

        assertThat(refined).doesNotContain("Missing file:").contains("[k7]").contains("[k8]");
    }

    @Test
    void aSeedWithRulesIsReadBackWithNoOperatorsLine_andARuleFindsItsFeature() {
        var merged = SeedRefiner.merged(WIDE, SeedRefiner.sections(WIDE, WIDE_SEED),
                "- Missing file: answered with \"no such file: <path>\" and exit 1. [k7, k8]", null);
        String text = SeedRefiner.render(null, SeedRefiner.contextLine(WIDE, SeedRefiner.sections(WIDE, WIDE_SEED)), merged);

        assertThat(text).contains("\ntally, audit:\n- Missing file: answered with");
        assertThat(ReverseEngineeringConfirmation.stated(WIDE, text)).isEmpty();
        assertThat(SeedComposer.lines(text, Set.of("Tool")).stream().filter(l -> l.kind().equals(SeedComposer.Line.CITED))
                .map(l -> l.cites().size())).contains(2);
    }

    @Test
    void switchedOff_orWithNoCitedLine_theComposedSeedIsReturnedAsItIs() {
        LLMClient llm = mock(LLMClient.class);
        assertThat(new SeedRefiner(llm, false).refine(account, COMPOSED)).isEqualTo(COMPOSED);
        assertThat(new SeedRefiner(llm, true).refine(account, "Tool:\n")).isEqualTo("Tool:\n");
        verify(llm, never()).ask(anyList());
    }

    @Test
    void aModelThatDoesNotAnswerLeavesTheArrangementWithTheComposedWording() {
        LLMClient llm = mock(LLMClient.class);
        when(llm.ask(anyList())).thenThrow(new IllegalStateException("no model"));

        String refined = new SeedRefiner(llm, true).refine(account, COMPOSED);

        assertThat(refined).contains("\naudit:\n- Auditing a well-formed file exits 0 [k3]\n");
    }
}
