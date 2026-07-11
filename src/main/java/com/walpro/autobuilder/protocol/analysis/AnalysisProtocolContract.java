package com.walpro.autobuilder.protocol.analysis;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.SignalReadout;
import com.walpro.autobuilder.protocol.abandoned.RelatedAbandonedWorkBlock;

public final class AnalysisProtocolContract {

    private AnalysisProtocolContract() {}

    public static String getSystemPrompt() {
        return """
            You are the Analysis agent for Autobuilder.
            You do NOT write code and do NOT propose code blocks.
            Your job is to produce structured diagnostic output and executable follow-up subgoals.
            """;
    }

    public static String getUserPrompt(CycleContextData ctx) {
        String goal = ctx.currentGoalDescription() == null ? "(none)" : ctx.currentGoalDescription();
        String sub = ctx.currentSubGoalDescription() == null ? "(none)" : ctx.currentSubGoalDescription();

        return """
            Diagnose the current situation and produce actionable follow-up tasks.

            --- GOAL ---
            %s

            --- SUBGOAL ---
            %s

            --- SIGNALS ---
            %s
            (If REPETITION_DETECTED or STAGNATION signals are present at significant strength, consider emitting #InjectionThrottle below.)

            --- PROBLEM CONTEXT ---
            %s

            --- DIFF SUMMARY ---
            %s
            %s
            %s
            Respond using this exact format:
            Findings:
            - [SEVERITY] <type> :: <observation> :: <path or (n/a)>
            Hypotheses:
            - <one hypothesis per line>
            ProposedExecutableSubGoals:
            - <subgoal description — MUST be directly executable as code changes>
            Criteria:
            * <acceptance criterion 1>
            * <acceptance criterion 2>
            - <next subgoal description>
            Criteria:
            * <acceptance criterion 1>
            Message: <one line>
            GoalKind: <COGNITIVE|EXECUTABLE|HYBRID>
            (COGNITIVE = investigation/analysis only, no code changes required;
             EXECUTABLE = concrete code change, no investigation needed;
             HYBRID = requires both investigation and code changes)
            PossiblyComplete: <true|false>
            (true ONLY when there is positive indication that the current subgoal is already fully
             implemented — e.g. the diff shows the required change was already merged, or the
             output already contains all expected artefacts. false in all other cases.)
            #InjectionThrottle: <reason>
            (optional — emit ONLY when REPETITION_DETECTED or STAGNATION signals are strong and
             further subgoal injection would be counterproductive. When present, all injection is
             suppressed this cycle regardless of proposed subgoals above.)

            IMPORTANT: Every subgoal under ProposedExecutableSubGoals MUST be followed by a Criteria: block
            with at least one observable, testable acceptance criterion. Omitting criteria is not permitted.
            Criteria must describe observable behaviour (endpoints, status codes, response bodies, stored
            state) — never internal structure such as method names, field names, or helper classes; the
            implementation may choose any internal shape that produces the behaviour.

            REQUIREMENT_PHRASING RULE: Phrase every ProposedExecutableSubGoal as the required behaviour
            (what the system must do), never as a problem statement (what it currently fails to do).
            Write "the endpoint returns all matching records", not "the endpoint does not return all
            matching records". Descriptions are later verified as requirements against the output;
            a defect-statement phrasing inverts that check and can never be confirmed.

            ROOT_GOAL_SCOPE RULE: All entries under ProposedExecutableSubGoals MUST be directly required
            to implement a feature or endpoint explicitly named in the root goal above. Do NOT propose
            infrastructure improvements (logging, error handling, refactoring, testing, code style) unless
            the root goal explicitly requires them. If no on-goal subgoals remain, leave ProposedExecutableSubGoals
            empty rather than inventing work outside the root goal spec.

            INDEPENDENT_ENDPOINT_SPLIT RULE: If a single ProposedExecutableSubGoal's criteria span more than
            one independent HTTP endpoint or feature (e.g. criteria reference both "POST /loans" and
            "GET /members"), split it into one subgoal per endpoint, each with its own Criteria block.
            Do NOT split subgoals whose criteria all address the same endpoint — coordinated multi-file
            work for one feature stays as one subgoal.
            """.formatted(
                goal,
                sub,
                signalsBlock(ctx),
                ctx.problemContext() == null ? "(none)" : ("OpenProblemsInScope: " + safeInt(ctx.problemContext().open() == null ? 0 : ctx.problemContext().open().size())),
                ctx.projectDiff() == null ? "(none)" : ctx.projectDiff().summary(),
                packageConstraintBlock(ctx),
                abandonedGoalsBlock(ctx)
        );
    }

    static String abandonedGoalsBlock(CycleContextData ctx) {
        String rendered = RelatedAbandonedWorkBlock.render(ctx, RelatedAbandonedWorkBlock.Audience.ANALYSIS);
        return rendered == null ? "" : rendered;
    }

    /**
     * Renders present signal NAMES only (no strengths). 7a687f5 changed this to NAME=strength
     * so the LLM could judge the #InjectionThrottle "at significant strength" condition, but
     * that regressed generated-code quality (field-confirmed 2026-07-11): carriers saturate at
     * 3.00, so the LLM read "significant strength" nearly every cycle and over-throttled subgoal
     * injection, leaving endpoints unmapped and stalling. Reverted to the proven-good e0b5bff
     * names-only render (23/23). See {@link SignalReadout#namesLine()}.
     */
    static String signalsBlock(CycleContextData ctx) {
        return SignalReadout.of(ctx).namesLine();
    }

    /**
     * Renders a PACKAGE CONSTRAINT block when the project's base Java package is known.
     * Forces all file-path acceptance criteria to live under the project's actual package root —
     * without this, the analyzer LLM has been observed inventing fictitious paths like
     * {@code src/main/java/com/example/library/loan/Loan.java} when the project actually uses
     * {@code com/example/demo/...}, making the criteria structurally unsatisfiable and stranding
     * subgoals as ACTIVE indefinitely.
     */
    static String packageConstraintBlock(CycleContextData ctx) {
        if (ctx == null) return "";
        String basePackage = ctx.establishedBasePackage();
        if (basePackage == null || basePackage.isBlank()) return "";
        String basePath = "src/main/java/" + basePackage.replace('.', '/');
        return """

            --- PACKAGE CONSTRAINT ---
            This project's established Java base package is `%s`.
            Every file path mentioned in any Criteria entry MUST start with `%s/`.
            Do NOT reference paths under any other top-level package — a file written under
            `%s/<sub>/Name.java` will NOT match a criterion that names a different path.

            PACKAGE COHERENCE RULE — anchor each criterion's path so it matches the file's responsibility:
              • Files with DIFFERENT responsibilities should live in DIFFERENT sub-packages of `%s`.
              • Choose sub-package names that describe the responsibility of the classes inside.
              • Use the SAME sub-package for the SAME responsibility throughout the project —
                criteria for the same kind of class should anchor to the same sub-package every time.
              • If a sub-package matching the criterion's responsibility already exists under `%s`,
                anchor the criterion's path there.

            """.formatted(basePackage, basePath, basePath, basePackage, basePackage);
    }

    public static boolean isValidResponse(String content) {
        if (content == null) return false;
        String s = content.trim();
        return s.contains("Findings:") && s.contains("Hypotheses:") && s.contains("ProposedExecutableSubGoals:") && s.contains("Message:") && s.contains("GoalKind:") && s.contains("PossiblyComplete:");
    }

    private static int safeInt(int v) { return v; }
}