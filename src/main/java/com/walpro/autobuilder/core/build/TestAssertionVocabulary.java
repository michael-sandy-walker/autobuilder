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

    /** Truth assertions: a value assertion only when they wrap the entry point's own call (a boolean result). */
    public static final Set<String> TRUTH_ASSERTIONS = Set.of("assertTrue", "assertFalse");

    /** Annotations that mark a method as a test. */
    public static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest");

    /**
     * The one assertion call the value-observation probe writes ({@link ValueObservationProbe}), and the
     * import that says a source can compile it. $SENTINEL and $VALUE are filled in by the probe.
     */
    public static final String PROBE_ASSERTION = "org.junit.jupiter.api.Assertions.assertEquals($SENTINEL, $VALUE);";
    public static final String PROBE_ASSERTION_IMPORT = "org.junit.jupiter";
}
