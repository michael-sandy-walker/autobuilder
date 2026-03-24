package com.walpro.autobuilder.core.service.merge;

import java.util.Set;

public record MergeResult(boolean merged, boolean adopted, boolean failed, boolean skipped,
						  String previousContent, String currentContent, Set<String> contributingCycles, String reasoning) {

	public static MergeResult asMerged(String previousContent, String currentContent, Set<String> contributing) {
		return new MergeResult(true, false, false, false, previousContent, currentContent, contributing, null);
	}

	public static MergeResult asAdopted(String previousContent, String currentContent, Set<String> contributing) {
		return new MergeResult(false, true, false, false, previousContent, currentContent, contributing, null);
	}

	public static MergeResult asFailed(String reasoning) {
		return new MergeResult(false, false, true, false, null, null, Set.of(), reasoning);
	}

	public static MergeResult asSkipped() {
		return new MergeResult(false, false, false, true, null, null, Set.of(), null);
	}

	public boolean wasSuccessful() {
		return merged || adopted;
	}
}