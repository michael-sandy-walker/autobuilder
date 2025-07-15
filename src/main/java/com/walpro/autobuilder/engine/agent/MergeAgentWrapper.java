package com.walpro.autobuilder.engine.agent;

import com.walpro.autobuilder.coordinator.MergeCoordinator;
import com.walpro.autobuilder.engine.ExecutionAgent;
import com.walpro.autobuilder.model.CycleContextData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class MergeAgentWrapper implements ExecutionAgent {

    private final MergeCoordinator mergeCoordinator;

    @Override
    public boolean canExecute(CycleContextData context) {
        return context.subgoalPromoted() && !context.merged();
    }

    @Override
    public CycleContextData execute(CycleContextData context) {
        log.info("🔀 Merging after subgoal promotion.");
        return mergeCoordinator.mergeCompletedBranch(context)
                .withMerged(true);
    }

    @Override
    public String name() {
        return "MergeAgent";
    }
}
