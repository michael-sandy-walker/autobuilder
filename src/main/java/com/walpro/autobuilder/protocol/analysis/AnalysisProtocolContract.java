package com.walpro.autobuilder.protocol.analysis;

import com.walpro.autobuilder.core.lifecycle.CycleContextData;

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

            Respond using this exact format:
            Findings:
            - [SEVERITY] <type> :: <evidence> :: <path or (n/a)>
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
            (true ONLY when there is positive evidence that the current subgoal is already fully
             implemented — e.g. the diff shows the required change was already merged, or the
             output already contains all expected artefacts. false in all other cases.)
            #InjectionThrottle: <reason>
            (optional — emit ONLY when REPETITION_DETECTED or STAGNATION signals are strong and
             further subgoal injection would be counterproductive. When present, all injection is
             suppressed this cycle regardless of proposed subgoals above.)

            IMPORTANT: Every subgoal under ProposedExecutableSubGoals MUST be followed by a Criteria: block
            with at least one observable, testable acceptance criterion. Omitting criteria is not permitted.

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
                ctx.signals() == null ? "(none)" : ctx.signals().keySet().toString(),
                ctx.problemContext() == null ? "(none)" : ("OpenProblemsInScope: " + safeInt(ctx.problemContext().open() == null ? 0 : ctx.problemContext().open().size())),
                ctx.projectDiff() == null ? "(none)" : ctx.projectDiff().summary()
        );
    }

    public static boolean isValidResponse(String content) {
        if (content == null) return false;
        String s = content.trim();
        return s.contains("Findings:") && s.contains("Hypotheses:") && s.contains("ProposedExecutableSubGoals:") && s.contains("Message:") && s.contains("GoalKind:") && s.contains("PossiblyComplete:");
    }

    private static int safeInt(int v) { return v; }
}