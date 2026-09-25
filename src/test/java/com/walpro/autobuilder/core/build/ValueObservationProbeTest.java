package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CQ: the probe instruments a test to reveal what an entry point returns, and reads the value back
 * from the runner's report. Invented coordinates; the test shape is structdiff run 11's.
 */
class ValueObservationProbeTest {

    private static final String TEST = """
            package shop;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;

            class GadgetTest {
                @Test
                void describeReturnsNonEmptyString() {
                    Part part = new Part();
                    String s = Gadget.describe(part);
                    assertNotNull(s);
                    assertFalse(s.isEmpty());
                }
            }
            """;

    @Test
    void theCopyAssertsTheStoredResultAgainstTheSentinelRightAfterTheCall() {
        String probe = ValueObservationProbe.instrument(TEST, "GadgetTest", "describeReturnsNonEmptyString",
                "Gadget", "describe").orElseThrow();

        assertThat(probe).contains("class GadgetTestValueObservation")
                .contains("org.junit.jupiter.api.Assertions.assertEquals(\"" + ValueObservationProbe.SENTINEL
                        + "\", String.valueOf(s));");
        assertThat(probe.indexOf("String.valueOf(s)")).isGreaterThan(probe.indexOf("Gadget.describe(part)"))
                .isLessThan(probe.indexOf("assertNotNull(s)"));
    }

    @Test
    void aTestThatDoesNotStoreTheResultIsNotInstrumented() {
        String inline = TEST.replace("String s = Gadget.describe(part);\n", "")
                .replace("assertNotNull(s);", "assertNotNull(Gadget.describe(part));").replace("assertFalse(s.isEmpty());", "");

        assertThat(ValueObservationProbe.instrument(inline, "GadgetTest", "describeReturnsNonEmptyString",
                "Gadget", "describe")).isEmpty();
    }

    @Test
    void aSourceWithoutTheProbesAssertionLibraryIsLeftAlone() {
        String other = TEST.replace("org.junit.jupiter.api", "org.other.api");

        assertThat(ValueObservationProbe.instrument(other, "GadgetTest", "describeReturnsNonEmptyString",
                "Gadget", "describe")).isEmpty();
    }

    @Test
    void theObservedValueIsReadWhole_evenAcrossLines(@TempDir Path dir) throws Exception {
        Path xml = dir.resolve("TEST-shop.GadgetTestValueObservation.xml");
        Files.writeString(xml, "<?xml version=\"1.0\"?><testsuite><testcase name=\"describeReturnsNonEmptyString\">"
                + "<failure message=\"expected: &lt;" + ValueObservationProbe.SENTINEL + "&gt; but was: &lt;Parts:&#10;  wheel (added)&gt;\""
                + " type=\"org.opentest4j.AssertionFailedError\"/></testcase></testsuite>");

        assertThat(ValueObservationProbe.sentinelMismatch(xml)).contains("\"Parts:\\n  wheel (added)\"");
    }
}
