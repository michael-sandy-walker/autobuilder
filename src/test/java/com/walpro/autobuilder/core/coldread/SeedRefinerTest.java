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

        String refined = new SeedRefiner(answering(answer, "nothing"), true).refine(account, COMPOSED);

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

    // --- stage 2 and 3: one answer, stated once ---

    private static JudgedClaim ran(String id, int exit, String out, String err, String... args) {
        Claim k = new Claim(id, "c1", "claim " + id, EntryKind.COMMAND_STYLE, List.of(), exit, null,
                new CommandRun(Map.of(), List.of(args), null));
        return new JudgedClaim(k, "anchored", List.of(),
                List.of(new Observation("as-is", exit, null, out + err, true, null, err)),
                new FeatureVerdict("Tool", "anchored", List.of(), "Tool.main"));
    }

    private static final Account WIDE = new Account("src", "copy", null, "command-style",
            List.of(new Feature("Tool", "command-style", List.of("program Tool"), List.of("Tool.main"), List.of(), List.of(), List.of())),
            List.of(), List.of(), List.of(), List.of(),
            List.of(ran("k1", 5, "", "", "tally"), ran("k2", 5, "", "", "tally", "a.txt", "b.txt"),
                    ran("k3", 5, "", "", "audit"), ran("k4", 5, "", "", "pick", "a.txt"),
                    ran("k5", 1, "", "no such file: gone.txt\n", "tally", "gone.txt"),
                    ran("k6", 1, "", "no such file: away.txt\n", "audit", "away.txt"),
                    ran("k7", 2, "row 2: 3 short\n", "", "audit", "a.txt"), ran("k8", 2, "row 7: 1 short\n", "", "audit", "b.txt"),
                    ran("k9", 0, "2\n", "", "tally", "a.txt"), ran("k10", 0, "x\ny\n", "", "pick", "a.txt", "n"),
                    ran("k11", 0, "x\n", "", "pick", "b.txt", "n")),
            Map.of(), 0);

    private static final String WIDE_SEED = """
            Tool:
            - tally with no file exits 5 [k1]
            - tally with two files exits 5 [k2]
            - audit with no file exits 5 [k3]
            - pick with only a file exits 5 [k4]
            - tally on a missing file prints "no such file: <path>" and exits 1 [k5]
            - audit on a missing file prints "no such file: <path>" to standard error and exits 1 [k6]
            - audit reports a short row and exits 2 [k7]
            - audit counts rows from the first line of the file and exits 2 [k8]
            - tally prints the row count and exits 0 [k9]
            - pick prints the column and exits 0 [k10, k11]
            """;

    @Test
    void anAnswerIsTheRecord_withTheExercisesOwnValuesAndNumbersTakenOut_andTheStreamItWasPrintedTo() {
        var byId = new java.util.HashMap<String, JudgedClaim>();
        WIDE.claims().forEach(j -> byId.put(j.claim().id(), j));

        assertThat(SeedRefiner.answerOf(byId.get("k5"))).isEqualTo(SeedRefiner.answerOf(byId.get("k6")));
        assertThat(SeedRefiner.answerOf(byId.get("k5")).phrase())
                .isEqualTo("exits with status 1 and prints to standard error \"no such file: <argument>\"");
        assertThat(SeedRefiner.answerOf(byId.get("k7"))).isEqualTo(SeedRefiner.answerOf(byId.get("k8")));
        assertThat(SeedRefiner.answerOf(byId.get("k7")).phrase())
                .isEqualTo("exits with status 2 and prints to standard output \"row <n>: <n> short\"");
        assertThat(SeedRefiner.answerOf(byId.get("k1")).phrase()).isEqualTo("exits with status 5 and prints nothing");
        assertThat(SeedRefiner.answerOf(byId.get("k10"))).isNotEqualTo(SeedRefiner.answerOf(byId.get("k11")));
    }

    @Test
    void linesWhoseClaimsWereAllAnsweredAlikeAreOneGroup_aLineOnDifferingAnswersBelongsToNone() {
        var groups = SeedRefiner.groups(WIDE, SeedRefiner.sections(WIDE, WIDE_SEED));

        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).lines()).extracting(SeedRefiner.Cited::cites)
                .containsExactly(List.of("k1"), List.of("k2"), List.of("k3"), List.of("k4"));
        assertThat(groups.get(1).lines()).extracting(SeedRefiner.Cited::cites).containsExactly(List.of("k5"), List.of("k6"));
        assertThat(groups.get(2).lines()).extracting(SeedRefiner.Cited::cites).containsExactly(List.of("k7"), List.of("k8"));
    }

    private static final String NAMED = """
            GROUP 1
            name: wrong number of arguments
            answer: exits with status 5 and prints nothing
            - no file is given [k1, k3]
            - two files are given [k2]
            - the column name is missing [k4]
            - something about a claim that is not there [k1, k99]

            GROUP 2
            name: missing file
            answer: exits with status 1 and prints to standard error "no such file: <path>"
            - when the file does not exist [k5, k6]

            GROUP 3
            name: short row
            answer: exits 2 and says a row is short
            - a row is short [k7, k8]
            """;

    @Test
    void aGroupIsStatedOnce_underTheUsesItsClaimsWentThrough_withItsConditionsBelowIt() {
        var sections = SeedRefiner.sections(WIDE, WIDE_SEED);
        var groups = SeedRefiner.groups(WIDE, sections);

        String text = SeedRefiner.render(null, null, SeedRefiner.grouped(sections, groups, NAMED));

        assertThat(text).isEqualTo("""
                tally:
                - tally prints the row count and exits 0 [k9]

                audit:
                - short row: exits with status 2 and prints to standard output "row <n>: <n> short" -- when a row is short -- printed: row 2: 3 short ; row 7: 1 short [k7, k8]

                pick:
                - pick prints the column and exits 0 [k10, k11]

                tally, audit:
                - missing file: exits with status 1 and prints to standard error "no such file: <path>" -- when the file does not exist [k5, k6]

                All uses:
                - wrong number of arguments: exits with status 5 and prints nothing, in each of these cases [k1, k2, k3, k4]
                  - (tally, audit) no file is given [k1, k3]
                  - (tally) two files are given [k2]
                  - (pick) the column name is missing [k4]
                """);
    }

    @Test
    void theModelsPhraseForAnAnswerIsTakenOnlyWhileItCarriesTheRecord() {
        var a = new SeedRefiner.Answer(1, List.of("no such file: <argument>"), true, SeedRefiner.ERR);

        assertThat(SeedRefiner.carries("exits with status 1 and prints to standard error \"no such file: <path>\"", a)).isTrue();
        assertThat(SeedRefiner.carries("exits with status 1 and prints \"no such file: <path>\"", a)).isFalse();
        assertThat(SeedRefiner.carries("exits with status 2 and prints to standard error \"no such file: <path>\"", a)).isFalse();
        assertThat(SeedRefiner.carries("exits with status 1 and prints to standard error \"file missing\"", a)).isFalse();
    }

    @Test
    void aLineItsConditionDoesNotStateInFullKeepsItsOwnWords_asItsOwnCase() {
        // wording answer, then the groups, then the verdicts on k1..k8 in the seed's order: the 6th and 8th are not confirmed
        String verdicts = "1: yes\n2: yes\n3: yes\n4: yes\n5: yes\n6: no -- the line says where it is printed\n7: yes";

        String refined = new SeedRefiner(answering("nothing usable", NAMED, verdicts), true).refine(WIDE, WIDE_SEED);

        assertThat(refined).contains("- missing file: exits with status 1 and prints to standard error \"no such file: <path>\", in each of these cases [k5, k6]")
                .contains("  - (tally) the file does not exist [k5]")
                .contains("  - (audit) audit on a missing file prints \"no such file: <path>\" to standard error and exits 1 [k6]")
                .contains("  - a row is short -- printed: row 2: 3 short [k7]")
                .contains("  - audit counts rows from the first line of the file and exits 2 -- printed: row 7: 1 short [k8]");
    }

    @Test
    void aSeedWithGroupsIsReadBackWithItsCasesBelowTheirLine_andNothingAsTheOperatorsOwn() {
        var sections = SeedRefiner.sections(WIDE, WIDE_SEED);
        String text = SeedRefiner.render(null, SeedRefiner.contextLine(WIDE, sections),
                SeedRefiner.grouped(sections, SeedRefiner.groups(WIDE, sections), NAMED));

        var lines = SeedComposer.lines(text, Set.of("Tool"));

        assertThat(ReverseEngineeringConfirmation.stated(WIDE, text)).isEmpty();
        assertThat(lines.stream().filter(l -> l.kind().equals(SeedComposer.Line.CASE)).map(SeedComposer.Line::text))
                .containsExactly("(tally, audit) no file is given", "(tally) two files are given", "(pick) the column name is missing");
        assertThat(ReverseEngineeringConfirmation.seedText(lines))
                .contains("- wrong number of arguments: exits with status 5 and prints nothing, in each of these cases\n  - (tally, audit) no file is given\n");
    }


    // --- a rule of the input: lines picked out by something only their inputs contain ---

    private static JudgedClaim read(String id, String content, String out, String... args) {
        Claim k = new Claim(id, "c1", "claim " + id, EntryKind.COMMAND_STYLE, List.of(), 0, null,
                new CommandRun(Map.of("in.txt", content), List.of(args), null));
        return new JudgedClaim(k, "anchored", List.of(), List.of(new Observation("as-is", 0, null, out, true, null, "")),
                new FeatureVerdict("Tool", "anchored", List.of(), "Tool.main"));
    }

    private static final Account TILDE = new Account("src", "copy", null, "command-style",
            List.of(new Feature("Tool", "command-style", List.of("program Tool"), List.of("Tool.main"), List.of(), List.of(), List.of())),
            List.of(), List.of(), List.of(), List.of(),
            List.of(read("k1", "a;b\n1;2\n", "1\n", "tally", "in.txt"), read("k2", "a;b\n~1;2~;3\n", "one\n", "tally", "in.txt"),
                    read("k3", "a;b\n1;2\n", "x\n", "pick", "in.txt", "a"), read("k4", "a;b\n~x~~y~;2\n", "x~y\n", "pick", "in.txt", "a"),
                    read("k5", "a;b\n", "0 rows\n", "tally", "in.txt"), read("k6", "a;b\n3;4\n", "y\n", "pick", "in.txt", "b"),
                    read("k7", "a;b\n5;6\n", "z\n", "audit", "in.txt"), read("k8", "a;b\n", "empty\n", "audit", "in.txt")),
            Map.of(), 0);

    private static final String TILDE_SEED = """
            Tool:
            - tally counts the rows [k1]
            - tally counts a row with a marked field as one [k2]
            - pick prints a column [k3]
            - pick prints a marked field without its marks [k4]
            - tally of a file with only a header prints 0 rows [k5]
            - pick prints the second column [k6]
            - audit accepts a file [k7]
            - audit of a file with only a header says empty [k8]
            """;

    @Test
    void theLinesWhoseInputsAloneHoldASymbolAreSetTogether_aSymbolEveryInputHasPicksOutNothing() {
        var marked = SeedRefiner.marked(TILDE, SeedRefiner.sections(TILDE, TILDE_SEED));

        assertThat(marked).hasSize(1);
        assertThat(marked.get(0).symbols()).isEqualTo("~");
        assertThat(marked.get(0).lines()).extracting(SeedRefiner.Cited::cites).containsExactly(List.of("k2"), List.of("k4"));
        assertThat(marked.get(0).uses()).containsExactly("tally", "pick");
    }

    @Test
    void aNamedRuleOfTheInputIsAddedAboveTheLinesUnderTheirUses_andNoLineIsTakenAway() {
        var sections = SeedRefiner.sections(TILDE, TILDE_SEED);
        var marked = SeedRefiner.marked(TILDE, sections);

        var with = SeedRefiner.withTerms(sections, sections, marked,
                "SET 1\nname: Marked fields\nrule: A field between two ~ is one field; ~~ inside it is one ~.");
        var none = SeedRefiner.withTerms(sections, sections, marked, "SET 1\nname: NONE\nrule: -");

        assertThat(with.keySet()).containsExactly("tally", "pick", "audit", "tally, pick");
        assertThat(with.get("tally, pick")).extracting(SeedRefiner.Cited::render)
                .containsExactly("- Marked fields: A field between two ~ is one field; ~~ inside it is one ~. [k2, k4]");
        assertThat(with.get("tally")).hasSize(3);
        assertThat(with.get("pick")).hasSize(3);
        assertThat(none).isEqualTo(sections);
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
