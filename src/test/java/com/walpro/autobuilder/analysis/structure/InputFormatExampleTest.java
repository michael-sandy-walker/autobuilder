package com.walpro.autobuilder.analysis.structure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** T4-DD. The specification text is the seed a real run was driven from. */
class InputFormatExampleTest {

    private static final String SPEC = """
            FIELD SYNTAX. Fields are separated by commas. A field MAY be wrapped in double quotes,
            and a quoted field may contain commas and newlines, which are then part of the value and
            NOT separators. Inside a quoted field, two consecutive double quotes mean one literal
            double quote. So the line  a,"b,c","d""e"  has THREE fields: a / b,c / d"e.

            An empty file (zero bytes) has no header.
            """;

    @Test
    void readsTheSampleItsTrueCountAndDerivesTheSeparator() {
        var e = InputFormatExample.readFrom(SPEC);
        assertThat(e).isPresent();
        assertThat(e.get().sample()).isEqualTo("a,\"b,c\",\"d\"\"e\"");
        assertThat(e.get().trueParts()).isEqualTo(3);
        assertThat(e.get().separator()).isEqualTo(',');
        assertThat(e.get().naiveParts()).isEqualTo(4);
    }

    /** The header shares no syntax with the sample, which is what makes the two disagree. */
    @Test
    void buildsAHeaderOfTheTrueWidth() {
        var e = InputFormatExample.readFrom(SPEC).orElseThrow();
        assertThat(e.headerLine()).isEqualTo("col1,col2,col3");
        assertThat(e.headerLine().split(",", -1)).hasSize(3);
        assertThat(e.sample().split(",", -1)).hasSize(4);   // the disagreement a careless reader shows
    }

    /**
     * The rule that keeps this from producing a fixture with no power: a sample whose naive
     * division already matches the stated count exercises nothing, so there is nothing to detect.
     */
    @Test
    void anExampleThatIsNotDiscriminatingIsDeclined() {
        assertThat(InputFormatExample.readFrom("So the line  a,b,c  has three fields.")).isEmpty();
    }

    @Test
    void proseThatMerelyCountsSomethingIsNotAWorkedExample() {
        assertThat(InputFormatExample.readFrom("An empty file (zero bytes) has no header.")).isEmpty();
        assertThat(InputFormatExample.readFrom("The report has three sections.")).isEmpty();
    }

    @Test
    void aSpecificationWorkingNoExampleIsSilent() {
        assertThat(InputFormatExample.readFrom("Build a library service. GET /books returns 200.")).isEmpty();
        assertThat(InputFormatExample.readFrom("")).isEmpty();
    }
}
