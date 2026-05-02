package com.walpro.autobuilder.protocol.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.walpro.autobuilder.goal.GoalEntity;

import com.walpro.autobuilder.analysis.diff.util.DiffSummarizer;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.protocol.core.ContractRule;
import com.walpro.autobuilder.protocol.core.ProblemContextPromptBlocks;
import com.walpro.autobuilder.protocol.meta.MetaIntention;

public class EvaluationProtocolContract {

    public static final String VERSION = "1.0-evaluation";

    private static final List<ContractRule> STATIC_RULES = loadRules();

    private static List<ContractRule> loadRules() {
        List<ContractRule> rules = new ArrayList<>();
        rules.add(new ContractRule("STRUCTURED_RESPONSE",
                "Respond using the required format headers only.",
                """
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: PROGRESSING
                Message: The changes improve modularity and readability.
                """
        ));
        rules.add(new ContractRule("NO_CODE_BLOCKS",
                "Do not include any Java code or file content in this response.",
                "WRONG: ```java\n// code\n```"
        ));
        rules.add(new ContractRule("NO_GOAL_MANIPULATION",
                "Do not propose or modify the goal stack in this context.",
                "WRONG: #GoalStackAppend - Fix goal evaluation"
        ));
        rules.add(new ContractRule("RELEVANT_MESSAGE",
                "Message should be concise and relevant to the mutation impact.",
                "Example: Reduced code duplication across utils."
        ));
        rules.add(new ContractRule("INCLUDE_SCORE",
                "Include a score between 0.0 and 1.0 on its own line, formatted as 'Score: <number>'.",
                "Score: 0.63 (partial implementation — core structure added but edge cases unhandled)"
        ));
        rules.add(new ContractRule("SCORE_FROM_EVIDENCE",
                "Derive the score from specific observations in the diff: what changed, how many concerns were addressed, " +
                        "whether referenced problem paths were touched. Do not default to round values (0.5, 0.7, 0.8, 0.85, 0.9) " +
                        "unless the evidence strongly matches a boundary condition. Scores like 0.63, 0.71, 0.44 are expected.",
                """
                WRONG: Score: 0.90  (no diff-based justification)
                OK:    Score: 0.67  (Message: Two of three problem files touched; third still unaddressed)
                OK:    Score: 0.41  (Message: Change is structurally relevant but introduces a new inconsistency in the referenced path)
                """
        ));
        rules.add(new ContractRule("REJECTION_EXAMPLE",
                "If the mutation is functionally identical to the last or lacks meaningful improvement, reject it.",
                """
                Score: 0.3
                MutationAcceptanceState: REJECTED
                GoalProgressState: STAGNATING
                Message: Repeated mutation with no structural or functional change.
                """
        ));
        rules.add(new ContractRule("STRUCTURAL_SIGNIFICANCE_REQUIRED",
                "Do not accept mutations that are structurally or semantically insignificant.",
                """
                Score: 0.4
                MutationAcceptanceState: REJECTED
                GoalProgressState: STAGNATING
                Message: Minor formatting change only. No functional or structural improvement detected.
                """
        ));
        rules.add(new ContractRule("OPTIONAL_RECOMMENDATION",
        	    "You may include a Recommendation: line suggesting next steps or improvements for future mutations.",
        	    "Recommendation: Refactor helper methods into dedicated utility class."
        ));
        rules.add(new ContractRule(
                "CRITERIA_ASSESSMENT",
                "When an ACCEPTANCE CRITERIA block is present, include a CriteriaCheck: section before Message:. " +
                "List EVERY criterion on its own numbered line as '[N] ✓ <criterion text>' if met, or '[N] ✗ <criterion text> — <why not met>' if unmet. " +
                "You MUST emit exactly one line per criterion — omitting a criterion is not permitted and will be treated as unmet. " +
                "Do NOT mark GoalProgressState: FULFILLED if any criterion is '[N] ✗'. " +
                "Omit CriteriaCheck: entirely when no acceptance criteria are defined.",
                """
                Acceptance criteria:
                * POST /books returns HTTP 201 with book body
                * GET /books returns all previously created books

                OK:
                Score: 0.54
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: STAGNATING
                CriteriaCheck:
                [1] ✓ POST /books returns HTTP 201 with book body
                [2] ✗ GET /books returns all previously created books — endpoint not implemented yet
                Message: POST /books implemented with correct status code; GET /books is missing.

                WRONG (FULFILLED despite unmet criterion):
                Score: 0.90
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: FULFILLED
                CriteriaCheck:
                [1] ✓ POST /books returns HTTP 201 with book body
                [2] ✗ GET /books returns all previously created books — not implemented
                Message: Task done.
                """
        ));
        rules.add(new ContractRule(
                "CRITERIA_EVIDENCE_REQUIRED",
                "When marking a criterion ✓, you must be able to point to specific code in the CURRENT FILE CONTENT " +
                "that directly satisfies it — an exact method signature, return statement, condition, or field declaration. " +
                "Marking ✓ based on naming conventions, plausible intent, or comment text is not sufficient. " +
                "Marking ✓ is appropriate and encouraged whenever the satisfying code is clearly present. " +
                "If the specific satisfying code is not visible in the diff or current file content, mark ✗ and name what is missing.",
                """
                Criterion: POST /books returns HTTP 201 with book body

                Code shows: return ResponseEntity.status(HttpStatus.CREATED).body(book);
                OK:
                [1] ✓ POST /books returns HTTP 201 with book body

                Code shows: return ResponseEntity.ok(book);
                OK:
                [1] ✗ POST /books returns HTTP 201 with book body — returns HTTP 200, not 201

                Code shows: // TODO: return 201 — return ResponseEntity.ok(book);
                WRONG (comment is not evidence):
                [1] ✓ POST /books returns HTTP 201 with book body
                """
        ));
        rules.add(new ContractRule(
                "ACCEPTANCE_CRITERIA_CHECK",
                "If an ACCEPTANCE CRITERIA block is present, evaluate each criterion explicitly. " +
                "A criterion is met only if the CURRENT FILE CONTENT or diff demonstrates the required behavior. " +
                "If any criterion is unmet, reflect this in the score and Message. " +
                "Do NOT mark FULFILLED if any criterion is unmet.",
                """
                Acceptance criteria:
                * POST /tasks returns HTTP 201 with task body
                * POST /tasks returns HTTP 400 with {"message":"..."} on invalid input

                File shows: return ResponseEntity.ok(task);  (uses 200, not 201)
                WRONG:
                Score: 0.85
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: FULFILLED
                Message: Task creation implemented.

                OK:
                Score: 0.52
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: STAGNATING
                Message: Criterion 1 unmet: uses HTTP 200 instead of 201. Criterion 2 unmet: error body is a plain string.
                """
        ));
        rules.add(new ContractRule(
                "SPEC_ADHERENCE_CHECK",
                "When the goal or subgoal specifies exact technical requirements (HTTP status codes, response body format, " +
                "field names, validation rules, endpoint paths), cross-check the CURRENT FILE CONTENT block against those requirements. " +
                "A mutation that uses the wrong HTTP method, returns a plain string instead of a required JSON object, " +
                "misses a required field (e.g., {\"message\":\"...\"} for error bodies), or skips a required validation " +
                "must NOT be ACCEPTED. Reflect every spec deviation in the score and message explicitly.",
                """
                Goal: POST /tasks must return HTTP 400 with JSON body {"message": "..."} on invalid input.
                File shows: return ResponseEntity.badRequest().body("Invalid input: title too long");
                WRONG:
                Score: 0.80
                MutationAcceptanceState: ACCEPTED
                GoalProgressState: PROGRESSING
                Message: Validation is present.

                OK:
                Score: 0.45
                MutationAcceptanceState: REJECTED
                GoalProgressState: STAGNATING
                Message: Validation present but error body is a plain string, not a JSON object with a "message" field as required by the spec.
                Recommendation: Return ResponseEntity.badRequest().body(Map.of("message", "...")) or a dedicated error DTO.
                """
        ));
        rules.add(new ContractRule(
                "PROBLEM_CONFIRMATION",
                "If a PROBLEM CONTEXT block is present, confirm whether the mutation reduces or worsens the listed problems. Reflect this in the Message and score. If blockers persist, do not mark FULFILLED.",
                """
                Score: 0.45
                MutationAcceptanceState: REJECTED
                GoalProgressState: STAGNATING
                Message: Blocking problems remain (keywords indicate no meaningful change). Rejecting until structural change targets the issue.
                Recommendation: Modify the file referenced by path:... to introduce a meaningful structural improvement.
                """
        ));
        rules.add(new ContractRule(
                "KEYWORD_CONSISTENCY",
                "Use problem keywords as consistency anchors: if the diff does not address files/areas referenced by keywords (path:...), score should reflect low relevance unless the change clearly resolves the issue indirectly.",
                """
                Score: 0.35
                MutationAcceptanceState: REJECTED
                GoalProgressState: STAGNATING
                Message: Changes do not address the problem keywords or referenced files; no evidence of progress toward resolution.
                """
        ));
        rules.addAll(List.of( // specifics regarding MutationAcceptanceState & GoalProgressState
                new ContractRule(
                        "STATE_PAIRING_MATRIX",
                        "Use GoalProgressState consistent with MutationAcceptanceState. Allowed pairings: " +
                                "ACCEPTED -> PROGRESSING, STAGNATING, FULFILLED; " +
                                "REJECTED -> STAGNATING, REGRESSING, GIVEN_UP; " +
                                "NOT_EVALUATED -> NOT_EVALUATED only.",
                        """
                        OK:
                        Score: 0.72
                        MutationAcceptanceState: ACCEPTED
                        GoalProgressState: STAGNATING
                        Message: Improves structure but does not materially advance the goal yet.

                        WRONG:
                        Score: 0.72
                        MutationAcceptanceState: REJECTED
                        GoalProgressState: PROGRESSING
                        Message: (inconsistent states)
                        """
                ),
                new ContractRule(
                        "FULFILLED_IMPLIES_ACCEPTED_AND_HIGH_SCORE",
                        "If GoalProgressState is FULFILLED, then MutationAcceptanceState must be ACCEPTED, " +
                                "score should be high, and Message must explicitly say the goal/subgoal is achieved.",
                        """
                        Score: 0.92
                        MutationAcceptanceState: ACCEPTED
                        GoalProgressState: FULFILLED
                        Message: Subgoal requirements are fully met; expected behavior is implemented.
                        """
                ),
                new ContractRule(
                        "REGRESSING_IMPLIES_REJECTED",
                        "If GoalProgressState is REGRESSING, then MutationAcceptanceState must be REJECTED and " +
                                "Message must name the regression type (e.g., removed required behavior, broke build, worsened problem keywords).",
                        """
                        Score: 0.15
                        MutationAcceptanceState: REJECTED
                        GoalProgressState: REGRESSING
                        Message: Regression: removed required behavior / breaks expected API contract; rejecting.
                        """
                ),
                new ContractRule(
                        "GIVEN_UP_REQUIRES_CLEAR_RATIONALE_AND_REJECTION",
                        "GoalProgressState GIVEN_UP should be rare. If used: MutationAcceptanceState must be REJECTED, " +
                                "score should be low (<= 0.25), and Message must state why continuing this direction is not worthwhile.",
                        """
                        Score: 0.20
                        MutationAcceptanceState: REJECTED
                        GoalProgressState: GIVEN_UP
                        Message: Repeated attempts do not affect referenced problem areas; giving up on this mutation direction.
                        Recommendation: Target the referenced file path:... and introduce a meaningful structural change.
                        """
                ),
                new ContractRule(
                        "SCORE_STATE_CONSISTENCY_BANDS",
                        "Score must broadly match the selected states unless Message explicitly explains a hard constraint violation. " +
                                "Guidance: score >= 0.80 -> typically ACCEPTED + PROGRESSING/FULFILLED; " +
                                "0.50-0.79 -> often ACCEPTED (PROGRESSING or STAGNATING); " +
                                "0.30-0.49 -> typically REJECTED + STAGNATING; " +
                                "< 0.30 -> typically REJECTED + STAGNATING/REGRESSING/GIVEN_UP.",
                        """
                        Score: 0.88
                        MutationAcceptanceState: ACCEPTED
                        GoalProgressState: PROGRESSING
                        Message: Meaningful goal-relevant improvements detected.

                        Score: 0.22
                        MutationAcceptanceState: REJECTED
                        GoalProgressState: REGRESSING
                        Message: Regression introduced; rejecting.
                        """
                ),
                new ContractRule(
                        "ACCEPTED_STAGNATING_REQUIRES_EXPLANATION",
                        "If MutationAcceptanceState is ACCEPTED and GoalProgressState is STAGNATING, Message must explain both: " +
                                "(1) what improved, and (2) why goal progress did not materially advance.",
                        """
                        Score: 0.67
                        MutationAcceptanceState: ACCEPTED
                        GoalProgressState: STAGNATING
                        Message: Improves modularity/readability but does not implement additional goal requirements yet.
                        """
                ),
                new ContractRule(
                        "NOT_EVALUATED_PAIRING_RULE",
                        "If MutationAcceptanceState is NOT_EVALUATED, GoalProgressState must be NOT_EVALUATED. " +
                                "Message must state why evaluation cannot be performed (missing context, empty diff, invalid input, etc.).",
                        """
                        Score: 0.00
                        MutationAcceptanceState: NOT_EVALUATED
                        GoalProgressState: NOT_EVALUATED
                        Message: Evaluation skipped due to missing diff/context; cannot judge mutation impact.
                        """
                ),
                new ContractRule(
                        "EXPLICIT_INCONSISTENCY_CALLOUT",
                        "If the chosen states imply goal relevance but the diff/changed files do not touch referenced problem keywords/paths, " +
                                "Message must explicitly justify the indirect link (or score/state should reflect low relevance).",
                        """
                        Score: 0.35
                        MutationAcceptanceState: REJECTED
                        GoalProgressState: STAGNATING
                        Message: Changes do not touch referenced problem paths/keywords; no evidence of progress toward resolution.
                        """
                )
        ));
        return rules;
    }

    public static List<ContractRule> rules() {
        return Collections.unmodifiableList(STATIC_RULES);
    }

    public static String asPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("EVALUATION PROTOCOL CONTRACT v").append(VERSION).append("\n\n");
        for (ContractRule rule : rules()) {
            sb.append("RULE: ").append(rule.id()).append("\n")
              .append("Description: ").append(rule.description()).append("\n")
              .append("Example: ").append(rule.example()).append("\n\n");
        }
        return sb.toString();
    }

    public static String getSystemPrompt() {
        return "You are a mutation evaluation agent.\n\n" + asPromptBlock();
    }

    public static String getUserPrompt(CycleContextData context) {
        return getUserPrompt(context, 150);
    }

    public static String getUserPrompt(CycleContextData context, int fileContentMaxLines) {
        String findings = context.findings() == null ? "" : context.findings().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n"));

        StringBuilder scoreHistoryBlock = new StringBuilder();
        if (context.scoreHistoryByFile() != null) {
	        context.scoreHistoryByFile().forEach((file, history) -> {
	            scoreHistoryBlock.append(file).append(": ").append(history).append("\n");
	        });
        }

        StringBuilder deltaBlock = new StringBuilder();
        if (context.recentDeltas() != null) {
        	context.recentDeltas().forEach((file, delta) -> {
        		deltaBlock.append(file).append(": ").append(String.format("%.2f", delta)).append("\n");
        	});
        }
        
        String diffSummary = "";
        if (context.projectDiff() != null) {
        	diffSummary = DiffSummarizer.summarizeForPrompt(context.projectDiff().structuredDiff());
        }

        String fileContentBlock = buildFileContentBlock(context, fileContentMaxLines);

        String problemBlock = ProblemContextPromptBlocks.asPromptBlock(
                context.problemContext(),
                context.currentGoalId(),
                context.currentSubGoalId()
        );

        String criteriaBlock = context.currentSubGoalCriteria() == null || context.currentSubGoalCriteria().isEmpty()
                ? "No acceptance criteria were defined for this subgoal. Evaluate against subgoal description only."
                : context.currentSubGoalCriteria().stream()
                        .map(c -> "* " + c)
                        .collect(Collectors.joining("\n"));

        String criteriaCheckFormat = buildCriteriaCheckFormat(context.currentSubGoalCriteria());

        String planContextBlock = planContextBlock(context);

        return String.format("""
            Evaluate the recent mutation using the following context.

            --- SCORE (GOAL-BASED) ---
            Current Score: %s
            Last Score: %s

            --- GOAL CONTEXT ---
            Current Goal: %s
            Current SubGoal: %s
            Goal Id: %s
            SubGoal Id: %s

            --- ACCEPTANCE CRITERIA ---
            %s

            ROOT GOAL INVARIANTS: Before scoring, verify that the mutation honours any universal constraints stated in the Root Goal above that apply across all subgoals (error handling conventions, output format, data persistence strategy, naming standards, etc.). If the mutation violates a universal constraint that is not listed as an explicit criterion, treat it as an implicit unmet requirement and factor it into your score.

            --- FILE CHANGES ---
            Updated Files: %s
            Deleted Files: %s

            --- FINDINGS ---
            %s

            --- STRUCTURAL DIFF SUMMARY ---
            %s

            --- CURRENT FILE CONTENT ---
            %s

            --- PROJECT STATE KEYS ---
            %s

            --- SCORE HISTORY BY FILE ---
            %s

            --- RECENT SCORE DELTAS ---
            %s

            %s

            %s

            --- META INTENTION ---
            %s

            Respond using this exact format:
            Score: [decimal number between 0.0 and 1.0]
            MutationAcceptanceState: ACCEPTED, REJECTED or NOT_EVALUATED
            GoalProgressState: PROGRESSING, STAGNATING, REGRESSING, FULFILLED, GIVEN_UP or NOT_EVALUATED
            %s
            Message: One-line explanation of your reasoning.
            (Optional) Recommendation: Suggested improvement or direction for future mutations.
           \s""",
            context.evaluationOutcome() == null ? "(n/a)" : String.format("%.2f", context.evaluationOutcome().score()),
            context.lastScore() == null ? "(n/a)" : String.format("%.2f", context.lastScore()),
            context.currentGoalDescription(),
            context.currentSubGoalDescription(),
            context.currentGoalId(),
            context.currentSubGoalId(),
            criteriaBlock,
            context.projectDiff() == null ? null : context.projectDiff().changedFiles() == null ? null : context.projectDiff().changedFiles().keySet(),
            context.projectDiff() == null ? null : context.projectDiff().deletedPaths() == null ? null : context.projectDiff().deletedPaths(),
            findings.isBlank() ? "(none)" : findings,
            diffSummary,
            fileContentBlock,
            context.previousOutput() == null ? null : context.previousOutput().keySet(),
            scoreHistoryBlock,
            deltaBlock,
            problemBlock,
            planContextBlock != null ? planContextBlock : "",
            metaIntention(context),
            criteriaCheckFormat
        );
    }

    /**
     * Builds the CriteriaCheck section of the response format template.
     * When criteria are defined, pre-enumerates each line so the model fills in ✓/✗
     * rather than generating the list from scratch (which causes LLM omission).
     * When no criteria are defined, instructs the model to omit the block entirely.
     */
    private static String buildCriteriaCheckFormat(List<String> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return "(Omit CriteriaCheck: entirely — no acceptance criteria are defined.)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("CriteriaCheck: (").append(criteria.size())
          .append(" criteria — emit exactly one line per criterion; omitting any line treats it as unmet)\n");
        for (int i = 0; i < criteria.size(); i++) {
            sb.append("[").append(i + 1).append("] \u2713 or \u2717 ").append(criteria.get(i)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String buildFileContentBlock(CycleContextData context, int maxLines) {
        if (context.projectDiff() == null || context.projectDiff().changedFiles() == null
                || context.projectDiff().changedFiles().isEmpty()) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        context.projectDiff().changedFiles().forEach((path, content) -> {
            sb.append("File: ").append(path).append("\n");
            if (content == null || content.isBlank()) {
                sb.append("(empty)\n\n");
                return;
            }
            String[] lines = content.split("\n", -1);
            int lineLimit = Math.min(lines.length, maxLines);
            int charCount = 0;
            int i = 0;
            while (i < lineLimit && charCount < 4000) {
                String line = lines[i];
                if (charCount + line.length() > 4000) break;
                sb.append(line).append("\n");
                charCount += line.length() + 1;
                i++;
            }
            if (i < lines.length) {
                sb.append("... (truncated, ").append(lines.length - i).append(" more lines)\n");
            }
            sb.append("\n");
        });
        return sb.toString().stripTrailing();
    }

    @SuppressWarnings("unused")
	private static String indentMultiline(String code, String prefix) {
        return code.lines()
                   .map(line -> prefix + line)
                   .collect(Collectors.joining("\n"));
    }

    public static boolean isValidResponse(String response) {
        if (response == null || response.isBlank()) return false;

        boolean hasValidMutationState =
                response.contains("MutationAcceptanceState: ACCEPTED") ||
                response.contains("MutationAcceptanceState: REJECTED") ||
                response.contains("MutationAcceptanceState: NOT_EVALUATED");

        boolean hasValidGoalProgress =
                response.contains("GoalProgressState: PROGRESSING") ||
                response.contains("GoalProgressState: STAGNATING") ||
                response.contains("GoalProgressState: REGRESSING") ||
                response.contains("GoalProgressState: FULFILLED") ||
                response.contains("GoalProgressState: GIVEN_UP") ||
                response.contains("GoalProgressState: NOT_EVALUATED");

        boolean hasMessageLine = response.lines().anyMatch(line ->
                line.trim().toLowerCase().startsWith("message:") &&
                        line.trim().length() > "message:".length());

        boolean hasScoreLine = response.lines().anyMatch(line ->
                line.trim().toLowerCase().startsWith("score:")
        );

//        boolean hasOptionalRecommendation = response.lines().anyMatch(line ->
//	        line.trim().toLowerCase().startsWith("recommendation:")
//	    );

        return hasValidMutationState && hasValidGoalProgress && hasMessageLine && hasScoreLine;
    }

    /**
     * T3-BH: builds a PLAN CONTEXT block showing the plan rationale and sibling subgoals,
     * so the evaluation agent does not penalise for functionality that belongs to upcoming siblings.
     * Returns null when no plan context is available.
     */
    static String planContextBlock(CycleContextData input) {
        String rationale = input.planRationale();
        String parentId = input.currentSubGoalParentId();
        String currentId = input.currentSubGoalId();

        if (rationale == null && parentId == null) return null;

        List<GoalEntity> completedSiblings = input.completedGoals() == null ? List.of()
                : input.completedGoals().stream()
                        .filter(g -> parentId != null && parentId.equals(g.getParentId()))
                        .toList();
        List<GoalEntity> upcomingSiblings = input.remainingGoals() == null ? List.of()
                : input.remainingGoals().stream()
                        .filter(g -> parentId != null && parentId.equals(g.getParentId())
                                && !g.getId().equals(currentId))
                        .toList();

        if (rationale == null && completedSiblings.isEmpty() && upcomingSiblings.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("--- PLAN CONTEXT ---\n");
        if (rationale != null) {
            sb.append("Plan rationale: ").append(rationale).append("\n");
        }
        if (!completedSiblings.isEmpty()) {
            sb.append("Completed sibling subgoals (already implemented):\n");
            for (GoalEntity g : completedSiblings) {
                sb.append("  - ").append(g.getDescription()).append("\n");
            }
        }
        if (!upcomingSiblings.isEmpty()) {
            sb.append("Upcoming sibling subgoals (not yet implemented):\n");
            for (GoalEntity g : upcomingSiblings) {
                sb.append("  - ").append(g.getDescription()).append("\n");
            }
        }
        sb.append("Do not penalise for absent functionality that will be handled by upcoming sibling subgoals listed above.");
        return sb.toString();
    }

    private static String metaIntention(CycleContextData ctx) {
        if (ctx == null) return "(none)";
        MetaIntention metaIntention = ctx.metaIntention();
        if (metaIntention == null || metaIntention.role() == null && metaIntention.request() == null) return "(none)";
        String result = "";
        if (metaIntention.role() != null) {
            result += "Role: " + metaIntention.role() + "\n";
        }
        if (metaIntention.request() != null) {
            result += "Request: " + metaIntention.request() + "\n";
        }
        return result;
    }
}
