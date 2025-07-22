package com.walpro.autobuilder.diff;

import com.walpro.autobuilder.tools.diff.ChangedMethod;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record ProjectDiffResult(
    DiffReport diffReport,                  // legacy-compatible high-level view
    List<ChangedMethod> changedMethods,     // for evaluation agent
    Map<String, String> changedFiles,       // for evolution agent (path → content)
    
    // Mutation context
//    List<String> changedPaths, // not necessary, because changedFiles already contains the path
    Set<String> deletedPaths
//    List<String> oldFiles // not necessary, because deletedPaths should represent this
) {
	public String summary() {
	    return diffReport != null ? diffReport.summary() : "(no summary available)";
	}
}
