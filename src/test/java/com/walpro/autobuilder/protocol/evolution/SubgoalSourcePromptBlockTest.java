package com.walpro.autobuilder.protocol.evolution;

import com.walpro.autobuilder.agent.evolution.model.EvolutionOutcome;
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

        assertThat(block).contains("SOURCE OF THE EXISTING FILES THIS WORK NAMES",
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
    void aFileTooLargeForTheHeadroom_isShownAsItsEnd_neverCut() {
        String huge = "package zeta;\npublic class Quill {\n" + "    int a;\n".repeat(4000) + "}\n";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/zeta/Inkwell.java", INKWELL);
        files.put("src/main/java/zeta/Quill.java", huge);

        String block = SubgoalSourcePromptBlock.render(ctx("Change Quill and Inkwell", List.of(), files), 2_000);

        // T6-T: a file the work may change keeps the end of its type, where a new member goes -- and only that
        assertThat(block).contains("```java [src/main/java/zeta/Inkwell.java]",
                "```java [src/main/java/zeta/Quill.java, lines 4002-4003, the end of Quill, where a new member goes]\n    int a;\n}\n```")
                .doesNotContain("    int a;\n    int a;").doesNotContain("too large to show here");
    }

    /**
     * T4-EB: the file the GOAL names is shown even when the subgoal names only a test -- run
     * 2026-09-29_16-21-43 rewrote a 502-line class it had only seen as a two-line outline -- and so is a
     * file the rejection reasons name; the subgoal's own files come first.
     */
    @Test
    void theFileTheGoalOrTheRejectionNamesIsShownToo() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/zeta/Inkwell.java", INKWELL);
        files.put("src/main/java/zeta/Nib.java", NIB);
        files.put("src/test/java/zeta/InkwellTest.java", "package zeta;\nclass InkwellTest {}\n");
        CycleContextData goalNames = CycleContextData.builder().currentSubGoalDescription("Analyse InkwellTest's cases")
                .currentGoalDescription("The one class to change is Inkwell.").previousOutput(files).build();

        String block = SubgoalSourcePromptBlock.render(goalNames, 20_000);

        assertThat(block).contains("[src/test/java/zeta/InkwellTest.java]", "[src/main/java/zeta/Inkwell.java]").doesNotContain("Nib");
        assertThat(block.indexOf("InkwellTest.java]")).isLessThan(block.indexOf("zeta/Inkwell.java]"));

        CycleContextData rejectionNames = CycleContextData.builder().currentSubGoalDescription("Make the drops count")
                .rejectionFeedback(List.of("Nib.width() still returns 1")).previousOutput(files).build();
        assertThat(SubgoalSourcePromptBlock.render(rejectionNames, 20_000)).contains("[src/main/java/zeta/Nib.java]");
    }

    /** A class of {@code n} filler methods, plus {@code drain} with its comment -- larger than a small headroom. */
    private static String quill(int n) {
        StringBuilder sb = new StringBuilder("package zeta;\n\npublic class Quill {\n\n    private int drops;\n\n");
        for (int i = 0; i < n; i++) sb.append("    public int filler").append(i).append("() { return ").append(i).append("; }\n\n");
        sb.append("    /** Pours the given drops out, never below zero. */\n")
          .append("    public int drain(int less) {\n        drops = Math.max(0, drops - less);\n        return drops;\n    }\n}\n");
        return sb.toString();
    }

    /**
     * T6-T: a read-only test named first must not take the place of the class the work changes.
     * Ladder-rows run 2026-10-05_12-25-01: the class to edit was left out of 49 of 61 prompts behind a
     * specification test and a finished class, and the model guessed its text.
     */
    @Test
    void theClassToChangeIsShownBeforeAReadOnlyTestNamedFirst() {
        String bigTest = "package zeta;\nclass QuillTest {\n" + "    void t() {}\n".repeat(60) + "}\n";
        String quill = quill(40);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/test/java/zeta/QuillTest.java", bigTest);
        files.put("src/main/java/zeta/Quill.java", quill);
        int roomForOne = 2 * (PromptTokenBudgetSize.of(quill) + 200);

        String block = SubgoalSourcePromptBlock.render(ctx("Make QuillTest pass by changing Quill", List.of(), files),
                roomForOne, p -> p.startsWith("src/test/"));

        assertThat(block).contains("```java [src/main/java/zeta/Quill.java]", "public int filler39()")
                .contains("too large to show here: src/test/java/zeta/QuillTest.java");
    }

    /** T6-T: a file the last, discarded answer could not edit is shown first, even unnamed by the work. */
    @Test
    void theFileADiscardedEditCouldNotChangeIsShownFirst() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/zeta/Inkwell.java", INKWELL);
        files.put("src/main/java/zeta/Nib.java", NIB);
        EvolutionOutcome refused = new EvolutionOutcome(List.of(), List.of(), EvolutionOutcome.INVALID_PREFIX
                + "Response did not match protocol: an edit could not be applied: src/main/java/zeta/Nib.java: hunk 1's"
                + " SEARCH text is not in the file as it is now", "<<<<<<< SEARCH\nold\n=======\nnew\n>>>>>>> REPLACE");
        CycleContextData c = CycleContextData.builder().currentSubGoalDescription("Make Inkwell pour")
                .previousOutput(files).lastEvolutionOutcome(refused).build();

        String block = SubgoalSourcePromptBlock.render(c, 20_000);

        assertThat(block).contains("[src/main/java/zeta/Nib.java]", "[src/main/java/zeta/Inkwell.java]");
        assertThat(block.indexOf("zeta/Nib.java]")).isLessThan(block.indexOf("zeta/Inkwell.java]"));
    }

    /**
     * T6-T: a file too large to show whole is shown as the members the work names, each exactly as the file
     * has it (comment included), so a SEARCH part can be copied from it -- and the rest is not shown.
     */
    @Test
    void aFileTooLargeToShowWholeIsShownAsTheMembersTheWorkNames_exactly() {
        String quill = quill(400);
        Map<String, String> files = Map.of("src/main/java/zeta/Quill.java", quill);

        String block = SubgoalSourcePromptBlock.render(
                ctx("Quill.drain must never go below zero", List.of(), files), 2_000);

        String drain = quill.substring(quill.indexOf("    /** Pours"), quill.indexOf("    }\n}") + "    }".length());
        assertThat(block).contains("Only parts of src/main/java/zeta/Quill.java are shown here", "ONLY with edit blocks",
                "```java [src/main/java/zeta/Quill.java, lines ", drain).doesNotContain("filler12()")
                .doesNotContain("too large to show here");
    }

    /** T6-T: the members a discarded answer's SEARCH part tried to change count as named. */
    @Test
    void theMembersADiscardedAnswerTriedToChangeAreTheOnesShown() {
        String quill = quill(400);
        Map<String, String> files = Map.of("src/main/java/zeta/Quill.java", quill);
        EvolutionOutcome refused = new EvolutionOutcome(List.of(), List.of(), EvolutionOutcome.INVALID_PREFIX
                + "an edit could not be applied: src/main/java/zeta/Quill.java: hunk 1's SEARCH text is not in the file",
                "```edit [src/main/java/zeta/Quill.java]\n<<<<<<< SEARCH\n    public int filler7() { return 70; }\n=======\n"
                        + "    public int filler7() { return 7; }\n>>>>>>> REPLACE\n```");
        CycleContextData c = CycleContextData.builder().currentSubGoalDescription("Make the numbers right")
                .previousOutput(files).lastEvolutionOutcome(refused).build();

        String block = SubgoalSourcePromptBlock.render(c, 2_000);

        assertThat(block).contains("    public int filler7() { return 7; }").doesNotContain("filler8()");
    }

    /**
     * T6-T: a subgoal about a failing read-only test is about the class that test uses: it comes before the
     * other files the goal names, so it is the one shown whole.
     */
    @Test
    void theClassAFailingReadOnlyTestUsesComesBeforeTheOtherFilesTheGoalNames() {
        String quill = quill(40);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/zeta/Inkwell.java", INKWELL + "// " + "padding ".repeat(400) + "\n");
        files.put("src/main/java/zeta/Quill.java", quill);
        files.put("src/test/java/zeta/QuillTest.java", "package zeta;\nclass QuillTest { Quill q = new Quill(); }\n");
        CycleContextData c = CycleContextData.builder()
                .currentSubGoalDescription("Make the suite pass: QuillTest.drainsToZero fails")
                .currentGoalDescription("The classes to change are Inkwell and Quill.").previousOutput(files).build();

        String block = SubgoalSourcePromptBlock.render(c, 2 * (PromptTokenBudgetSize.of(quill) + 200), p -> p.startsWith("src/test/"));

        assertThat(block).contains("```java [src/main/java/zeta/Quill.java]", "public int filler39()")
                .doesNotContain("```java [src/main/java/zeta/Inkwell.java]");
    }

    /**
     * T6-T, ladder-grind 2026-10-10_23-45-15: for one failing test of one class the prompt carried two unrelated
     * classes whole, because the goal names every class of the change. A work item that names the test it may only
     * read is about that test: its files, every project class the test refers to -- named by the goal or not --
     * and what the last rejection named; what only the goal names is left out, room or no room.
     */
    @Test
    void anItemAboutItsFailingTestShowsWhatTheTestRefersTo_andNotWhatOnlyTheGoalNames() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/zeta/Inkwell.java", INKWELL);
        files.put("src/main/java/zeta/Nib.java", NIB);
        files.put("src/main/java/zeta/Quill.java", quill(3));
        files.put("src/main/java/zeta/Blotter.java", "package zeta;\npublic class Blotter { public int dab() { return 1; } }\n");
        files.put("src/test/java/zeta/QuillTest.java",
                "package zeta;\nclass QuillTest { Quill q = new Quill(); Blotter b = new Blotter(); }\n");
        CycleContextData c = CycleContextData.builder()
                .currentSubGoalDescription("Make the suite pass: QuillTest.drainsToZero fails")
                .currentGoalDescription("The classes to change are Inkwell, Nib and Quill.")
                .rejectionFeedback(List.of("The evaluator found: Nib still rounds down."))
                .previousOutput(files).build();

        String block = SubgoalSourcePromptBlock.render(c, 200_000, p -> p.startsWith("src/test/"), true);

        assertThat(block).contains("[src/main/java/zeta/Quill.java]", "[src/test/java/zeta/QuillTest.java")
                .as("the test refers to Blotter, which the goal never names").contains("[src/main/java/zeta/Blotter.java]")
                .as("the last rejection named Nib").contains("[src/main/java/zeta/Nib.java]")
                .as("only the goal names Inkwell, and there was room for it")
                .doesNotContain("```java [src/main/java/zeta/Inkwell.java]")
                .as("what is left out stays as what it declares: the class exists, with this constructor and member")
                .contains("- src/main/java/zeta/Inkwell.java",
                        "record Inkwell(String well, int drops, Integer spare): Inkwell pour(int more)");
    }

    /**
     * T6-T, ladder-rows 2026-10-12_11-36-58 and 2026-10-12_13-39-25: the work items before the suite compiles name
     * their tests as well, so the narrowing took the class the ticket is about out of most prompts and the suite
     * took 55 and 68 minutes to compile (17 before). A test that does not compile has not failed: until the suite
     * has run on the tree the work is the whole coupled change, and every file the goal names is shown.
     */
    @Test
    void untilTheSuiteHasRunOnTheTree_anItemThatNamesItsTestStillGetsEveryFileTheGoalNames() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/zeta/Inkwell.java", INKWELL);
        files.put("src/main/java/zeta/Nib.java", NIB);
        files.put("src/main/java/zeta/Quill.java", quill(3));
        files.put("src/test/java/zeta/QuillTest.java", "package zeta;\nclass QuillTest { Quill q = new Quill(); }\n");
        CycleContextData c = CycleContextData.builder()
                .currentSubGoalDescription("Write Quill so that QuillTest compiles and passes")
                .currentGoalDescription("The classes to change are Inkwell, Nib and Quill.").previousOutput(files).build();

        String block = SubgoalSourcePromptBlock.render(c, 200_000, p -> p.startsWith("src/test/"), false);

        assertThat(block).contains("```java [src/main/java/zeta/Quill.java]", "```java [src/main/java/zeta/Inkwell.java]",
                        "```java [src/main/java/zeta/Nib.java]")
                .doesNotContain("These files belong to the same change");
    }

    /** A file whose signatures do not fit is still named, by its members' names or at least by its path. */
    @Test
    void aLeftOutFileTooLargeForItsSignatures_isStillNamed() {
        StringBuilder wide = new StringBuilder("package zeta;\npublic class Ledger {\n");
        for (int i = 0; i < 400; i++) {
            wide.append("    public java.util.Map<String, java.util.List<Integer>> entryNumber").append(i)
                    .append("(java.util.Map<String, java.util.List<Integer>> previousEntries) { return null; }\n");
        }
        wide.append("}\n");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/zeta/Ledger.java", wide.toString());
        files.put("src/main/java/zeta/Quill.java", quill(3));
        files.put("src/test/java/zeta/QuillTest.java", "package zeta;\nclass QuillTest { Quill q = new Quill(); }\n");
        CycleContextData c = CycleContextData.builder()
                .currentSubGoalDescription("Make the suite pass: QuillTest.drainsToZero fails")
                .currentGoalDescription("The classes to change are Ledger and Quill.").previousOutput(files).build();
        int room = 2 * (com.walpro.autobuilder.protocol.PromptTokenBudget.estimateTokens(quill(3)) + 600);

        String block = SubgoalSourcePromptBlock.render(c, room, p -> p.startsWith("src/test/"), true);

        assertThat(block).contains("```java [src/main/java/zeta/Quill.java]", "- src/main/java/zeta/Ledger.java")
                .doesNotContain("java.util.Map<String, java.util.List<Integer>> previousEntries");
    }

    /** An item that names only files it may write still gets what the goal names: it says what else there is. */
    @Test
    void anItemThatNamesNoReadOnlyFileStillGetsTheFilesTheGoalNames() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/zeta/Inkwell.java", INKWELL);
        files.put("src/main/java/zeta/Quill.java", quill(3));
        CycleContextData c = CycleContextData.builder()
                .currentSubGoalDescription("Change Quill so that it drains")
                .currentGoalDescription("The classes to change are Inkwell and Quill.").previousOutput(files).build();

        assertThat(SubgoalSourcePromptBlock.render(c, 200_000, p -> p.startsWith("src/test/")))
                .contains("[src/main/java/zeta/Quill.java]", "[src/main/java/zeta/Inkwell.java]");
    }

    /** T6-T: a read-only file the work names members of is shown as those members, leaving room for the rest. */
    @Test
    void aReadOnlyTestIsShownAsTheCasesTheWorkNames() {
        StringBuilder test = new StringBuilder("package zeta;\nclass QuillTest {\n");
        for (int i = 0; i < 50; i++) test.append("    void case").append(i).append("() { new Quill(); }\n");
        test.append("    void drainsToZero() {\n        assert new Quill().drain(5) == 0;\n    }\n}\n");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/test/java/zeta/QuillTest.java", test.toString());
        files.put("src/main/java/zeta/Inkwell.java", INKWELL);

        String block = SubgoalSourcePromptBlock.render(ctx("QuillTest.drainsToZero fails; change Inkwell", List.of(), files),
                20_000, p -> p.startsWith("src/test/"));

        assertThat(block).contains("Only parts of src/test/java/zeta/QuillTest.java", "This file is not yours to change.",
                "assert new Quill().drain(5) == 0;",
                "```java [src/main/java/zeta/Inkwell.java]").doesNotContain("case7()");
    }

    /**
     * T6-T: an interface of records too large to show whole -- a record the work names is a member, and the end of
     * the type is shown so a record the work ADDS has a place to go (ladder-grind field45 run 1: the file was "too
     * large to show here" in 31 of 35 prompts).
     */
    @Test
    void anInterfaceOfRecords_showsTheNamedRecordAndItsEnd_whereANewRecordGoes() {
        StringBuilder ink = new StringBuilder("package zeta;\npublic sealed interface InkEvent {\n    String name();\n\n");
        for (int i = 0; i < 120; i++) {
            ink.append("    /** Blot ").append(i).append(". */\n    record Blot").append(i)
                    .append("(String cycle, int drops) implements InkEvent {\n        public String name() { return \"blot")
                    .append(i).append("\"; }\n    }\n\n");
        }
        ink.append("    record Smudge(String cycle) implements InkEvent {\n        public String name() { return \"smudge\"; }\n    }\n}\n");
        Map<String, String> files = Map.of("src/main/java/zeta/InkEvent.java", ink.toString());

        String block = SubgoalSourcePromptBlock.render(
                ctx("Add the record Spill to InkEvent, shaped like Blot7", List.of(), files), 2_000);

        assertThat(block).contains("Only parts of src/main/java/zeta/InkEvent.java",
                "the end of InkEvent, where a new member goes]\n    record Smudge(String cycle) implements InkEvent {",
                "    }\n}\n```", "    /** Blot 7. */\n    record Blot7(String cycle, int drops)")
                .doesNotContain("record Blot8(").doesNotContain("too large to show here");
    }

    private static final class PromptTokenBudgetSize {
        static int of(String file) {
            return com.walpro.autobuilder.protocol.PromptTokenBudget.estimateTokens(file);
        }
    }

    /**
     * T6-T, ladder-grind 2026-10-14_00-22-30: a 1,121-line file shown in parts hid the helpers the work did not name, and
     * 15 of 17 late candidates failed on a second copy of one ("method isInAnonymousClass(...) is already defined").
     * What a part does not show is listed by signature, so the model knows it exists.
     */
    @Test
    void aFileShownInParts_listsEverythingElseItDeclaresBySignature() {
        StringBuilder big = new StringBuilder("package zeta;\npublic class Ledger {\n");
        for (int i = 0; i < 60; i++) {
            big.append("    /** Entry ").append(i).append(". */\n    int entry").append(i).append("(int page) {\n");
            for (int k = 0; k < 6; k++) big.append("        page = page + ").append(k).append(";\n");
            big.append("        return page;\n    }\n");
        }
        big.append("    private static boolean isBlotted(String ink, int nib) { return ink == null; }\n}\n");
        String ledger = big.toString();

        String block = SubgoalSourcePromptBlock.parts("src/main/java/zeta/Ledger.java", ledger,
                "Fix entry7() so the page count is right", 2_000, false);

        assertThat(block).contains("int entry7(int page)")
                .contains("The rest of this file, not shown", "Ledger: int entry0(int page) (line 3)", "Ledger: int entry59(int page)")
                .as("shown in a part, so not listed again: the named member and the end of the type")
                .doesNotContain("Ledger: int entry7(int page)", "Ledger: boolean isBlotted");
    }
}
