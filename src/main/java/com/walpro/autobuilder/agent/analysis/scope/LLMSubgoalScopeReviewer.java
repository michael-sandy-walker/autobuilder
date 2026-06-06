package com.walpro.autobuilder.agent.analysis.scope;

import com.walpro.autobuilder.agent.shared.LLMAgentProtocolInvoker;
import com.walpro.autobuilder.analysis.scope.ScopeAssessment;
import com.walpro.autobuilder.analysis.scope.ScopeFit;
import com.walpro.autobuilder.analysis.scope.SubgoalScopeReviewer;
import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.protocol.scope.SubgoalScopePromptBuilder;
import com.walpro.autobuilder.protocol.scope.SubgoalScopeProtocolContract;
import com.walpro.autobuilder.protocol.scope.SubgoalScopeResponseParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM-only, strict, bounded classifier for subgoal scope alignment.
 * Fail-open: returns IN_SCOPE with low confidence if protocol fails.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMSubgoalScopeReviewer implements SubgoalScopeReviewer {

    private final LLMClient llmClient;
    private final LLMAgentProtocolInvoker invoker;

    private final SubgoalScopeResponseParser parser = new SubgoalScopeResponseParser();

    @Override
    public ScopeAssessment assess(CycleContextData ctx, List<GoalEntity> remainingGoals, List<GoalEntity> completedGoals) {
        if (ctx == null) return null;

        // If nothing changed, scope-judging is meaningless. Don’t create REPLAN noise.
        if (isEmptyDiff(ctx)) {
            return new ScopeAssessment(
                    ScopeFit.IN_SCOPE,
                    0.20,
                    "No structural diff / no changed files — scope assessment skipped",
                    null
            );
        }

        String systemPrompt = SubgoalScopeProtocolContract.getSystemPrompt();
        String userPrompt = SubgoalScopePromptBuilder.buildUserPrompt(ctx, remainingGoals, completedGoals);

        var result = invoker.callWithValidation(
                llmClient,
                systemPrompt,
                userPrompt,
                SubgoalScopeProtocolContract::isValidResponse,
                parser::parse
        );

        if (!result.success()) {
            log.warn("SubgoalScopeReviewer fail-open: {}", result.failureReason());
            return new ScopeAssessment(
                    ScopeFit.IN_SCOPE,
                    0.10,
                    "Fail-open: scope protocol failed (" + result.failureReason() + ")",
                    null
            );
        }

        return result.outcome();
    }

    private boolean isEmptyDiff(CycleContextData ctx) {
        if (ctx.projectDiff() == null) return true;

        // Use whichever representation you actually populate.
        // From your logs, changedFiles and structuredDiff both exist in ProjectDiffResult.
        try {
            var diff = ctx.projectDiff();
            boolean noChangedFiles = diff.changedFiles() == null || diff.changedFiles().isEmpty();
            boolean noDeleted = diff.deletedPaths() == null || diff.deletedPaths().isEmpty();
            boolean noChildren = diff.structuredDiff() == null
                    || diff.structuredDiff().children() == null
                    || diff.structuredDiff().children().isEmpty();

            // consider empty if there is no evidence of change from any source
            return noChangedFiles && noDeleted && noChildren;
        } catch (Exception ignored) {
            // safest: don’t block pipeline on introspection mismatch
            return false;
        }
    }
}