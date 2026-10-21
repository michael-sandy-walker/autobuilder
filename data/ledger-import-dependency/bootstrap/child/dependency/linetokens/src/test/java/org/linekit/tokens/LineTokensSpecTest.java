package org.linekit.tokens;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The specification of linetokens: what LineTokenizer.tokenize returns, and where it says a line is wrong. */
class LineTokensSpecTest {

    @Test
    void readsPairsInOrder() {
        assertThat(LineTokenizer.tokenize("a=1;b=two"))
                .containsExactly(new Token("a", "1"), new Token("b", "two"));
    }

    @Test
    void trimsSpaceAroundNamesAndBareValues() {
        assertThat(LineTokenizer.tokenize("  name =  some value ;  x=y  "))
                .containsExactly(new Token("name", "some value"), new Token("x", "y"));
    }

    @Test
    void anEmptyOrBlankLineHasNoTokens() {
        assertThat(LineTokenizer.tokenize("")).isEmpty();
        assertThat(LineTokenizer.tokenize("   ")).isEmpty();
    }

    @Test
    void aTrailingSeparatorIsAllowed() {
        assertThat(LineTokenizer.tokenize("a=1;")).containsExactly(new Token("a", "1"));
        assertThat(LineTokenizer.tokenize("a=1 ; ")).containsExactly(new Token("a", "1"));
    }

    @Test
    void aBareValueMayBeEmpty() {
        assertThat(LineTokenizer.tokenize("a=;b=2")).containsExactly(new Token("a", ""), new Token("b", "2"));
    }

    @Test
    void aQuotedValueKeepsSeparatorsSpacesAndEquals() {
        assertThat(LineTokenizer.tokenize("memo=\" coffee; large = 2 \";n=1"))
                .containsExactly(new Token("memo", " coffee; large = 2 "), new Token("n", "1"));
    }

    @Test
    void insideQuotesABackslashEscapesAQuoteOrABackslash() {
        assertThat(LineTokenizer.tokenize("q=\"say \\\"hi\\\" \\\\ there\""))
                .containsExactly(new Token("q", "say \"hi\" \\ there"));
    }

    @Test
    void namesStartWithALetterAndHoldLettersDigitsAndUnderscores() {
        assertThat(LineTokenizer.tokenize("a_1=x;B2=y")).containsExactly(new Token("a_1", "x"), new Token("B2", "y"));
    }

    @Test
    void theResultCannotBeModified() {
        List<Token> tokens = LineTokenizer.tokenize("a=1");
        assertThatThrownBy(() -> tokens.add(new Token("b", "2"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aPairWithoutEqualsIsRejectedAtThePairsFirstCharacter() {
        TokenizeException e = catchThrowableOfType(() -> LineTokenizer.tokenize("a=1;  oops;b=2"), TokenizeException.class);
        assertThat(e).isNotNull();
        assertThat(e.position()).isEqualTo(6);
        assertThat(e.getMessage()).contains("missing '='");
    }

    @Test
    void aNameThatIsNotANameIsRejectedWhereTheNameStarts() {
        TokenizeException e = catchThrowableOfType(() -> LineTokenizer.tokenize("a=1; 9lives=x"), TokenizeException.class);
        assertThat(e).isNotNull();
        assertThat(e.position()).isEqualTo(5);
        assertThat(e.getMessage()).contains("invalid name");
        TokenizeException empty = catchThrowableOfType(() -> LineTokenizer.tokenize("=x"), TokenizeException.class);
        assertThat(empty).isNotNull();
        assertThat(empty.position()).isEqualTo(0);
    }

    @Test
    void anUnterminatedQuoteIsRejectedAtTheOpeningQuote() {
        TokenizeException e = catchThrowableOfType(() -> LineTokenizer.tokenize("a=1;b= \"open"), TokenizeException.class);
        assertThat(e).isNotNull();
        assertThat(e.position()).isEqualTo(7);
        assertThat(e.getMessage()).contains("unterminated quote");
    }

    @Test
    void textAfterAClosingQuoteIsRejectedWhereItStarts() {
        TokenizeException e = catchThrowableOfType(() -> LineTokenizer.tokenize("a=\"x\" y;b=2"), TokenizeException.class);
        assertThat(e).isNotNull();
        assertThat(e.position()).isEqualTo(6);
        assertThat(e.getMessage()).contains("after closing quote");
    }

    @Test
    void aRepeatedNameIsRejectedAtTheSecondOne() {
        TokenizeException e = catchThrowableOfType(() -> LineTokenizer.tokenize("a=1; b=2; a=3"), TokenizeException.class);
        assertThat(e).isNotNull();
        assertThat(e.position()).isEqualTo(10);
        assertThat(e.getMessage()).contains("duplicate name");
    }

    @Test
    void aTokenizeExceptionIsAnIllegalArgumentException() {
        assertThat(new TokenizeException("bad", 3)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("bad");
        assertThat(new TokenizeException("bad", 3).position()).isEqualTo(3);
    }

    @Test
    void aTokenSaysWhatItHolds() {
        Token t = new Token("k", "v");
        assertThat(t.name()).isEqualTo("k");
        assertThat(t.value()).isEqualTo("v");
    }
}
