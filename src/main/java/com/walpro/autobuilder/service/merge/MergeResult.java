package com.walpro.autobuilder.service.merge;

import java.util.Set;

public record MergeResult(boolean merged, boolean promoted, boolean failed, boolean skipped,
		String previousContent, String currentContent, Set<String> contributingCycles) {

	public static MergeResult asMerged(String previousContent, String currentContent, Set<String> contributing) {
		return new MergeResult(true, false, false, false, previousContent, currentContent, contributing);
	}

	public static MergeResult asPromoted(String previousContent, String currentContent, Set<String> contributing) {
		return new MergeResult(false, true, false, false, previousContent, currentContent, contributing);
	}

	public static MergeResult asFailed() {
		return new MergeResult(false, false, true, false, null, null, Set.of());
	}

	public static MergeResult asSkipped() {
		return new MergeResult(false, false, false, true, null, null, null);
	}

	public boolean wasSuccessful() {
		return merged || promoted;
	}
}