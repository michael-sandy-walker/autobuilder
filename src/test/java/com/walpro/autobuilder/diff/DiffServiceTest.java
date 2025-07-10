package com.walpro.autobuilder.diff;

import com.walpro.autobuilder.config.DiffConfig;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DiffServiceTest {

    private final DiffService diffService = new DiffService(null, new DiffConfig());

    @Test
    void shouldDetectModifiedAddedAndDeletedFiles() {
        Map<String, String> oldFiles = Map.of(
                "A.java", "class A {}",
                "B.java", "class B {}"
        );
        Map<String, String> newFiles = Map.of(
                "A.java", "class A { int x = 1; }",  // modified
                "C.java", "class C {}"               // added
        );
        Set<String> deleted = Set.of("B.java");

        DiffReport report = diffService.compareInMemory(oldFiles, newFiles, deleted);

        assertThat(report.getModifiedFiles()).contains("A.java");
        assertThat(report.getAddedFiles()).contains("C.java");
        assertThat(report.getDeletedFiles()).contains("B.java");
    }
}
