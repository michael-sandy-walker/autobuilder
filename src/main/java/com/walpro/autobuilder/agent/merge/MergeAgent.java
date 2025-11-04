package com.walpro.autobuilder.agent.merge;

import java.util.List;

import com.walpro.autobuilder.agent.merge.model.FileVariant;
import com.walpro.autobuilder.agent.merge.model.MergeOutcome;
import com.walpro.autobuilder.core.lifecycle.CycleContextData;

public interface MergeAgent {
	MergeOutcome merge(String path, String baseContent, List<FileVariant> variants, CycleContextData context);
}
