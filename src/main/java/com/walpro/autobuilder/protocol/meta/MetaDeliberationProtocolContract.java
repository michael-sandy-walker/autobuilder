package com.walpro.autobuilder.protocol.meta;

import java.util.List;
import com.walpro.autobuilder.protocol.core.ContractRule;

public class MetaDeliberationProtocolContract {

    public static final String VERSION = "0.4-meta-plan";

    public static List<ContractRule> rules() {
        return List.of(
                new ContractRule(
                        "STRUCTURED_ONLY",
                        "Respond using ONLY the required headers. No prose outside headers.",
                        """
                        Stop: false
                        Mode: CONTINUE
                        Rationale: Evaluation missing for latest mutation; do next steps in-cycle.
                        Steps:
                        - Step: 1
                          Need: NEXT_STEP
                          TargetRole: EVOLUTION
                          Request: Produce a non-repetitive mutation for the active subgoal.
                          StopIf: MutationAcceptanceState is ACCEPTED
                        - Step: 2
                          Need: NEXT_STEP
                          TargetRole: EVALUATION
                          Request: Evaluate the latest mutation and emit acceptance + progress state.
                          StopIf: MutationAcceptanceState is ACCEPTED or MutationAcceptanceState is REJECTED
                        """
                ),
                new ContractRule(
                        "STOP_REQUIRED",
                        "Stop must be true or false.",
                        "Stop: true"
                ),
                new ContractRule(
                        "MODE_REQUIRED",
                        "Mode must be one of: CONTINUE, PAUSE, NOOP.",
                        "Mode: PAUSE"
                ),
                new ContractRule(
                        "STEPS_REQUIRED_WHEN_CONTINUE",
                        "If Stop=false and Mode=CONTINUE then Steps must contain 1..5 steps.",
                        """
                        Stop: false
                        Mode: CONTINUE
                        Rationale: Planner outcome missing; bootstrap.
                        Steps:
                        - Step: 1
                          Need: MISSING_INFO
                          TargetRole: PLANNER
                          Request: Produce planning outcome for current goal/subgoal.
                          StopIf: HasPlannerOutcome is true
                        """
                ),
                new ContractRule(
                        "NO_CODE",
                        "Do not output code blocks or file content.",
                        "WRONG: ```java ...```"
                ),
                new ContractRule(
                        "RATIONALE_MUST_MATCH_STEPS",
                        """
                        Rationale must be consistent with the selected steps and eligibility.
                        - If Rationale mentions a role is needed/missing (PLANNER/EVALUATION/MERGE/EVOLUTION),
                          then either (a) that role appears in Steps, or (b) Rationale explicitly states that role is blocked (canExecute=false).
                        - Do not say "Evaluation missing" if TargetRole EVALUATION is not in Steps unless you explicitly say "EVALUATION blocked".""",
                        """
                           Stop: false
                           Mode: CONTINUE
                           Rationale: X is blocked (canExecute=false). Proceed with Y to generate a candidate mutation.
                           Steps:
                           - Step: 1
                             Need: NEXT_STEP
                             TargetRole: Y
                             Request: Produce a non-repetitive mutation for the active subgoal.
                             StopIf: HasYOutcome is true
                        """
                ),
                new ContractRule(
                        "PROBLEM_ROUTING",
                        "If BlockingInScope > 0, route next steps toward resolving blockers first (typically EVOLUTION or EVALUATION). Avoid planning new feature subgoals until blockers are resolved or downgraded.",
                        """
                        Step 1: Role: EVALUATION
                          Need: MISSING_INFO
                          Request: Evaluate whether the latest changes reduce the blocker keywords in the problem context.
                          StopIf: A clear accepted evaluation confirms blocker keywords are resolved or downgraded.
                        Step 2: Role: EVOLUTION
                          Need: UNSTICK
                          Request: Apply a focused structural change in the file referenced by path:... to resolve the blocker keyword(s).
                          StopIf: Diff summary shows meaningful structural change aligned with problem keywords.
                        """
                )

        );
    }

    public static String asPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("META DELIBERATION PROTOCOL CONTRACT v").append(VERSION).append("\n\n");
        for (ContractRule rule : rules()) {
            sb.append("RULE: ").append(rule.id()).append("\n")
                    .append("Description: ").append(rule.description()).append("\n")
                    .append("Example:\n").append(rule.example()).append("\n\n");
        }
        return sb.toString();
    }

    public static String getSystemPrompt() {
        return "You are the Autobuilder meta-orchestrator. Output a short ordered in-cycle plan (1..5 steps). "
                + "Each step targets exactly one role and includes a StopIf guard.\n"
                + "CRITICAL: Only propose steps for roles that are executable per the eligibility block (canExecute=true). "
                + "Do NOT propose roles with canExecute=false.\n\n"
                + "FORMAT RULE (MANDATORY): Always output these headers exactly once: Stop:, Mode:, Rationale:, Steps:.\n"
                + asPromptBlock();
    }

    public static boolean isValidResponse(String response) {
        if (response == null || response.isBlank()) return false;
        String r = response.toLowerCase();
        return r.contains("stop:") && r.contains("mode:") && r.contains("rationale:") && r.contains("steps:");
    }
}
