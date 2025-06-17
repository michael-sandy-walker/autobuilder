package com.walpro.autobuilder.evaluation.dynamic;

import com.walpro.autobuilder.client.LLMClient;
import com.walpro.autobuilder.client.dto.ChatResponse;
import com.walpro.autobuilder.client.dto.Message;
import com.walpro.autobuilder.evaluation.model.EvaluationOutcome;
import com.walpro.autobuilder.evaluation.model.GoalProgressState;
import com.walpro.autobuilder.evaluation.model.MutationAcceptanceState;
import com.walpro.autobuilder.model.EvaluationInput;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LLMLogicInterpreter implements DynamicLogicInterpreter {

    private final LLMClient llm;

    public LLMLogicInterpreter(LLMClient llm) {
        this.llm = llm;
    }

    @Override
    public EvaluationOutcome evaluate(DynamicEvaluatorDefinition def, EvaluationInput input) {
        String systemPrompt = """
            You are an internal evaluator module named "%s" with role "%s".
            You must decide if a mutation should be accepted or rejected, and whether it progresses the current goal.
            Base your decision on the behavior definition below.
            
            Behavior:
            %s
            
            Respond in the format:
            MutationAcceptanceState: ACCEPTED or REJECTED
            GoalProgressState: PROGRESSING, STAGNATING, or NOT_EVALUATED
            Message: One-line explanation.
            """.formatted(def.id(), def.role(), def.behavior());

        String userPrompt = formatInput(input);

        ChatResponse response = llm.ask(List.of(
                new Message("system", systemPrompt),
                new Message("user", userPrompt)
        )).getBody();

        if (response == null || response.getFirstContent() == null) {
            return new EvaluationOutcome(
                    MutationAcceptanceState.REJECTED,
                    GoalProgressState.NOT_EVALUATED,
                    "LLM response was null",
                    null
            );
        }

        return parseResponse(response.getFirstContent());
    }

    private String formatInput(EvaluationInput input) {
        return """
            Evaluation Input:
            Score: %s
            Last Score: %s
            Goal: %s
            Updated Files: %s
            Deleted Files: %s
            Findings: %s
            Project State Keys: %s
            """.formatted(
                input.score(),
                input.lastScore(),
                input.currentGoal(),
                input.updatedFiles(),
                input.deletedFiles(),
                input.findings().stream().map(Object::toString).toList(),
                input.projectState().keySet()
        );
    }

    private EvaluationOutcome parseResponse(String content) {
        MutationAcceptanceState mutation = content.contains("REJECTED")
                ? MutationAcceptanceState.REJECTED
                : MutationAcceptanceState.ACCEPTED;

        GoalProgressState goal;
        if (content.contains("PROGRESSING")) {
            goal = GoalProgressState.PROGRESSING;
        } else if (content.contains("STAGNATING")) {
            goal = GoalProgressState.STAGNATING;
        } else {
            goal = GoalProgressState.NOT_EVALUATED;
        }

        String msg = content.lines()
                .filter(line -> line.toLowerCase().startsWith("message:"))
                .map(line -> line.substring("message:".length()).trim())
                .findFirst()
                .orElse("No explanation provided.");

        return new EvaluationOutcome(mutation, goal, msg, null);
    }
}
