package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-DD item 2. Every criterion below is VERBATIM from the goal tree of the first non-web run
 * (2026-08-25, the delimited-text tool) rather than invented for the test — the abstain cases in
 * particular are shapes that run actually produced, which is why they are the ones worth pinning.
 */
class CommandCriterionParserTest {

    private static final List<String> LAUNCH = CommandCriterionParser.launchTokensOf("java -jar {artifact}");

    private static Optional<CommandCriterionParser.CommandAssertion> parse(String c) {
        return CommandCriterionParser.parse(c, LAUNCH);
    }

    @Test
    void launchTokensAreTheFixedPrefixOfTheConfiguredCommand() {
        assertThat(CommandCriterionParser.launchTokensOf("java -jar {artifact}")).containsExactly("java", "-jar");
        assertThat(CommandCriterionParser.launchTokensOf("{artifact}")).isEmpty();
        assertThat(CommandCriterionParser.launchTokensOf("")).isEmpty();
    }

    @Test
    void readsTheInvocationAndTheExitStatusTheCriterionStates() {
        var a = parse("Running `java -jar app.jar unknown` prints \"unknown command: unknown\" "
                + "to stderr and exits with code 3.");
        assertThat(a).isPresent();
        assertThat(a.get().args()).containsExactly("unknown");
        assertThat(a.get().expectedExitStatus()).isEqualTo(3);
        assertThat(a.get().describe()).isEqualTo("unknown → exit 3");
    }

    @Test
    void readsAMultiArgumentInvocation() {
        var a = parse("Running `java -jar app.jar check file.csv extra` (too many args) "
                + "prints an error to stderr and exits with code 5");
        assertThat(a).isPresent();
        assertThat(a.get().args()).containsExactly("check", "file.csv", "extra");
        assertThat(a.get().expectedExitStatus()).isEqualTo(5);
    }

    @Test
    void readsTheOtherStatusPhrasingsTheCorpusUses() {
        assertThat(parse("`java -jar app.jar count <x>` exits 1").isPresent()).isFalse(); // placeholder, below
        assertThat(parse("`java -jar app.jar check missing.csv` exits 1 with \"no such file\" on stderr"))
                .get().extracting(a -> a.expectedExitStatus()).isEqualTo(1);
        assertThat(parse("For `java -jar app.jar column f.csv salary` the exit code is 4"))
                .get().extracting(a -> a.expectedExitStatus()).isEqualTo(4);
    }

    /** The empty argument vector is a real invocation, not an absence. */
    @Test
    void anInvocationWithNoArgumentsParses() {
        var a = parse("Running `java -jar app.jar` with no arguments exits 3");
        assertThat(a).isPresent();
        assertThat(a.get().args()).isEmpty();
        assertThat(a.get().describe()).isEqualTo("(no arguments) → exit 3");
    }

    /**
     * A described argument is a FIXTURE the parser cannot conjure, and inventing one would test a
     * different input from the one the requirement names — the failure T1-FW is about. Silence is
     * the honest answer until grounding exists.
     */
    @Test
    void anArgumentThatIsDescribedRatherThanGivenAbstains() {
        assertThat(parse("`java -jar app.jar count <nonexistent-path>` exits 1 with "
                + "\"no such file: <nonexistent-path>\" on stderr")).isEmpty();
        assertThat(parse("Running `java -jar app.jar column <file>` (missing column name arg) exits 5")).isEmpty();
    }

    @Test
    void aDisjunctionOrANegationAbstainsRatherThanPickingOne() {
        // Both verbatim from the corpus, and both would otherwise yield exactly one matched status.
        assertThat(parse("`java -jar app.jar check f.csv` exits 0 or 2 and prints malformed-row lines")).isEmpty();
        assertThat(parse("`java -jar app.jar check f.csv` exits 0 or 2 (never 5)")).isEmpty();
        assertThat(parse("Invoking the tool with `java -jar app.jar check f.csv` results in exit code 0 "
                + "(if all rows are well-formed) or exit code 2 (if malformed rows exist)")).isEmpty();
    }

    @Test
    void aCriterionNamingNoInvocationOfTheArtifactAbstains() {
        // Real, and genuinely about exit 3 — but it names no invocation to make.
        assertThat(parse("Running with no arguments or an unrecognized command word prints "
                + "\"unknown command: <word>\" to stderr and exits 3")).isEmpty();
        assertThat(parse("`check <file>` exits with code 0 when all rows have the same field count")).isEmpty();
    }

    @Test
    void twoDifferentInvocationsMakeTheSubjectAmbiguous() {
        assertThat(parse("`java -jar app.jar count f.csv` and `java -jar app.jar check f.csv` both exit 0")).isEmpty();
    }

    /**
     * The bare-number guard, and the corpus supplies the trap: this criterion is about a MESSAGE
     * FORMAT and its numbers are field counts. Reading one as a status would invent an expectation
     * the criterion never stated.
     */
    @Test
    void aNumberThatIsNotIntroducedAsAnExitStatusIsNotOne() {
        assertThat(parse("For each malformed row, stdout contains a line matching "
                + "\"line 2: expected 3 fields, found 2\"")).isEmpty();
    }

    @Test
    void aCriterionAboutAResponseStatusIsNotOneAboutAnExitStatus() {
        assertThat(parse("POST /books with invalid input returns HTTP 400 with a JSON body")).isEmpty();
        assertThat(parse("GET /loans?memberId={id} returns HTTP 200")).isEmpty();
    }
}
