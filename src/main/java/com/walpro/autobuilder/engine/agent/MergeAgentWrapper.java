package com.walpro.autobuilder.engine.agent;

import java.io.IOException;

import com.walpro.autobuilder.coordinator.MergeCoordinator;
import com.walpro.autobuilder.engine.ExecutionAgent;
import com.walpro.autobuilder.lifecycle.CycleContextData;

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
        try {
			mergeCoordinator.mergeCompletedBranch(context);
		} catch (IOException e) {
			log.error("Error during merge of cycle {}: {}", context.cycleName(), e.getMessage());
		}
        return context;
    }

    @Override
    public String name() {
        return "MergeAgent";
    }
}
