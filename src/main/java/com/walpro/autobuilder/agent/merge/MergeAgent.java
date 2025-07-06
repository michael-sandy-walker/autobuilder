package com.walpro.autobuilder.agent.merge;

import com.walpro.autobuilder.agent.merge.model.MergeInput;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;

public interface MergeAgent {
    MergeOutcome merge(MergeInput input);
}
