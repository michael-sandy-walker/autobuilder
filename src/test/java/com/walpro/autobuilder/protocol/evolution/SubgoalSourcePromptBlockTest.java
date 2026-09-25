package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-CQ: the model changing a file the subgoal names is shown that file's source, not its method
 * names. Invented coordinates; the shape is participation-totals run 1 (a record whose components the
 * summary never showed).
 */
class SubgoalSourcePromptBlockTest {

    private static final String INKWELL = "package zeta;\npublic record Inkwell(String well, int drops, Integer spare) {\n"
            + "    public Inkwell pour(int more) { return new Inkwell(well, drops + more, spare); }\n}\n";
    private static final String NIB = "package zeta;\npublic class Nib { public int width() { return 1; } }\n";

    private static CycleContextData ctx(String subgoal, List<String> criteria, Map<String, String> files) {
        return CycleContextData.builder().currentSubGoalDescription(subgoal).currentSubGoalCriteria(criteria)
                .previousOutput(files).build();
    }

    @Test
    void anExistingFileTheSubgoalNamesIsShownWhole_andOneItDoesNotNameIsNot() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/zeta/Inkwell.java", INKWELL);
        files.put("src/main/java/zeta/Nib.java", NIB);

        String block = SubgoalSourcePromptBlock.render(
                ctx("Add Inkwell.drain(drops) returning a copy with fewer drops", List.of("The Tests pass"), files), 20_000);

        assertThat(block).contains("SOURCE OF THE EXISTING FILES THIS SUBGOAL NAMES",
                "```java [src/main/java/zeta/Inkwell.java]", "public record Inkwell(String well, int drops, Integer spare)",
                "return new Inkwell(well, drops + more, spare);")
                .doesNotContain("Nib");
    }

    /** A capitalised word counts only when it IS a file in the tree -- "Add", "Tests" name nothing. */
    @Test
    void aSubgoalNamingNoExistingFileGetsNoBlock() {
        assertThat(SubgoalSourcePromptBlock.render(ctx("Add Tests for The Gadget", List.of(),
                Map.of("src/main/java/zeta/Nib.java", NIB)), 20_000)).isNull();
    }

    /** A file that does not fit is named, never cut: a half-shown file invites the same invention. */
    @Test
    void aFileTooLargeForTheHeadroomIsNamedAsLeftOut_notCut() {
        String huge = "package zeta;\npublic class Quill {\n" + "    int a;\n".repeat(4000) + "}\n";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/zeta/Inkwell.java", INKWELL);
        files.put("src/main/java/zeta/Quill.java", huge);

        String block = SubgoalSourcePromptBlock.render(ctx("Change Quill and Inkwell", List.of(), files), 2_000);

        assertThat(block).contains("```java [src/main/java/zeta/Inkwell.java]",
                "too large to show here: src/main/java/zeta/Quill.java").doesNotContain("int a;");
    }
}
