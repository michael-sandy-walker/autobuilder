package com.walpro.autobuilder.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CQ: values the code was observed to return are kept for the run, and dropped once the code
 * they describe changes. Invented coordinates; the message shape is structdiff run 7's.
 */
class ObservedBehaviourTest {

    @TempDir
    Path root;

    private static TestRunReport.Failure failure(String method, String message) {
        return new TestRunReport.Failure("shop.widget.GadgetTest", method, TestRunReport.Outcome.FAILED, message);
    }

    private String main(String content) throws Exception {
        Path f = root.resolve("src/main/java/shop/widget/Gadget.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        return ObservedBehaviour.mainDigestOnDisk(root);
    }

    @Test
    void anObservedValueOutlivesTheCycleThatFoundIt() throws Exception {
        ObservedBehaviour ledger = new ObservedBehaviour();
        String digest = main("record Gadget(String kind) {}");

        ledger.observe(List.of(failure("rootKind", "expected: <PART> but was: <WHOLE>")), digest);
        ledger.observe(List.of(failure("other", "expected: <3> but was: <4>")), digest);

        assertThat(ledger.promptBlock(digest))
                .contains("OBSERVED BEHAVIOUR")
                .contains("GadgetTest.rootKind: expected: <PART> but was: <WHOLE>")
                .contains("GadgetTest.other: expected: <3> but was: <4>");
    }

    /** An observation of code that has since changed is not shown as if it were still true. */
    @Test
    void anObservationOfCodeThatHasChangedIsDropped() throws Exception {
        ObservedBehaviour ledger = new ObservedBehaviour();
        String before = main("record Gadget(String kind) {}");
        ledger.observe(List.of(failure("rootKind", "expected: <PART> but was: <WHOLE>")), before);

        String after = main("record Gadget(String kind, int size) {}");

        assertThat(after).isNotEqualTo(before);
        assertThat(ledger.promptBlock(after)).isNull();
    }

    /** Only a value the code returned is a fact about it; a bare assertion failure says nothing. */
    @Test
    void aFailureWithoutAnObservedValueIsNotKept() throws Exception {
        ObservedBehaviour ledger = new ObservedBehaviour();
        String digest = main("record Gadget(String kind) {}");

        ledger.observe(List.of(failure("somethingHolds", "Expected the part to be present")), digest);

        assertThat(ledger.promptBlock(digest)).isNull();
    }
}
