package com.walpro.autobuilder.agent.analysis;

import com.walpro.autobuilder.agent.analysis.model.AnalysisOutcome;
import com.walpro.autobuilder.core.engine.ExecutionAgent;
import com.walpro.autobuilder.core.lifecycle.BasicSignals;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;
import com.walpro.autobuilder.core.lifecycle.SignalType;
import com.walpro.autobuilder.goal.GoalEntity;
import com.walpro.autobuilder.goal.GoalKindClassifier;
import com.walpro.autobuilder.goal.GoalPlannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisAgent implements ExecutionAgent {

    private final AnalysisLLMAdapter adapter;
    private final GoalPlannerService goalPlannerService;
    private final GoalKindClassifier kindClassifier;

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("Executing agent (role={})", role());

        AnalysisOutcome prev = context.analysisOutcome();
        String prevGoal = context.analysisScopeGoalId();
        String prevSub  = context.analysisScopeSubGoalId();

        AnalysisOutcome outcome = adapter.analyze(context);
        persistAnalysisClassifiedKind(context, outcome);
        autoInjectExecutableSubGoals(context, outcome);

        return context
                .withLastAnalysisOutcome(prev)
                .withLastAnalysisScopeGoalId(prevGoal)
                .withLastAnalysisScopeSubGoalId(prevSub)
                .withAnalysisOutcome(outcome)
                .withAnalysisScopeGoalId(context.currentGoalId())
                .withAnalysisScopeSubGoalId(context.currentSubGoalId());
    }

    @Override
    public String name() {
        return "analysis";
    }

    @Override
    public String role() {
        return "analysis";
    }

    @Override
    public Set<SignalType> getSupportedSignals() {
        return BasicSignals.ANALYSIS_SIGNALS;
    }

    @Override
    public double priority(CycleContextData context) {
        return 1.5; // between planner and evaluation by default
    }

    private void autoInjectExecutableSubGoals(CycleContextData ctx, AnalysisOutcome outcome) {
        if (outcome == null || outcome.proposedExecutableSubGoals() == null || outcome.proposedExecutableSubGoals().isEmpty()) {
            return;
        }

        String parentId = ctx.currentSubGoalId();
        if (parentId == null || parentId.isBlank()) {
            return; // nothing to attach to
        }

        // dedupe: compare normalized descriptions among existing children under the same parent
        List<GoalEntity> existingChildren = goalPlannerService.getAllGoals().stream()
                .filter(g -> parentId.equals(g.getParentId()))
                .toList();

        boolean parentHasActiveChildren = existingChildren.stream().anyMatch(g -> g.getStatus() == com.walpro.autobuilder.goal.GoalStatus.ACTIVE);

        boolean setFirstAsCurrent = !parentHasActiveChildren;
        boolean firstInjected = true;

        for (String raw : outcome.proposedExecutableSubGoals()) {
            if (raw == null) continue;
            String desc = raw.trim();
            if (desc.isEmpty()) continue;

            GoalKindClassifier.GoalKind kind = kindClassifier.classify(desc);
            if (kind != GoalKindClassifier.GoalKind.EXECUTABLE) {
                continue; // only auto-inject executable ones
            }

            String norm = normalize(desc);
            boolean alreadyExists = existingChildren.stream()
                    .anyMatch(g -> normalize(g.getDescription()).equals(norm) && g.getStatus() == com.walpro.autobuilder.goal.GoalStatus.ACTIVE);

            if (alreadyExists) continue;

            String id = "goal-" + UUID.randomUUID();
            boolean makeCurrent = setFirstAsCurrent && firstInjected;

            goalPlannerService.addSubGoal(id, desc, makeCurrent, parentId, "analysis", kind);

            firstInjected = false;
        }
    }

    private void persistAnalysisClassifiedKind(CycleContextData ctx, AnalysisOutcome outcome) {
        if (outcome == null || outcome.goalKind() == null || outcome.goalKind().isBlank()) return;

        String subGoalId = ctx.currentSubGoalId();
        if (subGoalId == null || subGoalId.isBlank()) return;

        GoalEntity sg = goalPlannerService.getGoalById(subGoalId);
        if (sg == null) return;

        String kind = outcome.goalKind().trim().toUpperCase();
        if (!kind.equals("COGNITIVE") && !kind.equals("EXECUTABLE") && !kind.equals("HYBRID")) {
            log.warn("AnalysisAgent returned unrecognised GoalKind '{}' for subgoal {}, ignoring", outcome.goalKind(), subGoalId);
            return;
        }

        sg.getMetadata().put("analysisClassifiedKind", kind);
        goalPlannerService.saveGoalMetadata(sg);
        log.info("AnalysisAgent classified subgoal {} as {}", subGoalId, kind);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}