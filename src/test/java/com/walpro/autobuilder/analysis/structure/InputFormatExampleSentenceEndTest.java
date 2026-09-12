package com.walpro.autobuilder.analysis.structure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6-S: the worked example's last part must not absorb the sentence's own full stop.
 *
 * <p>Found by a hand edit. The operator added the example to {@code seed.obligations.txt} as its
 * last line, and the parse differed from the seed's: {@code d"e.} rather than {@code d"e}. The
 * sentence-boundary scan required a period followed by whitespace and stopped one character short
 * of the end, so a period with nothing after it was never a boundary.
 *
 * <p>Harmless on a seed, where this sentence always has content after it — and a refusal waiting to
 * happen anywhere the text ends there, because a criterion asserting the final part would demand
 * the period and refuse a correct artifact printing the value without it.
 */
class InputFormatExampleSentenceEndTest {

    private static final String LINE =
            "Quotes are removed from the value so the line  a,\"b,c\",\"d\"\"e\"  has THREE fields: a / b,c / d\"e.";

    @Test
    void theFinalPartIsTheSameWhereverTheSentenceSits() {
        var alone = InputFormatExample.readFrom(LINE);
        var followed = InputFormatExample.readFrom(LINE + "\nA naive split on commas is not a correct reader.");
        assertThat(alone).isPresent();
        assertThat(followed).isPresent();
        assertThat(alone.get().parts()).containsExactly("a", "b,c", "d\"e");
        assertThat(alone.get().parts()).isEqualTo(followed.get().parts());
    }

    /**
     * A period inside a value is not a sentence end.
     *
     * <p>⚠ The sample must also be DISCRIMINATING or {@code readFrom} declines by design — a first
     * attempt here used {@code a,"b.5",c}, which naive splitting already parses into three, so the
     * example exercises nothing the format does and silence is the right answer. The quoted comma
     * is what makes this one worth parsing.
     */
    @Test
    void aPeriodInsideAValueIsNotASentenceEnd() {
        var e = InputFormatExample.readFrom(
                "So the line  a,\"b.5,x\",c  has THREE fields: a / b.5,x / c.");
        assertThat(e).isPresent();
        assertThat(e.get().parts()).containsExactly("a", "b.5,x", "c");
    }
}
