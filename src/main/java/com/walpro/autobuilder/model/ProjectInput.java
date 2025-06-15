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
    Path outputPath
) {
	public static ProjectInput empty(Path outputPath, String structureSummary) {
	    return new ProjectInput(
	        ProtocolContract.emptyDiffReport(),
	        structureSummary,
	        Collections.emptyMap(),
	        outputPath
	    );
	}
}
