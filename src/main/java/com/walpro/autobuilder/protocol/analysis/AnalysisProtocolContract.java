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
            - <each MUST be directly executable as code changes>
            Message: <one line>
            GoalKind: <COGNITIVE|EXECUTABLE|HYBRID>
            (COGNITIVE = investigation/analysis only, no code changes required;
             EXECUTABLE = concrete code change, no investigation needed;
             HYBRID = requires both investigation and code changes)
            PossiblyComplete: <true|false>
            (true ONLY when there is positive evidence that the current subgoal is already fully
             implemented — e.g. the diff shows the required change was already merged, or the
             output already contains all expected artefacts. false in all other cases.)
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