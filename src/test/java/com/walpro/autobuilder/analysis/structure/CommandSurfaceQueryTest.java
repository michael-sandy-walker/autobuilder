package com.walpro.autobuilder.analysis.structure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-DD. The specification text is the one the field run was driven from, not an invented one. */
class CommandSurfaceQueryTest {

    private final CommandSurfaceQuery query = new CommandSurfaceQuery();

    private static final String CLI_SPEC = """
            Invocation: java -jar app.jar <command> [arguments]

            Commands:

            check <file> — read the file and report malformed rows. Exit 0 when every row is
            well formed, exit 2 when at least one row is malformed.

            count <file> — print the number of data rows to stdout as a bare integer. Exit 0.

            column <file> <name> — print every value of the named column, one per line.
            Exit 4 when the named column is not present in the header.
            """;

    private static final String WEB_SPEC = """
            Build a library service. GET /books returns HTTP 200.
            POST /loans returns HTTP 404 when book or member does not exist.
            """;

    @Test
    void readsEachDeclaredOperationAndItsArity() {
        var declared = query.declaredInSpec(CLI_SPEC);
        assertThat(declared.keySet()).containsExactly("check", "count", "column");
        assertThat(declared.get("check").arity()).isEqualTo(1);
        assertThat(declared.get("column").arity()).isEqualTo(2);
        assertThat(declared.get("column").positional()).containsExactly("file", "name");
    }

    /**
     * The guard that keeps the surface from filling with prose. A criterion mentions the word and
     * declares nothing; without the separator requirement it would read as a zero-argument
     * operation and "an unrecognised word" would then be computed against a surface of English.
     */
    @Test
    void aSentenceThatMerelyMentionsTheWordIsNotADeclaration() {
        assertThat(query.declaredInSpec("check exits 0 when every row is well formed")).isEmpty();
        assertThat(query.declaredInSpec("Running a known command with the wrong number of arguments exits 5")).isEmpty();
    }

    @Test
    void aSpecificationWithNoCommandSurfaceIsSilent() {
        assertThat(query.declaredInSpec(WEB_SPEC)).isEmpty();
        assertThat(query.declaresCommandSurface(WEB_SPEC)).isFalse();
        assertThat(query.unrecognisedWord(WEB_SPEC)).isEmpty();
    }

    @Test
    void anUnrecognisedWordCannotItselfBeADeclaredOperation() {
        var word = query.unrecognisedWord(CLI_SPEC);
        assertThat(word).isPresent();
        assertThat(query.declaredInSpec(CLI_SPEC)).doesNotContainKey(word.get());
    }
}
