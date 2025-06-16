package com.walpro.autobuilder.model;

import com.walpro.autobuilder.diff.DiffReport;
import com.walpro.autobuilder.protocol.ProtocolContract;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

public record ProjectInput(
    DiffReport diff,
    String structureSummary,
    Map<String, String> previousOutput,
    Path outputPath,
    String currentGoal
) {
	public static ProjectInput empty(Path outputPath, String goal) {
	    return new ProjectInput(
	        ProtocolContract.emptyDiffReport(),
	        "",
	        Collections.emptyMap(),
	        outputPath,
	        goal
	    );
	}
}
