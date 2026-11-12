package com.walpro.autobuilder.core.build;

import java.util.List;
import java.util.Set;

/**
 * T4-CQ: DATA -- the assertion calls of the Java test ecosystems, and the imports that say a test
 * source uses one. Ecosystem knowledge lives in a table, never in the logic that reads it
 * ({@link EntryPointAssertions}), and the logic has no opinion on a source importing none of these.
 *
 * <p>"Value" assertions compare what code returned against a stated value. The others --
 * not-null, not-empty, true-of-something-else -- say that something came back, not what.
 */
public final class TestAssertionVocabulary {

    private TestAssertionVocabulary() {
    }

    /** Import prefixes that mark a source as written against one of these ecosystems. */
    public static final List<String> GATE_IMPORT_PREFIXES = List.of(
            "org.junit", "org.assertj", "org.hamcrest", "org.testng");

    /**
     * Calls that compare a result with an expected value (static-assert style). {@code assertNull} is
     * {@code assertEquals(null, x)}: null is an exact value, and some entry points return it by
     * contract (participation run 2026-09-25_11-45-07: a {@code revoke} test asserting the revoked
     * role is gone, and any test of a horizon that is null when nothing decays, counted as asserting nothing).
     */
    public static final Set<String> VALUE_ASSERTIONS = Set.of(
            "assertEquals", "assertArrayEquals", "assertIterableEquals", "assertLinesMatch", "assertSame",
            "assertNull");

    /** Fluent-chain steps that compare with an expected value (fluent-assert style). */
    public static final Set<String> VALUE_CHAIN_STEPS = Set.of(
            "isEqualTo", "containsExactly", "containsExactlyInAnyOrder", "hasSize", "containsOnly", "isSameAs",
            "containsExactlyEntriesOf", "isEqualToIgnoringWhitespace", "equalTo", "isNull", "nullValue");

    /**
     * Fluent-chain steps that state the value as well, but share their name with ordinary methods (a text's or a
     * collection's own {@code contains}): they count only in a chain that starts at one of {@link #FLUENT_ENTRIES}.
     * {@code assertThat(optional).contains("EA-00001-C")} is how an optional result is compared with a literal, and
     * it was read as asserting nothing (run {@code data/pantry-rev3}, 2026-11-12: one refusal five cycles running).
     */
    public static final Set<String> VALUE_STEPS_OF_A_FLUENT_CHAIN = Set.of(
            "contains", "hasValue", "containsSame", "containsEntry", "hasToString");

    /** The calls a fluent assertion chain starts at. */
    public static final Set<String> FLUENT_ENTRIES = Set.of("assertThat", "then");

    /** Truth assertions: a value assertion only when they wrap the entry point's own call (a boolean result). */
    public static final Set<String> TRUTH_ASSERTIONS = Set.of("assertTrue", "assertFalse");

    /** Annotations that mark a method as a test. */
    public static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");

    /**
     * Annotations that supply a parameterized test's invocations whose rows are written inline as
     * literals, one row per invocation in order ({@link ParameterizedCaseInput}).
     */
    public static final Set<String> LITERAL_ROW_SOURCES = Set.of("CsvSource", "ValueSource");

    /** Any annotation named like this supplies invocations; two on one test make the index ambiguous. */
    public static final String ARGUMENT_SOURCE_SUFFIX = "Source";

    /** The attribute of an inline row source that holds its rows as one text block, one row per line. */
    public static final String TEXT_BLOCK_ROWS = "textBlock";

    /** A text-block row starting with this is a comment, not an invocation. */
    public static final String TEXT_BLOCK_COMMENT = "#";

    /** An attribute that makes the first row a header, shifting every index by one. */
    public static final String HEADER_ROW_ATTRIBUTE = "useHeadersInDisplayName";

    /** Attributes of an inline row source that configure parsing and carry no rows. */
    public static final Set<String> NON_ROW_ATTRIBUTES = Set.of("delimiter", "delimiterString",
            "quoteCharacter", "emptyValue", "nullValues", "ignoreLeadingAndTrailingWhitespace", "maxCharsPerColumn");

    /**
     * The one assertion call the value-observation probe writes ({@link ValueObservationProbe}), and the
     * import that says a source can compile it. $SENTINEL and $VALUE are filled in by the probe.
     */
    public static final String PROBE_ASSERTION = "org.junit.jupiter.api.Assertions.assertEquals($SENTINEL, $VALUE);";
    public static final String PROBE_ASSERTION_IMPORT = "org.junit.jupiter";
}
