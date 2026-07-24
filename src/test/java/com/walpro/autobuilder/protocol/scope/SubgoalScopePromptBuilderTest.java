package com.walpro.autobuilder.protocol.scope;

import com.walpro.autobuilder.analysis.diff.ProjectDiffResult;
import com.walpro.autobuilder.analysis.diff.model.ElementDescriptor;
import com.walpro.autobuilder.analysis.diff.model.ElementType;
import com.walpro.autobuilder.analysis.diff.model.StructuralDiffNode;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubgoalScopePromptBuilderTest {

    private static final String FILE_BODY = "package com.example;\npublic class BookController { /* 200 lines */ }";

    private static CycleContextData ctxWithDiff() {
        StructuralDiffNode fileNode = new StructuralDiffNode(
                null,
                new ElementDescriptor("src/main/java/com/example/BookController.java", ElementType.FILE, FILE_BODY, Map.of()),
                List.of(new StructuralDiffNode(
                        null,
                        new ElementDescriptor("com.example.BookController.createBook", ElementType.METHOD, "body", Map.of()),
                        Collections.emptyList())));
        ElementDescriptor rootDesc = new ElementDescriptor("root", ElementType.PROJECT, "", Map.of());
        StructuralDiffNode root = new StructuralDiffNode(rootDesc, rootDesc, List.of(fileNode));
        return CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .currentSubGoalDescription("Implement POST /books")
                .build()
                .withProjectDiff(new ProjectDiffResult(root,
                        Map.of("src/main/java/com/example/BookController.java", FILE_BODY), Set.of()));
    }

    @Test
    void promptCarriesDeclarationLevelDiff_notRawFileContents() {
        String prompt = SubgoalScopePromptBuilder.buildUserPrompt(ctxWithDiff(), List.of(), List.of());

        assertThat(prompt).contains("--- STRUCTURAL DIFF (changed declarations) ---");
        assertThat(prompt).contains("BookController");
        // declaration identity is present, raw file bodies and record toString syntax are not
        assertThat(prompt).doesNotContain("/* 200 lines */");
        assertThat(prompt).doesNotContain("ProjectDiffResult[");
        assertThat(prompt).doesNotContain("ElementDescriptor[");
    }

    @Test
    void promptKeepsNoOpSummaryContract() {
        CycleContextData ctx = CycleContextData.builder()
                .currentSubGoalId("goal-1")
                .currentSubGoalDescription("Implement POST /books")
                .build();

        String prompt = SubgoalScopePromptBuilder.buildUserPrompt(ctx, List.of(), List.of());

        // the system prompt's NO_OP RULE keys off this summary block — it must survive
        assertThat(prompt).contains("--- DIFF SUMMARY ---");
        assertThat(prompt).contains("NO_DIFF_OBJECT: true");
    }

    @Test
    void remainingGoalsRenderedWithIds() {
        GoalEntity g = new GoalEntity("goal-2", "Implement GET /books", "planner", null, null);

        String prompt = SubgoalScopePromptBuilder.buildUserPrompt(ctxWithDiff(), List.of(g), List.of());

        assertThat(prompt).contains("- goal-2: Implement GET /books");
    }
}
